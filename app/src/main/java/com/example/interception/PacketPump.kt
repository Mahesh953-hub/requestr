package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.QuicConnectionEntity
import com.example.data.local.QuicDecodeStatus
import com.example.data.local.RequestrDatabase
import com.example.data.local.UdpFlowEntity
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.tun.IpAddressCodec
import com.example.interception.tun.IpFragmentReassembler
import com.example.interception.tun.TunRelay
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Reads real IP packets off the tun file descriptor, feeds them through the
 * [CaptureEngine] pipeline, and reports genuine counters.
 *
 * **Pipeline (completed):**
 * 1. Parses actual IPv4/IPv6 headers
 * 2. Tracks distinct 5-tuple flows as "active connections"
 * 3. [CaptureEngine] handles TCP stream reassembly + HTTP/1.x parsing → Room
 * 4. TLS ClientHello SNI extraction (flag-only; no decryption without MITM)
 * 5. Periodic flush of real counters into [InterceptionStateRepository] + session row
 *
 * **Not yet built (next increment):**
 * - TLS MITM proxy that presents our CA to the client, connects to the real
 *   server, and decrypts application data on both sides
 *
 * Deliberate design rule: this class never invents a transaction. If the pump cannot decode a real
 * HTTP exchange, it records nothing in the traffic table rather than writing a plausible-looking
 * row. Counters reflect only bytes actually observed.
 */
class PacketPump(
    private val context: Context,
    private val input: FileInputStream,
    private val output: FileOutputStream,
    private val sessionId: Long,
    private val db: RequestrDatabase,
    /** Called for each parsed DNS query (Fix #16). */
    var dnsCallback: ((com.example.interception.dns.DnsParser.DnsQuery, String, Int, String, Int) -> Unit)? = null,
    printUndecoded: Boolean = true,
    dumpToFile: Boolean = false,
    captureEnabled: Boolean = true,
    enhancedMode: Boolean = false,
    /** Task 9: threaded from `SettingsDataStore.requestLogging`/`.responseLogging`. */
    requestLogging: Boolean = true,
    responseLogging: Boolean = true,
    /**
     * `VpnService.protect(...)` hook, threaded in from [RequestrVpnService] so relay upstream
     * sockets don't loop back into our own tun. Defaults to [com.example.interception.tun.NoOpProtectHook]
     * so existing/test call sites that don't care about the relay keep compiling; production
     * always passes a real hook.
     */
    protectHook: com.example.interception.tun.ProtectHook = com.example.interception.tun.NoOpProtectHook,
    /**
     * Gap #1 Approach A: true once [RequestrVpnService] has handed this session's tun fd to
     * the native tun2proxy bridge (`librequestr_tun2proxy.so`), which reads/writes that fd
     * directly on its own thread. When true, [run] must NOT also read [input]/write [output]
     * on that same fd -- two independent readers of one fd number would race, duplicate, or
     * corrupt packets -- so [run] takes the [runNativeBridgeLoop] branch instead of the normal
     * tun-read pipeline. Plain `Boolean`/lambda params (rather than a direct dependency on
     * [com.example.interception.tun.Tun2ProxyBridge]) keep this class loosely coupled and
     * testable with a fake poller.
     */
    private val nativeBridgeMode: Boolean = false,
    /**
     * Polled once per flush cycle in native-bridge mode to obtain cumulative (tx, rx) byte
     * counters from the native side. Null means no stats are available (bridge unavailable),
     * in which case [byteCount] simply stays at its last known value rather than being
     * fabricated.
     */
    private val nativeTrafficStatsPoll: (() -> Pair<Long, Long>?)? = null,
) {

    private var packetCount = 0
    private var byteCount = 0L

    /**
     * Userspace TCP+UDP relay — this is what finally does something with [output] (task 4).
     * Every packet the pump reads off the tun fd, after capture/PCAP ingestion, is also handed
     * here so the app it belongs to actually gets a response instead of the packet vanishing
     * (the VPN_TUN blackhole recorded in AUDIT_REPORT.md's v5.8-backup addendum). Exposed so the
     * service can stop it and the UI can observe [TunRelay.stats].
     */
    private val tunWriter = com.example.interception.tun.TunWriter(com.example.interception.tun.TunSink { datagram -> output.write(datagram) })

    val relay: TunRelay = TunRelay(protectHook, tunWriter)

    /**
     * ICMP echo relay (task 6), isolated from [relay]'s TCP/UDP flow tables and selector loop.
     * Wired in as [TunRelay.icmpGuestHandler] below so a failure inside [IcmpRelay] can never
     * propagate into TCP/UDP handling -- [TunRelay.offer] only ever calls this handler and
     * [IcmpRelay.offer] itself never throws (every failure is counted, not propagated). Exposed
     * (not private), matching [relay]'s own visibility, so a future relay-health UI surface
     * (task 14) can read [com.example.interception.tun.IcmpRelay.unrelayableCount] /
     * `outstandingCount()` the same way [RequestrVpnService] already reaches [relay]'s stats via
     * its `activePump` reference.
     */
    val icmpRelay = com.example.interception.tun.IcmpRelay(protectHook, tunWriter)

    init {
        relay.icmpGuestHandler = { datagram, header -> icmpRelay.offer(datagram, header) }
    }

    /**
     * Task 9: [parseIpv6] needs [IpFragmentReassembler.walkIpv6ExtensionHeaders] to find the
     * true transport offset instead of assuming a fixed offset 40, exactly like [relay]'s own
     * v6 header parsing (task 3's work) already does. [walkIpv6ExtensionHeaders] is a pure
     * function of the packet bytes -- it reads no group/reassembly state -- so a separate
     * instance here (distinct from [relay]'s own internal one) is safe; this one is never fed
     * through [IpFragmentReassembler.offer].
     */
    private val ipv6HeaderWalker = IpFragmentReassembler()

    /** Distinct flows seen recently, keyed by 5-tuple, with last-seen timestamps for expiry. */
    private val activeFlows = LinkedHashMap<String, Long>()

    /** M1: last time (ms) each UDP flow / QUIC connection row was upserted, to throttle writes. */
    private val lastUdpPersist = mutableMapOf<String, Long>()
    private val quicConnections = mutableSetOf<String>()

    /**
     * P2: in-memory QUIC connection/stream state machine, keyed by the same connectionId used
     * for the [QuicConnectionEntity] row. Only a connection-level summary is persisted to Room
     * (the schema has no per-stream columns) -- these live objects are what actually track
     * [com.example.interception.quic.QuicState] transitions and byte counters in real time, the
     * way [com.example.interception.tcp.TcpReassembler] tracks per-flow [com.example.interception.tcp.TcpStream] state.
     */
    private val quicConnectionState = LinkedHashMap<String, com.example.interception.quic.QuicConnection>()

    /**
     * Guards every QUIC decode-state structure above ([quicConnections], [quicConnectionState],
     * [quicDecoders], [http3RowIds], [pendingHttp3Writes], [quicConnectionGenerations]) plus
     * [activeFlows] and [lastUdpPersist].
     *
     * These are mutated from the tun-read coroutine ([parseIpv4]/[parseIpv6] -> [ingestQuicDatagram]),
     * pruned and drained from the flush-loop coroutine ([flushNow]), and read from the UI thread
     * ([quicLiveStateSnapshot]) -- three different threads over plain `LinkedHashMap`s, which is a data
     * race, not merely a `ConcurrentModificationException` risk. Every access now takes this lock, and
     * no database call is made while holding it: the ingest path prepares its writes under the lock and
     * performs them outside it, so a slow disk can never block packet reading.
     */
    private val quicStateLock = Any()

    /**
     * P2 QUIC connection-ID routing table and datagram router. [quicDatagramRouter] splits a
     * coalesced UDP/443 datagram into its QUIC packets, dispatches each by Destination Connection
     * ID through [quicCidTable] (with a 5-tuple fallback for zero-length-DCID connections),
     * classifies each packet's type, and recovers the Initial SNI/ALPN. [parseIpv4]/[parseIpv6]
     * hand the QUIC payload to [ingestQuicDatagram] instead of parsing Initial packets inline, so
     * this pump only persists (and, via the separate key-log-sourced 1-RTT path, decrypts). A
     * single [quicCidTable] instance is shared with the NEW/RETIRE_CONNECTION_ID frame walker so
     * CIDs issued mid-connection route to the same connection.
     */
    private val quicCidTable = com.example.interception.quic.QuicCidTable()
    private val quicDatagramRouter = com.example.interception.quic.QuicDatagramRouter(quicCidTable)

    /**
     * The NEW/RETIRE_CONNECTION_ID + STREAM + CRYPTO frame walker, sharing [quicCidTable] so a CID
     * issued mid-connection routes to the same connection. One instance is shared across connections;
     * it keys its per-connection sequence->CID bookkeeping by routing key.
     */
    private val quicFrameWalker = com.example.interception.quic.QuicFrameWalker(quicCidTable)

    /**
     * P2 (gap closure): the per-connection QUIC/HTTP-3 decode state machines. Each
     * [com.example.interception.quic.QuicConnectionDecoder] owns that connection's packet-number
     * spaces, negotiated cipher suite, key-log identity, Handshake/0-RTT/1-RTT keys (including
     * [com.example.interception.quic.QuicCrypto.KeyPhaseManager] key-phase rotation), CRYPTO/STREAM
     * reassembly, and [com.example.interception.quic.Http3StreamParser] state. This pump feeds them
     * routed packets and persists what they genuinely decode -- it never decides on its own that
     * something was decrypted.
     *
     * Bounded like every other map here: entries are dropped in [flushNow] once their connection has
     * been idle past [FLOW_TTL_MS], and each decoder is itself internally bounded.
     */
    private val quicDecoders = LinkedHashMap<String, com.example.interception.quic.QuicConnectionDecoder>()

    /**
     * Monotonic decode-state generation per connection routing key. A 5-tuple can legitimately be reused
     * by a genuinely different QUIC connection later, and after an idle connection's decode state is
     * released the next packet starts a fresh decoder. Both cases must not inherit the previous
     * decoder's [http3RowIds] entries, or an update would be applied to an unrelated, older
     * `captured_traffic` row. Including the generation in the row key makes that impossible.
     */
    private val quicConnectionGenerations = mutableMapOf<String, Int>()

    /**
     * Room `captured_traffic.id` per decoded HTTP/3 exchange, keyed
     * `connectionId#generation#streamId`, so later DATA/`:status` arrivals on the same stream update
     * the row this pump already inserted instead of inserting a duplicate -- and so a *different*
     * decoder generation can never update a previous generation's row.
     */
    private val http3RowIds = LinkedHashMap<String, Long>()

    /**
     * HTTP/3 exchanges whose decoded content has changed and not yet been written, keyed by the same
     * row key as [http3RowIds].
     *
     * Persisting inside the packet path meant a Room insert/update -- plus a full body serialization --
     * for every decoded DATA frame, on the tun-read thread, via `runBlocking`. Writes are instead
     * batched here and flushed from [flushLoop], which already runs once a second: a burst of DATA
     * frames on one stream now costs one write carrying the latest state instead of one write per
     * packet, and the read loop never blocks on the database.
     */
    private val pendingHttp3Writes = LinkedHashMap<String, PendingHttp3Write>()

    /** Real count of HTTP/3 exchanges decoded and persisted, folded into the session's counters. */
    private var http3TransactionCount = 0

    /**
     * One queued HTTP/3 row write. Holds the live [exchange] plus the immutable per-connection context
     * a row needs, and the [com.example.interception.quic.QuicConnectionDecoder.Http3Exchange.revision]
     * last written, so the flush can skip an exchange whose decoded content has not moved since -- the
     * check that stops repeated body serialization for a stream that is merely being ACKed.
     */
    private class PendingHttp3Write(
        val rowKey: String,
        val connectionId: String,
        val exchange: com.example.interception.quic.QuicConnectionDecoder.Http3Exchange,
        val fallbackAuthority: String,
        var writtenRevision: Int = -1,
    )

    /**
     * Locks the negotiated TLS 1.3 AEAD cipher suite for [connectionId] from the IANA cipher-suite
     * code the server selected in its ServerHello (RFC 8446 §4.1.3 / RFC 9001 §5.3), so the 1-RTT
     * decrypt path can derive correctly-sized traffic keys and pick the right AEAD + header
     * protection. Returns the locked [com.example.interception.quic.QuicCrypto.CipherSuite], or null
     * for an unrecognized code (a suite QUIC cannot negotiate, e.g. an obsolete CBC suite) -- in
     * which case nothing is locked and the connection stays undecryptable. Covers all three AEAD
     * suites QUIC can negotiate: AES-128-GCM-SHA256 (0x1301), AES-256-GCM-SHA384 (0x1302), and
     * ChaCha20-Poly1305-SHA256 (0x1303).
     */
    fun lockCipherSuite(connectionId: String, ianaCode: Int): com.example.interception.quic.QuicCrypto.CipherSuite? {
        val suite = com.example.interception.quic.QuicCrypto.CipherSuite.fromIanaCode(ianaCode) ?: return null
        // The decoder is the single owner of a connection's negotiated suite -- there is no mirrored
        // copy here to drift out of sync with it. An external lock (a plane that negotiated the suite
        // itself, e.g. the MITM leg) therefore creates the decoder if this connection has not been seen
        // yet, with an unknown version that a later long header corrects.
        synchronized(quicStateLock) {
            decoderFor(connectionId, version = 0).lockCipherSuite(suite)
        }
        return suite
    }

    /** The locked negotiated cipher suite for [connectionId], or null if none has been locked yet. */
    fun lockedCipherSuite(connectionId: String): com.example.interception.quic.QuicCrypto.CipherSuite? =
        synchronized(quicStateLock) { quicDecoders[connectionId]?.negotiatedCipherSuite }

    /**
     * Task 14: read-only snapshot of the live QUIC decode state for the HTTP/3 UI surface. The
     * authoritative, restart-surviving decode status lives in
     * [com.example.data.local.QuicConnectionEntity.decodeStatus] (written by this pump from the
     * decoder); this snapshot adds the strictly-live detail Room has no column for: the transport
     * [com.example.interception.quic.QuicState], which traffic secrets resolved versus were looked up
     * and missed, the negotiated AEAD suite, and live counters.
     *
     * Every field is measured, never inferred. [decodedHttp3Exchanges] counts exchanges that exist
     * only because a QPACK field section really decoded; [keyMaterialMissing] is true only when
     * protected packets were genuinely observed with no secret available for them; and
     * [missingKeyLabels] names the exact labels that missed, so the UI can say *why* a connection is
     * undecrypted instead of implying it could have been decoded.
     *
     * Taken under [quicStateLock], so it is a consistent view rather than an iteration racing the
     * tun-read and flush coroutines.
     */
    data class QuicLiveSummary(
        val connectionId: String,
        val sniHost: String?,
        val alpn: String?,
        val quicVersion: Int?,
        val destIp: String,
        val destPort: Int,
        val state: com.example.interception.quic.QuicState,
        val decodedFrames: Boolean,
        val bytesReceived: Long,
        val decodeStatus: com.example.data.local.QuicDecodeStatus = com.example.data.local.QuicDecodeStatus.DETECTED_ONLY,
        val cipherSuite: String? = null,
        val resolvedKeyLabels: List<String> = emptyList(),
        val missingKeyLabels: List<String> = emptyList(),
        val keyMaterialMissing: Boolean = false,
        val cipherSuiteUnavailable: Boolean = false,
        val decodedHttp3Exchanges: Int = 0,
        val keyUpdatesPromoted: Int = 0,
        /** Retry packets whose integrity tag authenticated and which re-keyed the connection. */
        val retriesAccepted: Int = 0,
        /** Retry packets rejected because their integrity tag did not authenticate (spoofed/corrupt). */
        val retriesRejected: Int = 0,
    )

    fun quicLiveStateSnapshot(): List<QuicLiveSummary> = synchronized(quicStateLock) {
        quicConnectionState.values.map { conn ->
            val decoder = quicDecoders[conn.connectionId]
            QuicLiveSummary(
                connectionId = conn.connectionId,
                sniHost = conn.sniHost,
                alpn = conn.alpn,
                quicVersion = conn.quicVersion,
                destIp = conn.dstIp,
                destPort = conn.dstPort,
                state = conn.state,
                decodedFrames = (decoder?.exchanges?.any { it.hasRequestHeaders || it.hasResponseHeaders } == true),
                bytesReceived = conn.bytesReceived,
                decodeStatus = decoder?.decodeStatus ?: com.example.data.local.QuicDecodeStatus.DETECTED_ONLY,
                cipherSuite = decoder?.negotiatedCipherSuite?.name,
                resolvedKeyLabels = decoder?.resolvedKeyLabels?.map { it.name } ?: emptyList(),
                missingKeyLabels = decoder?.missingKeyLabels?.map { it.name } ?: emptyList(),
                keyMaterialMissing = decoder?.keyMaterialMissing == true,
                cipherSuiteUnavailable = decoder?.cipherSuiteUnavailable == true,
                decodedHttp3Exchanges = decoder?.exchanges?.count { it.hasRequestHeaders || it.hasResponseHeaders } ?: 0,
                keyUpdatesPromoted = decoder?.keyUpdatesPromoted ?: 0,
                retriesAccepted = decoder?.retriesAccepted ?: 0,
                retriesRejected = decoder?.retriesRejected ?: 0,
            )
        }
    }

    /** TCP reassembly + HTTP/TLS pipeline. Feeds decoded transactions into Room. */
    private val captureEngine = CaptureEngine(context, db, sessionId, printUndecoded, dumpToFile, captureEnabled, enhancedMode, requestLogging, responseLogging)

    /**
     * T1: PCAP export. [com.example.interception.packet.PacketCaptureEngine] is started/stopped
     * by [RequestrVpnService] (which owns the [AppSettingsKeys.PCAP_ENABLED] preference and the
     * application [android.content.Context] the file writer needs) -- this pump just feeds it
     * every raw IP packet actually read off the tun fd. The engine's own `active`/`output`
     * guards make this call a no-op when the preference is off, so no flag needs to be threaded
     * through here.
     */
    private fun writeToPcap(packet: ByteArray, length: Int) {
        if (!com.example.interception.packet.PacketCaptureEngine.isActive()) return
        val nowMs = System.currentTimeMillis()
        com.example.interception.packet.PacketCaptureEngine.writePacket(
            timestampSec = nowMs / 1000,
            timestampUsec = (nowMs % 1000) * 1000,
            data = packet,
            offset = 0,
            len = length,
        )
    }

    suspend fun run(scope: CoroutineScope) {
        if (nativeBridgeMode) {
            runNativeBridgeLoop(scope)
            return
        }

        val buffer = ByteBuffer.allocate(MAX_PACKET)

        // Periodic flush so the UI shows live progress without thrashing the DB per packet.
        val flusher = scope.launch { flushLoop() }
        // Relay's own selector loop (TCP connects/reads, UDP reads) — separate coroutine so a
        // slow upstream socket never blocks the tun read loop above.
        val relayJob = scope.launch { relay.run(scope) }

        try {
            withContext(Dispatchers.IO) {
                while (scope.isActive) {
                    buffer.clear()
                    val read = try {
                        input.read(buffer.array())
                    } catch (e: Exception) {
                        if (!scope.isActive) break
                        Log.w(TAG, "tun read error: ${e.message}")
                        break
                    }

                    if (read <= 0) {
                        // No data available; yield briefly rather than spinning hot.
                        delay(IDLE_BACKOFF_MS)
                        continue
                    }

                    packetCount++
                    byteCount += read

                    parseAndTrackFlow(buffer.array(), read)
                }
            }
        } finally {
            flusher.cancel()
            relayJob.cancel()
            relay.stop()
            icmpRelay.stop()
            flushNow()
        }
    }

    /**
     * Gap #1 Approach A: the branch [run] takes when [nativeBridgeMode] is true. The native
     * tun2proxy bridge owns reading/writing the raw tun fd on its own thread and decrypts VPN
     * traffic by issuing `CONNECT` to [MitmProxyEngine] directly -- this pump never touches
     * [input]/[output] here, and never starts [relay]/[icmpRelay] (nothing would ever offer
     * them a packet in this mode). Its only job is to keep [InterceptionStateRepository] and
     * the session row genuinely reflecting live traffic by polling [nativeTrafficStatsPoll]
     * on the same cadence [flushLoop] already uses, reusing the existing [flushNow] machinery
     * unchanged.
     *
     * Deliberate, documented trade-off: [packetCount] stays at its initial value in this mode
     * (tun2proxy's traffic-status callback reports byte counters only, not packet counts), and
     * no DNS/QUIC/PCAP/flow-level detail is recorded, since all of that requires reading raw IP
     * packets this pump never sees when the native bridge owns the fd. That is consistent with
     * this class's stated design rule of never inventing data it did not actually observe --
     * the task's requirement is to preserve capture/session *counters*, not to reproduce the
     * full DPI pipeline under native mode.
     */
    private suspend fun runNativeBridgeLoop(scope: CoroutineScope) {
        val flusher = scope.launch { flushLoop() }
        try {
            while (scope.isActive) {
                nativeTrafficStatsPoll?.invoke()?.let { (tx, rx) ->
                    byteCount = tx + rx
                }
                delay(FLUSH_INTERVAL_MS)
            }
        } finally {
            flusher.cancel()
            flushNow()
        }
    }

    /**
     * Minimal real IP header parse. We extract version, protocol and addresses to identify the
     * flow. This is genuine packet inspection, not a placeholder.
     */
    private fun parseAndTrackFlow(packet: ByteArray, length: Int) {
        if (length < 20) return

        val version = (packet[0].toInt() shr 4) and 0xF
        when (version) {
            4 -> parseIpv4(packet, length)
            6 -> parseIpv6(packet, length)
            else -> return
        }

        // T1: mirror every packet that reaches this point (i.e. every packet the pump actually
        // read off the tun fd) into the .pcap file, if the user turned that export on.
        writeToPcap(packet, length)

        // Route to the capture engine: TCP reassembly → HTTP → Room.
        captureEngine.ingest(packet, length, version)

        // Task 4: hand the same packet to the relay so the flow actually gets a real upstream
        // connection and a reply, instead of vanishing (the VPN_TUN blackhole). Capture/PCAP
        // behaviour above stays byte-identical; this is purely additive.
        relay.offer(packet.copyOf(length), length, version)
    }

    private fun parseIpv4(packet: ByteArray, length: Int) {
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (length < ihl + 4) return

        val protocol = packet[9].toInt() and 0xFF
        val src = "${packet[12].u()}.${packet[13].u()}.${packet[14].u()}.${packet[15].u()}"
        val dst = "${packet[16].u()}.${packet[17].u()}.${packet[18].u()}.${packet[19].u()}"

        // Ports only exist for TCP(6)/UDP(17).
        val (sPort, dPort) = if (protocol == 6 || protocol == 17) {
            val s = ((packet[ihl].u() shl 8) or packet[ihl + 1].u())
            val d = ((packet[ihl + 2].u() shl 8) or packet[ihl + 3].u())
            s to d
        } else 0 to 0

        // DNS interception on UDP port 53: parses queries/responses and forwards them via
        // dnsCallback (see RequestrVpnService.onDnsObserved) for real persistence.
        if (protocol == 17 && (sPort == 53 || dPort == 53)) {
            val udpPayload = packet.copyOfRange(ihl + 8, length)
            val query = com.example.interception.dns.DnsParser.parse(udpPayload, sPort, dPort)
            query?.let { dns ->
                Log.d(TAG, "DNS ${dns.qtypeName}: ${dns.hostname} (${if (dns.isResponse) "response" else "query"})")
                dnsCallback?.invoke(dns, src, sPort, dst, dPort)
            }
        }

        // Fix #26 / P2: route QUIC datagrams on UDP port 443 through the datagram router, which
        // splits coalesced packets, dispatches each by Destination Connection ID through the CID
        // table, classifies each packet's type, and recovers the Initial SNI/ALPN (via
        // [QuicParser.parseInitial] for Initial packets). This pump then only persists -- the
        // inline Initial-parsing that used to live here moved into the router so coalesced
        // Initial+Handshake datagrams (a server's common response shape) are handled end to end.
        if (protocol == 17 && (sPort == 443 || dPort == 443)) {
            val quicPayload = packet.copyOfRange(ihl + 8, length)
            ingestQuicDatagram(quicPayload, src, sPort, dst, dPort, isIpv6 = false)
        }

        // M1: general UDP flow tracking (byte counters), covering all UDP traffic including
        // DNS/QUIC above -- those get the specialized entities too, but every UDP 5-tuple gets
        // a udp_flows row so "Connections" surfaces the full picture.
        if (protocol == 17) {
            persistUdpFlow(src, sPort, dst, dPort, length - ihl - 8)
        }

        trackFlow(com.example.correlation.ConnectionKey.forTcp(src, sPort, dst, dPort, protocol))
    }

    /** M1: upsert a udp_flows row, throttled per 5-tuple so we don't hit Room every packet. */
    private fun persistUdpFlow(src: String, sPort: Int, dst: String, dPort: Int, payloadLen: Int) {
        val flowId = com.example.correlation.ConnectionKey.forTcp(src, sPort, dst, dPort, 17)
        val now = System.currentTimeMillis()
        val throttled = synchronized(quicStateLock) {
            val last = lastUdpPersist[flowId]
            if (last != null && now - last < UDP_PERSIST_INTERVAL_MS) {
                true
            } else {
                lastUdpPersist[flowId] = now
                false
            }
        }
        if (throttled) return
        val entity = UdpFlowEntity(
            flowId = flowId,
            sessionId = sessionId,
            srcIp = src, destIp = dst,
            srcPort = sPort, destPort = dPort,
            transportProtocol = if (sPort == 443 || dPort == 443) "quic" else if (sPort == 53 || dPort == 53) "dns" else "udp",
            startedAt = now,
            byteCount = payloadLen.toLong().coerceAtLeast(0),
        )
        runCatching {
            runBlocking { db.udpFlowDao().upsert(entity) }
        }.onFailure { e -> Log.w(TAG, "Failed to persist UDP flow: ${e.message}") }
    }

    /**
     * P2: ingests one UDP/443 datagram's QUIC payload through [quicDatagramRouter], which splits
     * coalesced packets, routes each by Destination Connection ID, classifies each packet's type,
     * and recovers the Initial SNI/ALPN, then persists each routed packet. This replaces the
     * inline `QuicParser.parseInitial` + `persistQuicConnection` blocks that used to live in
     * [parseIpv4]/[parseIpv6] (Initial-only, no coalescing, no CID routing): the router owns
     * parsing/routing/classification, this pump owns persist (and, via the separate key-log-sourced
     * 1-RTT path, decrypt). [isIpv6] is metadata only and does not change routing.
     */
    internal fun ingestQuicDatagram(
        datagramPayload: ByteArray,
        src: String,
        sPort: Int,
        dst: String,
        dPort: Int,
        isIpv6: Boolean = false,
    ) {
        val routed = quicDatagramRouter.ingest(datagramPayload, src, sPort, dst, dPort, isIpv6)
        for (packet in routed) {
            persistQuicConnection(src, sPort, dst, dPort, packet)
        }
    }

    /**
     * P2 (gap closure): the per-connection decode state machine for [connectionId], created on first
     * sight of the connection and seeded with any externally-locked cipher suite. The key-log lookup
     * is [com.example.interception.tls.TlsKeyLogRegistry]'s real one, resolved by the ClientHello
     * random the decoder recovers from the connection's own Initial packet -- so a registry entry only
     * unlocks the connection it actually belongs to.
     */
    private fun decoderFor(connectionId: String, version: Int): com.example.interception.quic.QuicConnectionDecoder {
        quicDecoders[connectionId]?.let { return it }
        val decoder = com.example.interception.quic.QuicConnectionDecoder(
            connectionId = connectionId,
            initialVersion = version,
            frameWalker = quicFrameWalker,
            cidTable = quicCidTable,
            secretLookup = { label, clientRandom ->
                com.example.interception.tls.TlsKeyLogRegistry.lookup(label, clientRandom)
            },
        )
        quicDecoders[connectionId] = decoder
        // A new decoder for a routing key that has been seen before starts a new generation, so none of
        // the previous decoder's HTTP/3 row keys can ever be reused (see [quicConnectionGenerations]).
        quicConnectionGenerations[connectionId] = (quicConnectionGenerations[connectionId] ?: 0) + 1
        return decoder
    }

    /** Current decode-state generation for [connectionId] (1 for its first decoder). */
    private fun generationOf(connectionId: String): Int = quicConnectionGenerations[connectionId] ?: 1

    /**
     * M1/P2: upsert a quic_connections row once per connection (identified by the routed
     * connectionId, a [com.example.correlation.ConnectionKey.forQuic] 5-tuple key resolved by
     * [quicDatagramRouter] via the CID table), and maintain the in-memory
     * [com.example.interception.quic.QuicConnection] state machine for this connectionId (P2) --
     * the persisted row is a connection-level summary; the live
     * [com.example.interception.quic.QuicConnection]/[com.example.interception.quic.QuicState]
     * transition is what makes [com.example.interception.quic.QuicConnection],
     * [com.example.interception.quic.QuicState] and [com.example.interception.quic.QuicStream]
     * (previously zero-call-site dead code) an actually-running part of the pipeline.
     *
     * Each [routed] packet advances the in-memory state by its packet type (an Initial whose
     * ClientHello was AEAD-decrypted, or a Handshake packet, marks the handshake in progress); the
     * Room row is written once, on first observation of the connection, carrying the Initial's
     * recovered SNI/ALPN/version. Subsequent packets (e.g. a coalesced Handshare after the Initial,
     * or the server's response routed back to the same connectionId by DCID) only advance state.
     */
    private fun persistQuicConnection(
        src: String,
        sPort: Int,
        dst: String,
        dPort: Int,
        routed: com.example.interception.quic.QuicDatagramRouter.RoutedPacket,
    ) {
        val connectionId = routed.connectionId
        val outbound = routed.direction == com.example.interception.quic.QuicDatagramRouter.Direction.OUTBOUND
        // The row's 5-tuple is stored in canonical client->server orientation, matching the routing key
        // the router builds, so a connection first observed inbound does not record the server as `src`.
        val clientIp = if (outbound) src else dst
        val clientPort = if (outbound) sPort else dPort
        val serverIp = if (outbound) dst else src
        val serverPort = if (outbound) dPort else sPort

        // Everything below prepares the writes under [quicStateLock]; the database calls happen after it
        // is released so a slow disk never blocks the tun-read loop.
        val write: QuicConnectionWrite
        synchronized(quicStateLock) {
            val conn = quicConnectionState.getOrPut(connectionId) {
                com.example.interception.quic.QuicConnection(
                    connectionId = connectionId,
                    sessionId = sessionId,
                    srcIp = clientIp, srcPort = clientPort,
                    dstIp = serverIp, dstPort = serverPort,
                    sniHost = routed.sniHostname,
                    alpn = routed.alpn,
                    quicVersion = routed.version,
                    dstConnectionId = connectionId,
                )
            }
            conn.bytesReceived += routed.payloadBytes.size

            // P2 (gap closure): run the real decode state machine for this connection -- key-log identity
            // lookup, ServerHello cipher lock, Handshake/0-RTT/1-RTT decryption with key-phase rotation,
            // CRYPTO/STREAM reassembly, and HTTP/3 parsing. The lifecycle state is the decoder's measured
            // one, not a packet-type guess: HANDSHAKE_COMPLETE means 1-RTT really decrypted, and CLOSED
            // means a CONNECTION_CLOSE frame was really read out of a decrypted payload.
            val decoder = decoderFor(connectionId, routed.version)
            val decode = decoder.ingest(routed)
            conn.state = decoder.lifecycle

            // Register the streams the decoder actually reassembled so
            // [com.example.interception.quic.QuicStream] reflects real decoded state, keyed by the full
            // 62-bit stream ID rather than a narrowed one.
            for (exchange in decoder.exchanges) {
                val stream = conn.streams.getOrPut(exchange.streamId) {
                    com.example.interception.quic.QuicStream(streamId = exchange.streamId, connectionId = connectionId)
                }
                if (exchange.hasRequestHeaders && stream.decodedHeaders == null) {
                    stream.decodedHeaders = exchange.requestHeaders
                }
                if (exchange.requestComplete && exchange.responseComplete) {
                    stream.state = com.example.interception.quic.StreamState.CLOSED
                }
            }

            val firstObservation = quicConnections.add(connectionId)
            val now = System.currentTimeMillis()
            write = QuicConnectionWrite(
                insertIdentity = if (firstObservation) {
                    QuicConnectionEntity(
                        connectionId = connectionId,
                        sessionId = sessionId,
                        srcIp = clientIp, destIp = serverIp,
                        srcPort = clientPort, destPort = serverPort,
                        sniHost = routed.sniHostname,
                        alpn = routed.alpn,
                        quicVersion = decoder.version,
                        dstConnectionId = connectionId,
                        establishedAt = now,
                        decodeStatus = decoder.decodeStatus,
                        lifecycleState = decoder.lifecycle.name,
                        updatedAt = now,
                        clientRandomHex = decoder.clientRandomHex,
                        keylogLabels = decoder.resolvedKeyLabelsCsv(),
                    )
                } else {
                    null
                },
                // The monotonic update runs on first observation too: the identity insert is
                // IGNORE-on-conflict, so when a row already exists (an idle connection whose decode state
                // was released, then resumed) this is what carries the state forward without wiping it.
                updateState = firstObservation || decode.statusChanged || decode.lifecycleChanged || decode.provenanceChanged,
                decodeStatus = decoder.decodeStatus,
                decodeStatusRank = decoder.decodeStatusRank,
                lifecycleState = decoder.lifecycle.name,
                lifecycleRank = decoder.lifecycleStateRank,
                clientRandomHex = decoder.clientRandomHex,
                keylogLabels = decoder.resolvedKeyLabelsCsv(),
                sniHost = routed.sniHostname,
                alpn = routed.alpn,
                quicVersion = decoder.version,
                updatedAt = now,
            )

            // Queue the exchanges that gained real decoded content; the write happens in [flushNow].
            for (exchange in decode.updatedExchanges) {
                enqueueHttp3Write(connectionId, conn, exchange)
            }
        }

        if (routed.packetType == com.example.interception.quic.QuicCrypto.LongHeaderPacketType.INITIAL && routed.sniHostname != null) {
            Log.i(
                TAG,
                "QUIC ${if (routed.decrypted) "decrypted" else "heuristic"} SNI: " +
                    "${routed.sniHostname} alpn=${routed.alpn} " +
                    "(${if (outbound) "outbound" else "inbound"})",
            )
        }

        applyQuicConnectionWrite(connectionId, write)
    }

    /**
     * The prepared Room writes for one routed packet. Built while holding [quicStateLock] and applied
     * after releasing it, so the decode state and the database never contend on the same critical
     * section.
     */
    private class QuicConnectionWrite(
        val insertIdentity: QuicConnectionEntity?,
        val updateState: Boolean,
        val decodeStatus: QuicDecodeStatus,
        val decodeStatusRank: Int,
        val lifecycleState: String,
        val lifecycleRank: Int,
        val clientRandomHex: String?,
        val keylogLabels: String?,
        val sniHost: String?,
        val alpn: String?,
        val quicVersion: Int?,
        val updatedAt: Long,
    )

    /**
     * Applies a prepared connection write: an IGNORE-on-conflict identity insert (never a REPLACE, which
     * would discard the SNI/ALPN/identity columns a resumed connection can no longer re-derive) followed
     * by the monotonic state update that can only move `decodeStatus`/`lifecycleState` forward and
     * fill-forward provenance.
     */
    private fun applyQuicConnectionWrite(connectionId: String, write: QuicConnectionWrite) {
        write.insertIdentity?.let { entity ->
            runCatching {
                runBlocking {
                    // -1 means the row already exists. It may belong to a *previous* capture session,
                    // because a 5-tuple key is reused across sessions; in that case claim it for this
                    // session rather than leaving a stale row owned by a finished one. Within one session
                    // the claim is a no-op and the monotonic update below stays the only writer.
                    if (db.quicConnectionDao().insertIfAbsent(entity) == -1L) {
                        db.quicConnectionDao().claimForSession(
                            connectionId = entity.connectionId,
                            sessionId = entity.sessionId,
                            srcIp = entity.srcIp,
                            destIp = entity.destIp,
                            srcPort = entity.srcPort,
                            destPort = entity.destPort,
                            sniHost = entity.sniHost,
                            alpn = entity.alpn,
                            quicVersion = entity.quicVersion,
                            establishedAt = entity.establishedAt,
                            decodeStatus = entity.decodeStatus,
                            lifecycleState = entity.lifecycleState,
                            clientRandomHex = entity.clientRandomHex,
                            keylogLabels = entity.keylogLabels,
                            updatedAt = entity.updatedAt,
                        )
                    }
                }
            }.onFailure { e -> Log.w(TAG, "Failed to persist QUIC connection: ${e.message}") }
        }
        if (!write.updateState) return
        runCatching {
            runBlocking {
                db.quicConnectionDao().updateDecodeState(
                    connectionId = connectionId,
                    decodeStatus = write.decodeStatus,
                    decodeStatusRank = write.decodeStatusRank,
                    lifecycleState = write.lifecycleState,
                    lifecycleRank = write.lifecycleRank,
                    clientRandomHex = write.clientRandomHex,
                    keylogLabels = write.keylogLabels,
                    sniHost = write.sniHost,
                    alpn = write.alpn,
                    quicVersion = write.quicVersion,
                    updatedAt = write.updatedAt,
                )
            }
        }.onFailure { e -> Log.w(TAG, "Failed to update QUIC decode state: ${e.message}") }
    }

    /**
     * Queues one genuinely decoded HTTP/3 exchange for persistence. Must be called while holding
     * [quicStateLock].
     *
     * Nothing is queued without a decoded field section, so an undecryptable connection contributes no
     * traffic row at all rather than a plausible-looking placeholder. The queue is keyed by
     * connection + decode generation + stream, so repeated updates to one exchange collapse into a
     * single pending write instead of accumulating.
     */
    private fun enqueueHttp3Write(
        connectionId: String,
        conn: com.example.interception.quic.QuicConnection,
        exchange: com.example.interception.quic.QuicConnectionDecoder.Http3Exchange,
    ) {
        if (!exchange.hasRequestHeaders && !exchange.hasResponseHeaders) return
        val rowKey = "$connectionId#${generationOf(connectionId)}#${exchange.streamId}"
        val existing = pendingHttp3Writes[rowKey]
        // Already queued for this exact exchange instance: the flush reads its latest state, so there is
        // nothing to re-queue. A *different* instance for the same stream means the decoder's in-flight
        // bound evicted the old exchange and recreated it; the queue must then follow the live object,
        // otherwise everything the recreated exchange decodes would never be written.
        if (existing != null && existing.exchange === exchange) return
        pendingHttp3Writes[rowKey] = PendingHttp3Write(
            rowKey = rowKey,
            connectionId = connectionId,
            exchange = exchange,
            fallbackAuthority = conn.sniHost ?: conn.dstIp,
        )
    }

    /**
     * Writes every queued HTTP/3 exchange, inserting a row the first time an exchange carries decoded
     * HEADERS and updating that same row afterwards. Called from [flushNow], never from the packet path.
     *
     * Honesty rules this enforces:
     *  - **Nothing is written without a decoded field section** (see [enqueueHttp3Write]).
     *  - `wasDecrypted = true` / `isUndecoded = false` are literally true: the bytes came out of an
     *    AEAD-authenticated payload and a QPACK field section, not from an SNI guess.
     *  - `statusCode` stays null until a `:status` pseudo-header really decodes, and request metadata is
     *    filled in later if the response decoded first, rather than staying permanently blank.
     *  - The row's `connectionId` is the QUIC connection's routing key, so
     *    [com.example.correlation.graph.ConnectionLineage] joins it exactly like a TCP transaction.
     *
     * An exchange whose revision has not moved since its last write is skipped, so a stream that is only
     * being ACKed costs neither a body serialization nor a database round trip.
     */
    /**
     * Item 4 / gap B: what [flushHttp3Writes] needs to persist for one direction (request or
     * response) of an HTTP/3 exchange -- computed once per flush so the two call sites (the
     * `UPDATE` path and the fresh-insert path below) can't drift from each other.
     *
     * @property text the text column value: real UTF-8 text for a text body, or
     *   [HttpBodyDecoder.BINARY_BODY_PLACEHOLDER] for a binary one -- never the old
     *   `"<binary N bytes>"` marker string, which was neither the real content nor a clear
     *   "this is binary" signal the UI could act on structurally.
     * @property isBinary mirrors [com.example.data.local.CapturedTrafficEntity.isRequestBodyBinary]/
     *   `.isResponseBodyBinary`.
     * @property bytes the BLOB to persist, or null when [isBinary] is false, or when [truncated]
     *   is true -- same "store nothing beyond the cap, not a partial prefix" contract
     *   [HttpBodyDecoder.clampForStorage] documents.
     * @property truncated write straight to `requestBodyBytesTruncated`/`responseBodyBytesTruncated`.
     */
    internal data class Http3BodyForPersist(
        val text: String,
        val isBinary: Boolean,
        val bytes: ByteArray?,
        val truncated: Boolean,
    )

    /**
     * Classifies [bytes] (already buffered by [com.example.interception.quic.QuicConnectionDecoder.Http3Exchange],
     * up to its own in-flight bound) via the same [HttpBodyDecoder.isProbablyUtf8]/
     * [HttpBodyDecoder.MAX_STORED_BODY_BYTES] rules every other capture path
     * (MitmProxyEngine/CaptureEngine/GatewayEngine) uses, instead of the old ad-hoc
     * "does a UTF-8 round trip survive" check that only ever produced a text marker string and
     * never stored real bytes.
     *
     * [decoderTruncated] is [com.example.interception.quic.QuicConnectionDecoder.Http3Exchange.requestBodyTruncated]/
     * `.responseBodyTruncated` -- true when the decoder's own per-exchange buffering bound was
     * hit, meaning [bytes] is already only a *prefix* of the real body, not the whole thing. A
     * binary body in that state always degrades to "not stored" (bytes = null, truncated = true)
     * regardless of whether the prefix itself is under [HttpBodyDecoder.MAX_STORED_BODY_BYTES] --
     * storing a partial JPEG/protobuf/gzip blob as if it were complete would misrepresent it as
     * a whole, decodable payload, exactly what [HttpBodyDecoder.MAX_STORED_BODY_BYTES]'s own
     * "never a truncated prefix" rule exists to avoid.
     */
    internal fun bodyForPersist(bytes: ByteArray, decoderTruncated: Boolean): Http3BodyForPersist {
        val storage = HttpBodyDecoder.forStorage(bytes)
        if (!storage.isBinary) {
            return Http3BodyForPersist(text = String(bytes, Charsets.UTF_8), isBinary = false, bytes = null, truncated = false)
        }
        return Http3BodyForPersist(
            text = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBinary = true,
            bytes = if (decoderTruncated) null else storage.bytes,
            truncated = decoderTruncated || storage.truncated,
        )
    }

    private suspend fun flushHttp3Writes() {
        val queued = synchronized(quicStateLock) {
            val snapshot = pendingHttp3Writes.values.toList()
            pendingHttp3Writes.clear()
            snapshot
        }
        if (queued.isEmpty()) return
        var inserted = 0
        for (write in queued) {
            val exchange = write.exchange
            val revision = exchange.revision
            if (revision == write.writtenRevision) continue
            val rowId = synchronized(quicStateLock) { http3RowIds[write.rowKey] }
            val requestBodyInfo = exchange.requestBody().takeIf { it.isNotEmpty() }
                ?.let { bodyForPersist(it, exchange.requestBodyTruncated) }
            val responseBodyInfo = exchange.responseBody().takeIf { it.isNotEmpty() }
                ?.let { bodyForPersist(it, exchange.responseBodyTruncated) }
            val requestBody = requestBodyInfo?.text
            val responseBody = responseBodyInfo?.text
            val authority = exchange.authority ?: write.fallbackAuthority
            val host = exchange.authority?.substringBefore(':') ?: write.fallbackAuthority
            val path = exchange.path ?: ""
            val scheme = exchange.scheme ?: "https"
            val method = exchange.method ?: ""

            if (rowId != null) {
                runCatching {
                    db.capturedTrafficDao().updateDecodedExchange(
                        id = rowId,
                        method = method,
                        url = "$scheme://$authority$path",
                        host = host,
                        path = path,
                        hasRequestHeaders = exchange.hasRequestHeaders,
                        requestHeaders = exchange.requestHeaders,
                        statusCode = exchange.statusCode,
                        hasResponseHeaders = exchange.hasResponseHeaders,
                        responseHeaders = exchange.responseHeaders,
                        requestBody = requestBody,
                        responseBody = responseBody,
                        requestSizeBytes = exchange.requestBodyBytes,
                        responseSizeBytes = exchange.responseBodyBytes,
                        latencyMs = exchange.latencyMs,
                        isRequestBodyBinary = requestBodyInfo?.isBinary ?: false,
                        isResponseBodyBinary = responseBodyInfo?.isBinary ?: false,
                        requestBodyBytes = requestBodyInfo?.bytes,
                        responseBodyBytes = responseBodyInfo?.bytes,
                        requestBodyBytesTruncated = requestBodyInfo?.truncated ?: false,
                        responseBodyBytesTruncated = responseBodyInfo?.truncated ?: false,
                    )
                }.onSuccess { write.writtenRevision = revision }
                    .onFailure { e -> Log.w(TAG, "Failed to update HTTP/3 exchange: ${e.message}") }
                continue
            }

            val entity = com.example.data.local.CapturedTrafficEntity(
                sessionId = sessionId,
                method = method,
                url = "$scheme://$authority$path",
                host = host,
                path = path,
                statusCode = exchange.statusCode,
                protocol = HTTP3_PROTOCOL,
                requestHeaders = exchange.requestHeaders,
                responseHeaders = exchange.responseHeaders,
                requestBody = requestBody,
                responseBody = responseBody,
                requestSizeBytes = exchange.requestBodyBytes,
                responseSizeBytes = exchange.responseBodyBytes,
                isRequestBodyBinary = requestBodyInfo?.isBinary ?: false,
                isResponseBodyBinary = responseBodyInfo?.isBinary ?: false,
                requestBodyBytes = requestBodyInfo?.bytes,
                responseBodyBytes = responseBodyInfo?.bytes,
                requestBodyBytesTruncated = requestBodyInfo?.truncated ?: false,
                responseBodyBytesTruncated = responseBodyInfo?.truncated ?: false,
                latencyMs = exchange.latencyMs,
                timestamp = System.currentTimeMillis(),
                wasDecrypted = true,
                isUndecoded = false,
                connectionId = write.connectionId,
                // Item 1: PacketPump is only ever instantiated by RequestrVpnService, so this
                // HTTP/3 row -- like every row this class's tun-driven pipeline produces -- is
                // genuinely VPN_TUN-tier.
                captureMethod = com.example.data.local.CaptureRowMethod.VPN_TUN,
            )
            runCatching { db.capturedTrafficDao().insert(entity) }
                .onSuccess { newId ->
                    write.writtenRevision = revision
                    inserted++
                    synchronized(quicStateLock) { http3RowIds[write.rowKey] = newId }
                }
                .onFailure { e -> Log.w(TAG, "Failed to persist HTTP/3 exchange: ${e.message}") }
        }
        if (inserted > 0) {
            synchronized(quicStateLock) {
                http3TransactionCount += inserted
                trimHttp3RowIds()
            }
        }
        // Exchanges that are still live must stay queued so later DATA/:status arrivals are written; the
        // revision check above makes a re-queued, unchanged exchange free.
        synchronized(quicStateLock) {
            for (write in queued) {
                if (write.exchange.revision != write.writtenRevision) pendingHttp3Writes[write.rowKey] = write
            }
        }
    }

    /**
     * Bounds [http3RowIds] without breaking a live exchange's update path: entries whose connection no
     * longer has a decoder are dropped first (those exchanges can never change again), and only if that
     * is not enough are the oldest remaining entries dropped. Must be called while holding
     * [quicStateLock].
     */
    private fun trimHttp3RowIds() {
        if (http3RowIds.size <= MAX_TRACKED_HTTP3_ROWS) return
        val live = quicDecoders.keys
        val dead = http3RowIds.keys.filter { key -> live.none { key.startsWith("$it#") } }
        for (key in dead) {
            http3RowIds.remove(key)
            if (http3RowIds.size <= MAX_TRACKED_HTTP3_ROWS) return
        }
        val excess = http3RowIds.size - MAX_TRACKED_HTTP3_ROWS
        http3RowIds.keys.take(excess).toList().forEach { http3RowIds.remove(it) }
    }

    /**
     * Task 9: brought to parity with [parseIpv4] -- DNS-on-53, QUIC-on-443 and [persistUdpFlow]
     * all run for v6 the same way they do for v4 now. TCP capture needed no change here:
     * [parseAndTrackFlow] already calls `captureEngine.ingest(packet, length, version)` for
     * both families and [com.example.interception.tcp.TcpReassembler.feed] already handles v6
     * TCP via its own `feedIpv6`.
     *
     * The previous version read `nextHeader` at byte 6 and assumed the transport header always
     * started at the fixed offset 40, which breaks the moment any extension header (hop-by-hop
     * 0, routing 43, fragment 44, destination-options 60) is present. [walkIpv6ExtensionHeaders]
     * (task 3) walks the real chain to find the true transport offset and protocol, exactly like
     * [relay]'s own v6 header parsing already does.
     *
     * Addresses go through [IpAddressCodec.formatIpv6] rather than the ad-hoc
     * `joinToString`/`"%02x".format` construction this replaces -- same bare 32-hex-char string
     * output (so existing flow keys and any already-persisted `connectionId` values stay
     * byte-for-byte stable), just centralized in one place instead of duplicated per call site.
     */
    private fun parseIpv6(packet: ByteArray, length: Int) {
        if (length < 40) return
        val walk = ipv6HeaderWalker.walkIpv6ExtensionHeaders(packet, length) ?: return
        val (transportOffset, protocol) = walk
        val src = IpAddressCodec.formatIpv6(packet, 8)
        val dst = IpAddressCodec.formatIpv6(packet, 24)

        // Ports only exist for TCP(6)/UDP(17).
        val (sPort, dPort) = if ((protocol == 6 || protocol == 17) && length >= transportOffset + 4) {
            val s = ((packet[transportOffset].u() shl 8) or packet[transportOffset + 1].u())
            val d = ((packet[transportOffset + 2].u() shl 8) or packet[transportOffset + 3].u())
            s to d
        } else 0 to 0

        // Fix #16 parity: intercept DNS queries on UDP port 53.
        if (protocol == 17 && (sPort == 53 || dPort == 53) && length >= transportOffset + 8) {
            val udpPayload = packet.copyOfRange(transportOffset + 8, length)
            val query = com.example.interception.dns.DnsParser.parse(udpPayload, sPort, dPort)
            query?.let { dns ->
                Log.d(TAG, "DNS ${dns.qtypeName}: ${dns.hostname} (${if (dns.isResponse) "response" else "query"})")
                dnsCallback?.invoke(dns, src, sPort, dst, dPort)
            }
        }

        // Fix #26 / P2 parity: route QUIC datagrams on UDP port 443 through the same datagram
        // router as the IPv4 path (address family does not change DCID-based routing), so a v6
        // coalesced Initial+Handshake datagram is handled identically to a v4 one.
        if (protocol == 17 && (sPort == 443 || dPort == 443) && length >= transportOffset + 8) {
            val quicPayload = packet.copyOfRange(transportOffset + 8, length)
            ingestQuicDatagram(quicPayload, src, sPort, dst, dPort, isIpv6 = true)
        }

        // M1 parity: general UDP flow tracking (byte counters), covering all UDP traffic
        // including DNS/QUIC above.
        if (protocol == 17 && length >= transportOffset + 8) {
            persistUdpFlow(src, sPort, dst, dPort, length - transportOffset - 8)
        }

        trackFlow(com.example.correlation.ConnectionKey.forTcp(src, sPort, dst, dPort, protocol))
    }

    private fun trackFlow(key: String) {
        val now = System.currentTimeMillis()
        synchronized(quicStateLock) {
            activeFlows[key] = now
            if (activeFlows.size > MAX_TRACKED_FLOWS) {
                val cutoff = now - FLOW_TTL_MS
                activeFlows.entries.removeAll { it.value < cutoff }
            }
        }
    }

    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_INTERVAL_MS)
            flushNow()
        }
    }

    private suspend fun flushNow() {
        val now = System.currentTimeMillis()
        val cutoff = now - FLOW_TTL_MS

        // Queued HTTP/3 rows are written here rather than on the packet path (see [pendingHttp3Writes]).
        flushHttp3Writes()

        captureEngine.prune()

        val connections = synchronized(quicStateLock) {
            activeFlows.entries.removeAll { it.value < cutoff }
            // M1: bound the UDP/QUIC bookkeeping maps -- drop entries for flows no longer active so
            // long-running sessions don't leak memory keyed by every 5-tuple ever seen. Eviction is
            // always per-connection and idle-driven: the previous "clear the whole map when it grows
            // past the cap" behaviour discarded live connections' decode state (and their identity
            // bookkeeping) wholesale, which is what let a still-active connection be re-announced as a
            // brand new one.
            lastUdpPersist.entries.removeAll { it.value < cutoff }
            pruneQuicDecoders(cutoff)
            activeFlows.size
        }

        InterceptionStateRepository.updateCounters(
            packetCount = packetCount,
            bytesTransferred = byteCount,
            activeConnections = connections,
        )

        // Real transaction count: HTTP/1.x transactions the capture engine decoded plus HTTP/3
        // exchanges this pump decoded and persisted. Both are genuinely decoded rows, never estimates.
        val transactions = captureEngine.transactionCount + synchronized(quicStateLock) { http3TransactionCount }

        runCatching {
            db.captureSessionDao().updateCounters(
                id = sessionId,
                packets = packetCount,
                bytes = byteCount,
                transactions = transactions,
            )
        }
    }

    /**
     * Bounded lifecycle cleanup for the QUIC decode state: releases every decoder whose connection has
     * been idle past the flow TTL (and, if the map is still over-full, the oldest entries regardless),
     * dropping its per-stream reassemblers, HTTP/3 parser state, crypto buffers and derived key
     * material. Without this, a long capture would retain reassembly buffers and traffic keys for every
     * QUIC connection it ever saw. Closed connections are released as soon as they go idle; the Room
     * rows they produced are untouched.
     */
    private fun pruneQuicDecoders(cutoff: Long) {
        val expired = quicDecoders.entries.filter { it.value.lastActivityAt < cutoff }.map { it.key }
        for (key in expired) releaseQuicConnectionState(key)
        // Still over the cap: evict the least recently active connection, not an arbitrary one, and never
        // the whole map.
        while (quicDecoders.size > MAX_TRACKED_FLOWS) {
            val leastRecent = quicDecoders.entries.minByOrNull { it.value.lastActivityAt }?.key ?: break
            releaseQuicConnectionState(leastRecent)
        }
        trimHttp3RowIds()
    }

    /**
     * Releases every connection-scoped in-memory index together, leaving persisted Room rows intact.
     * Single place so a new index can never be forgotten by one eviction path and retained by another.
     *
     * Deliberately does **not** drop [quicConnectionGenerations]: the generation counter is what stops a
     * later decoder for the same routing key from inheriting this one's HTTP/3 row keys, so it must
     * outlive the decoder it identifies. Its own growth is bounded by the same eviction that bounds
     * [quicDecoders], since only routing keys that were once live can appear in it.
     *
     * Must be called while holding [quicStateLock].
     */
    private fun releaseQuicConnectionState(connectionId: String) {
        quicDecoders.remove(connectionId)?.release()
        quicConnectionState.remove(connectionId)
        quicConnections.remove(connectionId)
        quicFrameWalker.removeConnection(connectionId)
        quicCidTable.removeConnection(connectionId)
        pendingHttp3Writes.keys.removeAll { it.startsWith("$connectionId#") }
        http3RowIds.keys.removeAll { it.startsWith("$connectionId#") }
        if (quicConnectionGenerations.size > MAX_TRACKED_FLOWS * 2) {
            // Bound the generation ledger itself: a key with no live decoder can only matter again if
            // traffic resumes, and that resumption gets a fresh generation anyway.
            quicConnectionGenerations.keys.removeAll { it !in quicDecoders }
        }
    }

    /** Test/inspection hook: the number of live per-connection QUIC decoders currently retained. */
    internal fun quicDecoderCount(): Int = synchronized(quicStateLock) { quicDecoders.size }

    /** Test/inspection hook: the number of live QUIC summary objects currently retained. */
    internal fun quicConnectionStateCount(): Int = synchronized(quicStateLock) { quicConnectionState.size }

    /** Test/inspection hook: whether the decoder still holds a negotiated cipher for this connection. */
    internal fun hasQuicCipherSuite(connectionId: String): Boolean =
        synchronized(quicStateLock) { quicDecoders[connectionId]?.negotiatedCipherSuite != null }

    /** Test/inspection hook: forces the bounded QUIC decode-state cleanup with an explicit cutoff. */
    internal fun pruneQuicDecodersForTest(cutoff: Long) {
        runBlocking { flushHttp3Writes() }
        synchronized(quicStateLock) { pruneQuicDecoders(cutoff) }
    }

    /**
     * Test/inspection hook: writes every queued HTTP/3 row now. Production code batches these into
     * [flushNow]; a test that asserts on Room immediately after feeding packets calls this instead of
     * sleeping for a flush interval.
     */
    internal fun flushHttp3WritesForTest() = runBlocking { flushHttp3Writes() }

    /** Test/inspection hook: the number of HTTP/3 row writes still queued. */
    internal fun pendingHttp3WriteCount(): Int = synchronized(quicStateLock) { pendingHttp3Writes.size }

    private fun Byte.u(): Int = this.toInt() and 0xFF

    /**
     * `internal` rather than `private` so the module's tests can assert against the exact constants this
     * pump writes (notably [HTTP3_PROTOCOL] on a persisted HTTP/3 row) instead of duplicating the string
     * literal and silently drifting from it.
     */
    internal companion object {
        const val TAG = "PacketPump"
        const val MAX_PACKET = 32767
        const val IDLE_BACKOFF_MS = 10L
        const val FLUSH_INTERVAL_MS = 1000L
        const val FLOW_TTL_MS = 30_000L
        const val MAX_TRACKED_FLOWS = 512
        const val UDP_PERSIST_INTERVAL_MS = 2_000L

        /** `CapturedTrafficEntity.protocol` value for a decoded HTTP/3 exchange. */
        const val HTTP3_PROTOCOL = "HTTP/3"

        /** Bound on the connection#stream -> Room row id map, independent of decoder retention. */
        const val MAX_TRACKED_HTTP3_ROWS = 2_048
    }
}
