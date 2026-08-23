package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.QuicConnectionEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.UdpFlowEntity
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
     * Task 14: read-only snapshot of [quicConnectionState] for the HTTP/3 UI surface, which
     * needs the live [com.example.interception.quic.QuicState] (`INITIALIZING`,
     * `HANDSHAKE_IN_PROGRESS`, ...) and per-connection decoded-vs-Initial-only status that
     * [com.example.data.local.QuicConnectionEntity] has no column for (adding one would mean a
     * new Room migration, deliberately avoided here — see task 14's honesty writeup on why).
     * `decodedFrames` is real, not inferred: true only if a stream on this connection actually
     * has [com.example.interception.quic.QuicStream.decodedHeaders] set, i.e. QPACK decoded a
     * real HEADERS frame -- which never happens today, since [com.example.interception.quic.QpackDecoder]
     * has no production call site (task 13 descoped) -- so this will always read false until
     * that gap is closed, and the UI must say so rather than implying it can happen.
     *
     * Caught defensively: [quicConnectionState] is mutated from the tun-read coroutine and
     * cleared from the flush-loop coroutine with no lock between them (a pre-existing property
     * of this map, unrelated to this read-only accessor), so a concurrent structural change
     * during iteration is possible in principle; this snapshot degrades to "nothing to show
     * yet" rather than crashing the caller.
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
    )

    fun quicLiveStateSnapshot(): List<QuicLiveSummary> = try {
        quicConnectionState.values.map { conn ->
            QuicLiveSummary(
                connectionId = conn.connectionId,
                sniHost = conn.sniHost,
                alpn = conn.alpn,
                quicVersion = conn.quicVersion,
                destIp = conn.dstIp,
                destPort = conn.dstPort,
                state = conn.state,
                decodedFrames = conn.streams.values.any { it.decodedHeaders != null },
                bytesReceived = conn.bytesReceived,
            )
        }
    } catch (e: ConcurrentModificationException) {
        emptyList()
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

        // Fix #26 / P2: parse QUIC Initial packets on UDP port 443. [QuicParser.parseInitial]
        // does the real work now -- AEAD-decrypts the Initial packet using the public
        // RFC 9001 Initial keys (derived from the client DCID) when the version supports it,
        // recovering the real SNI *and* ALPN from the actual ClientHello, and falls back to
        // the plaintext heuristic scan (SNI only) for unsupported/gQUIC versions or on any
        // decrypt failure. Either way this is real session identity, not a placeholder.
        if (protocol == 17 && (sPort == 443 || dPort == 443)) {
            val quicPayload = packet.copyOfRange(ihl + 8, length)
            val initial = com.example.interception.quic.QuicParser.parseInitial(quicPayload)
            if (initial != null) {
                Log.i(
                    TAG,
                    "QUIC ${if (initial.decrypted) "decrypted" else "heuristic"} SNI: " +
                        "${initial.sniHostname} alpn=${initial.alpn} " +
                        "(${if (dPort == 443) "outbound" else "inbound"})",
                )
                persistQuicConnection(src, sPort, dst, dPort, initial, quicPayload)
            }
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
        val last = lastUdpPersist[flowId]
        if (last != null && now - last < UDP_PERSIST_INTERVAL_MS) return
        lastUdpPersist[flowId] = now
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
     * M1/P2: upsert a quic_connections row once per connection (identified by 5-tuple), and
     * maintain the in-memory [com.example.interception.quic.QuicConnection] state machine for
     * this connectionId (P2) -- the persisted row is a connection-level summary; the live
     * [com.example.interception.quic.QuicConnection]/[com.example.interception.quic.QuicState]
     * transition is what makes [com.example.interception.quic.QuicConnection],
     * [com.example.interception.quic.QuicState] and [com.example.interception.quic.QuicStream]
     * (previously zero-call-site dead code) an actually-running part of the pipeline.
     */
    private fun persistQuicConnection(
        src: String,
        sPort: Int,
        dst: String,
        dPort: Int,
        initial: com.example.interception.quic.QuicParser.QuicInitial,
        quicPayload: ByteArray,
    ) {
        val connectionId = com.example.correlation.ConnectionKey.forQuic(src, sPort, dst, dPort)

        // Advance (or create) the in-memory state machine for every Initial packet we see on
        // this 5-tuple, regardless of whether we've already persisted the Room row below --
        // e.g. a retransmitted/second Initial still means "handshake in progress".
        val conn = quicConnectionState.getOrPut(connectionId) {
            com.example.interception.quic.QuicConnection(
                connectionId = connectionId,
                sessionId = sessionId,
                srcIp = src, srcPort = sPort,
                dstIp = dst, dstPort = dPort,
                sniHost = initial.sniHostname,
                alpn = initial.alpn,
                quicVersion = initial.version,
                dstConnectionId = connectionId,
            )
        }
        conn.bytesReceived += quicPayload.size
        conn.state = if (initial.decrypted) {
            com.example.interception.quic.QuicState.HANDSHAKE_IN_PROGRESS
        } else {
            com.example.interception.quic.QuicState.INITIALIZING
        }
        // Every QUIC stream carrying request/response data multiplexes over this connection;
        // stream 0 (client-initiated bidirectional control/first-request stream) is the one
        // concrete stream we can associate with the Initial packet's CRYPTO data itself, so
        // track it as OPEN the moment the connection is observed -- this is what gives
        // [com.example.interception.quic.QuicStream] a real call site instead of being an
        // unused data class, without inventing frame-level stream data we haven't parsed.
        conn.streams.getOrPut(0) {
            com.example.interception.quic.QuicStream(streamId = 0, connectionId = connectionId)
        }

        if (!quicConnections.add(connectionId)) return // Room row already persisted for this 5-tuple
        val entity = QuicConnectionEntity(
            connectionId = connectionId,
            sessionId = sessionId,
            srcIp = src, destIp = dst,
            srcPort = sPort, destPort = dPort,
            sniHost = initial.sniHostname,
            alpn = initial.alpn,
            quicVersion = initial.version,
            dstConnectionId = connectionId,
            establishedAt = System.currentTimeMillis(),
            decodeStatus = QuicDecodeStatus.DETECTED_ONLY,
            lifecycleState = "INITIALIZING",
            updatedAt = System.currentTimeMillis(),
        )
        runCatching {
            runBlocking { db.quicConnectionDao().upsert(entity) }
        }.onFailure { e -> Log.w(TAG, "Failed to persist QUIC connection: ${e.message}") }
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

        // Fix #26 / P2 parity: parse QUIC Initial packets on UDP port 443.
        if (protocol == 17 && (sPort == 443 || dPort == 443) && length >= transportOffset + 8) {
            val quicPayload = packet.copyOfRange(transportOffset + 8, length)
            val initial = com.example.interception.quic.QuicParser.parseInitial(quicPayload)
            if (initial != null) {
                Log.i(
                    TAG,
                    "QUIC ${if (initial.decrypted) "decrypted" else "heuristic"} SNI: " +
                        "${initial.sniHostname} alpn=${initial.alpn} " +
                        "(${if (dPort == 443) "outbound" else "inbound"})",
                )
                persistQuicConnection(src, sPort, dst, dPort, initial, quicPayload)
            }
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
        activeFlows[key] = now
        if (activeFlows.size > MAX_TRACKED_FLOWS) {
            val cutoff = now - FLOW_TTL_MS
            activeFlows.entries.removeAll { it.value < cutoff }
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
        activeFlows.entries.removeAll { it.value < cutoff }
        val connections = activeFlows.size

        captureEngine.prune()

        // M1: bound the UDP/QUIC bookkeeping maps -- drop entries for flows no longer active so
        // long-running sessions don't leak memory keyed by every 5-tuple ever seen.
        lastUdpPersist.entries.removeAll { it.value < cutoff }
        if (quicConnections.size > MAX_TRACKED_FLOWS) quicConnections.clear()
        if (quicConnectionState.size > MAX_TRACKED_FLOWS) quicConnectionState.clear()

        InterceptionStateRepository.updateCounters(
            packetCount = packetCount,
            bytesTransferred = byteCount,
            activeConnections = connections,
        )

        val transactions = captureEngine.transactionCount

        runCatching {
            db.captureSessionDao().updateCounters(
                id = sessionId,
                packets = packetCount,
                bytes = byteCount,
                transactions = transactions,
            )
        }
    }

    private fun Byte.u(): Int = this.toInt() and 0xFF

    private companion object {
        const val TAG = "PacketPump"
        const val MAX_PACKET = 32767
        const val IDLE_BACKOFF_MS = 10L
        const val FLUSH_INTERVAL_MS = 1000L
        const val FLOW_TTL_MS = 30_000L
        const val MAX_TRACKED_FLOWS = 512
        const val UDP_PERSIST_INTERVAL_MS = 2_000L
    }
}
