package com.example.interception.quic

import com.example.data.local.QuicDecodeStatus
import com.example.interception.tls.KeyLogLabel
import com.example.interception.tls.TlsDecoder
import java.io.ByteArrayOutputStream

/**
 * Per-connection QUIC/HTTP-3 decode state machine — the piece that turns routed datagrams into real
 * decoded HTTP/3 exchanges, and the honest absence of them when key material is missing.
 *
 * [com.example.interception.quic.QuicDatagramRouter] splits and routes datagrams; this class owns
 * everything that is *per connection* and therefore cannot live in a stateless router:
 *
 * - **Packet-number spaces (RFC 9000 §12.3).** Initial, Handshake, and application-data (1-RTT and
 *   0-RTT share it) each track their own largest processed packet number, *per direction*, because
 *   each endpoint numbers its own packets. That largest value is what makes
 *   [QuicCrypto.decodePacketNumber] reconstruct a truncated on-wire packet number correctly beyond
 *   the first packet of a space.
 * - **Connection identity for key lookup.** The client's original Destination Connection ID (needed
 *   to derive the *server's* Initial keys, RFC 9001 §5.2 — a server-sourced packet never carries it)
 *   and the ClientHello `random`, which is the identity a TLS key log is indexed by, so
 *   [com.example.interception.tls.TlsKeyLogRegistry] secrets can be matched to this connection.
 * - **Negotiated cipher suite.** Recovered from the *real* ServerHello in the server's Initial-level
 *   CRYPTO stream (Initial keys are derived from public information, so this needs no key log), then
 *   locked for the connection: it selects the HKDF hash, AEAD, and header-protection construction
 *   for every later level.
 * - **Handshake / 0-RTT / 1-RTT decryption.** Traffic secrets come from the key-log registry; keys
 *   from [QuicCrypto.deriveKeysFromTrafficSecret]; 1-RTT goes through [QuicCrypto.KeyPhaseManager]
 *   so a key update authenticates *before* promotion and a retired generation still decrypts delayed
 *   packets for a bounded window (RFC 9001 §6).
 * - **Stream reassembly and HTTP/3.** Decrypted STREAM frames are fed by stream ID **and byte
 *   offset** into a per-(stream, direction) [QuicStreamReassembler] — QUIC gives no packet-boundary
 *   guarantees and freely retransmits/reorders — and the contiguous prefix is fed to
 *   [Http3StreamParser], which owns control-stream SETTINGS, the QPACK encoder/decoder streams, and
 *   request-stream HEADERS/DATA.
 * - **Honest status.** [decodeStatus] is a monotonic high-water mark of what was *actually*
 *   achieved, and [missingKeyLabels]/[keyMaterialMissing] record exactly which secrets were looked
 *   up and not found. Nothing here ever manufactures an exchange: an [Http3Exchange] exists only
 *   because a HEADERS field section really decoded.
 *
 * Deliberately free of Android, Room, and coroutine dependencies (it takes a plain [secretLookup]
 * function and a [clock]) so the whole pipeline is unit-testable on the host JVM from raw packet
 * bytes. [com.example.interception.PacketPump] owns persistence and passes
 * [com.example.interception.tls.TlsKeyLogRegistry]'s lookup in.
 *
 * Not thread-safe: one instance is owned by the single tun-read path for its connection.
 *
 * @param connectionId the routing key resolved by the router (== the Room row's primary key).
 * @param version the QUIC version observed for this connection (selects `quic`/`quicv2` labels).
 * @param frameWalker shared frame walker; also applies NEW/RETIRE_CONNECTION_ID to the CID table.
 * @param secretLookup resolves a TLS 1.3 traffic secret for (label, client random), or null on a miss.
 */
class QuicConnectionDecoder(
    val connectionId: String,
    val version: Int,
    private val frameWalker: QuicFrameWalker,
    private val secretLookup: (KeyLogLabel, ByteArray) -> ByteArray?,
    private val maxTrackedStreams: Int = MAX_TRACKED_STREAMS,
    private val maxTrackedExchanges: Int = MAX_TRACKED_EXCHANGES,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    private val maxCryptoBytes: Int = MAX_CRYPTO_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** TLS/QUIC encryption level a packet belongs to (RFC 9001 §4.1.3). */
    enum class Level { INITIAL, ZERO_RTT, HANDSHAKE, ONE_RTT }

    /**
     * Packet-number space (RFC 9000 §12.3). 0-RTT and 1-RTT share the application-data space; Initial
     * and Handshake each have their own. Tracked per direction as well, since each endpoint numbers
     * its own packets independently.
     */
    enum class PacketNumberSpace { INITIAL, HANDSHAKE, APPLICATION_DATA }

    private data class SpaceKey(val space: PacketNumberSpace, val direction: QuicDatagramRouter.Direction)
    private data class LevelKey(val level: Level, val direction: QuicDatagramRouter.Direction)
    private data class StreamKey(val streamId: Long, val direction: QuicDatagramRouter.Direction)

    /**
     * One HTTP/3 request/response exchange on a client-initiated bidirectional stream, built purely
     * from genuinely decoded frames: it comes into existence when a QPACK field section actually
     * decoded, never because a packet merely existed. Bodies accumulate DATA-frame bytes up to
     * [maxBodyBytes] and set the corresponding truncated flag beyond that, while
     * [requestBodyBytes]/[responseBodyBytes] keep counting the real observed totals.
     */
    class Http3Exchange internal constructor(val streamId: Long, val startedAt: Long) {
        var method: String? = null
            internal set
        var path: String? = null
            internal set
        var authority: String? = null
            internal set
        var scheme: String? = null
            internal set
        var requestHeaders: Map<String, String> = emptyMap()
            internal set
        var responseHeaders: Map<String, String> = emptyMap()
            internal set
        /** The `:status` pseudo-header parsed as an integer, or null when no response HEADERS decoded. */
        var statusCode: Int? = null
            internal set
        var hasRequestHeaders: Boolean = false
            internal set
        var hasResponseHeaders: Boolean = false
            internal set
        var requestBodyBytes: Long = 0
            internal set
        var responseBodyBytes: Long = 0
            internal set
        var requestBodyTruncated: Boolean = false
            internal set
        var responseBodyTruncated: Boolean = false
            internal set
        /** True once the request stream's FIN has been received and fully reassembled. */
        var requestComplete: Boolean = false
            internal set
        /** True once the response stream's FIN has been received and fully reassembled. */
        var responseComplete: Boolean = false
            internal set
        var requestHeadersAt: Long? = null
            internal set
        var responseHeadersAt: Long? = null
            internal set
        var lastUpdatedAt: Long = startedAt
            internal set
        /**
         * Room `captured_traffic.id` once the owning persist path has written this exchange, so later
         * body/status updates target the same row instead of inserting a duplicate. The decoder never
         * touches Room itself — this is set by [com.example.interception.PacketPump].
         */
        var persistedRowId: Long? = null

        private val requestBodyBuffer = ByteArrayOutputStream()
        private val responseBodyBuffer = ByteArrayOutputStream()

        /** Latency from decoded request HEADERS to decoded response HEADERS, or 0 when unknown. */
        val latencyMs: Long
            get() {
                val start = requestHeadersAt ?: return 0
                val end = responseHeadersAt ?: return 0
                return (end - start).coerceAtLeast(0)
            }

        fun requestBody(): ByteArray = requestBodyBuffer.toByteArray()

        fun responseBody(): ByteArray = responseBodyBuffer.toByteArray()

        internal fun appendRequestBody(bytes: ByteArray, limit: Int) {
            requestBodyBytes += bytes.size
            val room = limit - requestBodyBuffer.size()
            if (room <= 0) {
                if (bytes.isNotEmpty()) requestBodyTruncated = true
                return
            }
            if (bytes.size > room) {
                requestBodyBuffer.write(bytes, 0, room)
                requestBodyTruncated = true
            } else {
                requestBodyBuffer.write(bytes)
            }
        }

        internal fun appendResponseBody(bytes: ByteArray, limit: Int) {
            responseBodyBytes += bytes.size
            val room = limit - responseBodyBuffer.size()
            if (room <= 0) {
                if (bytes.isNotEmpty()) responseBodyTruncated = true
                return
            }
            if (bytes.size > room) {
                responseBodyBuffer.write(bytes, 0, room)
                responseBodyTruncated = true
            } else {
                responseBodyBuffer.write(bytes)
            }
        }
    }

    /**
     * What one [ingest] call changed. [updatedExchanges] carries every exchange whose decoded content
     * changed, so the persist path writes exactly the rows that gained real data — an empty list means
     * nothing decodable happened and nothing should be written.
     */
    data class IngestResult(
        /** True when this packet's payload was really decrypted (any level). */
        val decrypted: Boolean,
        val statusChanged: Boolean,
        val lifecycleChanged: Boolean,
        /** True when connection provenance changed (client random, cipher suite, resolved key labels). */
        val provenanceChanged: Boolean,
        val updatedExchanges: List<Http3Exchange>,
    )

    // ── Connection-level state ───────────────────────────────────────────────────────────

    var lifecycle: QuicState = QuicState.INITIALIZING
        private set

    var decodeStatus: QuicDecodeStatus = QuicDecodeStatus.DETECTED_ONLY
        private set

    /** The negotiated AEAD suite, locked from the real ServerHello (or an explicit external lock). */
    var negotiatedCipherSuite: QuicCrypto.CipherSuite? = null
        private set

    /** The client's original DCID — the only input to *both* directions' Initial keys (RFC 9001 §5.2). */
    var originalDcid: ByteArray? = null
        private set

    /** The ClientHello `random`: this connection's TLS-key-log identity. Null until an Initial decodes. */
    var clientRandom: ByteArray? = null
        private set

    /** Lowercase hex of [clientRandom] for the `quic_connections.clientRandomHex` provenance column. */
    var clientRandomHex: String? = null
        private set

    /** Traffic-secret labels really resolved from the key-log registry for this connection. */
    val resolvedKeyLabels: Set<KeyLogLabel> get() = resolvedLabels

    /** Traffic-secret labels looked up and **not** found — the explicit key-missing evidence. */
    val missingKeyLabels: Set<KeyLogLabel> get() = missingLabels

    /** Count of protected (Handshake/0-RTT/1-RTT) packets observed, decryptable or not. */
    var protectedPacketsObserved: Int = 0
        private set

    /** Count of packets whose payload really decrypted. */
    var packetsDecrypted: Int = 0
        private set

    /** Count of packets that had keys but failed AEAD (corrupt, wrong space, spoofed). */
    var decryptFailures: Int = 0
        private set

    /** Count of authenticated 1-RTT key updates promoted (RFC 9001 §6). */
    var keyUpdatesPromoted: Int = 0
        private set

    /** CONNECTION_CLOSE reason phrase, when one was observed in a decrypted packet. */
    var closeReason: String? = null
        private set

    /** Wall-clock time of the last packet ingested, for the owner's idle-based eviction. */
    var lastActivityAt: Long = clock()
        private set

    /**
     * True when protected packets were observed but **no** traffic secret is available for them — the
     * honest "observed, cannot decrypt" state. False when nothing protected was seen yet (nothing to
     * decrypt) and false once any secret resolved.
     */
    val keyMaterialMissing: Boolean
        get() = protectedPacketsObserved > 0 && resolvedLabels.isEmpty()

    /**
     * True when protected packets were observed before the negotiated cipher suite could be
     * recovered — e.g. the server's Initial (which carries the ServerHello) was never captured, so no
     * AEAD can be selected regardless of what the key log holds.
     */
    var cipherSuiteUnavailable: Boolean = false
        private set

    private val resolvedLabels = linkedSetOf<KeyLogLabel>()
    private val missingLabels = linkedSetOf<KeyLogLabel>()

    private val largestPacketNumbers = mutableMapOf<SpaceKey, Long>()
    private val levelKeys = mutableMapOf<LevelKey, QuicCrypto.PacketProtectionKeys>()
    private val keyPhaseManagers = mutableMapOf<QuicDatagramRouter.Direction, QuicCrypto.KeyPhaseManager>()

    private val cryptoReassemblers = mutableMapOf<LevelKey, QuicStreamReassembler>()
    private val cryptoStreams = mutableMapOf<LevelKey, ByteArrayOutputStream>()

    private val streamReassemblers = LinkedHashMap<StreamKey, QuicStreamReassembler>()
    private val http3Parsers = mutableMapOf<QuicDatagramRouter.Direction, Http3StreamParser>()
    private val exchangeMap = LinkedHashMap<Long, Http3Exchange>()

    /** Destination-Connection-ID length to assume for a short header in each direction. */
    private val shortHeaderDcidLength = mutableMapOf<QuicDatagramRouter.Direction, Int>()

    /** Decoded HTTP/3 exchanges on this connection, in first-seen order. */
    val exchanges: Collection<Http3Exchange> get() = exchangeMap.values

    val trackedStreamCount: Int get() = streamReassemblers.size

    /** Bytes currently buffered across HTTP/3 bodies and crypto streams — the bounded-memory probe. */
    fun bufferedByteCount(): Int {
        var total = cryptoStreams.values.sumOf { it.size() }
        for (ex in exchangeMap.values) total += ex.requestBody().size + ex.responseBody().size
        return total
    }

    // ── Ingest ───────────────────────────────────────────────────────────────────────────

    /**
     * Feeds one routed QUIC packet through the connection's decode state machine: records identity,
     * decrypts what the available keys allow, reassembles CRYPTO and STREAM data, drives HTTP/3
     * parsing, and updates [decodeStatus]/[lifecycle]. Never throws — every malformed or
     * undecryptable packet degrades to "nothing decoded", which is a first-class outcome here.
     */
    fun ingest(routed: QuicDatagramRouter.RoutedPacket): IngestResult {
        lastActivityAt = clock()
        val statusBefore = decodeStatus
        val lifecycleBefore = lifecycle
        val provenanceBefore = provenanceFingerprint()
        touchedExchanges.clear()

        var decrypted = false
        try {
            recordConnectionIdLengths(routed)
            val firstByte = routed.payloadBytes.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            val isLongHeader = (firstByte and 0x80) != 0
            decrypted = when {
                routed.packetType == QuicCrypto.LongHeaderPacketType.INITIAL -> handleInitial(routed)
                routed.packetType == QuicCrypto.LongHeaderPacketType.HANDSHAKE ->
                    handleProtectedLongHeader(routed, Level.HANDSHAKE)
                routed.packetType == QuicCrypto.LongHeaderPacketType.ZERO_RTT ->
                    handleProtectedLongHeader(routed, Level.ZERO_RTT)
                routed.packetType == QuicCrypto.LongHeaderPacketType.RETRY -> {
                    // A Retry re-keys the connection; the router already registered the Retry's SCID as
                    // a routable CID. Nothing is decryptable here (a Retry carries no payload).
                    advanceLifecycle(QuicState.HANDSHAKE_IN_PROGRESS)
                    false
                }
                !isLongHeader -> handleShortHeader(routed)
                else -> false // Version Negotiation / unknown version: no payload protection to remove
            }
        } catch (e: Exception) {
            // A decode path must never take down the capture: treat any unexpected failure as
            // "nothing decoded for this packet".
            decryptFailures++
        }

        return IngestResult(
            decrypted = decrypted,
            statusChanged = decodeStatus != statusBefore,
            lifecycleChanged = lifecycle != lifecycleBefore,
            provenanceChanged = provenanceFingerprint() != provenanceBefore,
            updatedExchanges = touchedExchanges.toList(),
        )
    }

    /** Exchanges touched by the packet currently being ingested (cleared at the start of [ingest]). */
    private val touchedExchanges = LinkedHashSet<Http3Exchange>()

    /**
     * Records the Source Connection ID length each peer advertises, which is the *other* peer's
     * Destination Connection ID length once 1-RTT starts — the only way to locate the Packet Number
     * field in a short header, which carries no CID length (RFC 9000 §17.3).
     */
    private fun recordConnectionIdLengths(routed: QuicDatagramRouter.RoutedPacket) {
        if (routed.packetType == null) return // short header / version negotiation: no CID fields to read
        when (routed.direction) {
            QuicDatagramRouter.Direction.OUTBOUND -> {
                // The client's SCID is what the server addresses its 1-RTT packets to.
                if (routed.scid.isNotEmpty()) shortHeaderDcidLength[QuicDatagramRouter.Direction.INBOUND] = routed.scid.size
                if (routed.packetType == QuicCrypto.LongHeaderPacketType.INITIAL && originalDcid == null) {
                    originalDcid = routed.dcid.copyOf()
                }
            }
            QuicDatagramRouter.Direction.INBOUND -> {
                // The server's SCID is what the client addresses its 1-RTT packets to.
                if (routed.scid.isNotEmpty()) shortHeaderDcidLength[QuicDatagramRouter.Direction.OUTBOUND] = routed.scid.size
            }
        }
    }

    /**
     * Initial packets. Client-direction identity (SNI/ALPN/ClientHello random) was already recovered
     * by [QuicParser.parseInitial] inside the router using the public Initial keys; the
     * server-direction packet is decrypted **here**, because its keys derive from the client's
     * original DCID ([originalDcid]) rather than from anything the server's own packet carries — and
     * its Initial-level CRYPTO stream is where the real ServerHello (and therefore the negotiated
     * cipher suite) lives.
     */
    private fun handleInitial(routed: QuicDatagramRouter.RoutedPacket): Boolean {
        advanceLifecycle(QuicState.HANDSHAKE_IN_PROGRESS)
        if (routed.direction == QuicDatagramRouter.Direction.OUTBOUND) {
            routed.clientRandom?.let { random ->
                if (clientRandom == null && random.size == CLIENT_RANDOM_LEN) {
                    clientRandom = random.copyOf()
                    clientRandomHex = random.toHexLower()
                }
            }
            if (routed.decrypted) {
                raiseStatus(QuicDecodeStatus.PARTIALLY_DECODED)
                return true
            }
            return false
        }

        // Inbound (server) Initial: derive the server-direction Initial keys from the client's
        // original DCID and decrypt for real.
        val dcid = originalDcid ?: return false
        val keys = QuicCrypto.deriveServerInitialKeys(version, dcid) ?: return false
        val spaceKey = SpaceKey(PacketNumberSpace.INITIAL, routed.direction)
        val decryptedInitial = QuicCrypto.decryptInitialPacketAt(
            packet = routed.payloadBytes,
            keys = keys,
            largestPn = largestPacketNumbers[spaceKey] ?: -1L,
        ) ?: run {
            decryptFailures++
            return false
        }
        recordLargestPacketNumber(spaceKey, decryptedInitial.packetNumber)
        packetsDecrypted++
        raiseStatus(QuicDecodeStatus.PARTIALLY_DECODED)
        processFrames(decryptedInitial.cryptoData, Level.INITIAL, routed.direction)
        return true
    }

    /**
     * Handshake (RFC 9000 §17.2.4) and 0-RTT (§17.2.3) long-header packets: keyed from a real TLS 1.3
     * traffic secret resolved out of the key-log registry by this connection's ClientHello random, and
     * decrypted with the RFC 9001 §5.3 header Associated Data. Returns false — recording the precise
     * reason as [cipherSuiteUnavailable] or a [missingKeyLabels] entry — when the connection has no
     * negotiated suite, no identity, or no secret for this level/direction.
     */
    private fun handleProtectedLongHeader(routed: QuicDatagramRouter.RoutedPacket, level: Level): Boolean {
        protectedPacketsObserved++
        advanceLifecycle(QuicState.HANDSHAKE_IN_PROGRESS)
        val keys = protectionKeysFor(level, routed.direction) ?: return false
        val pnOffset = routed.pnOffset
        if (pnOffset < 0) return false
        val space = if (level == Level.HANDSHAKE) PacketNumberSpace.HANDSHAKE else PacketNumberSpace.APPLICATION_DATA
        val spaceKey = SpaceKey(space, routed.direction)
        val result = QuicCrypto.decryptLongHeaderPacket(
            keys = keys,
            packet = routed.payloadBytes,
            pnOffset = pnOffset,
            packetEnd = routed.payloadBytes.size,
            largestPn = largestPacketNumbers[spaceKey] ?: -1L,
        ) ?: run {
            decryptFailures++
            return false
        }
        recordLargestPacketNumber(spaceKey, result.packetNumber)
        packetsDecrypted++
        raiseStatus(QuicDecodeStatus.PARTIALLY_DECODED)
        processFrames(result.plaintext, level, routed.direction)
        return true
    }

    /**
     * 1-RTT short-header packets (RFC 9000 §17.3) under key-phase rotation. Header protection is
     * removed with the connection's (never-rotated, RFC 9001 §6) `hp` key to expose the KEY_PHASE bit
     * and the packet number, then the AEAD step runs through [QuicCrypto.KeyPhaseManager] so a
     * peer-signalled key update is promoted **only** after it authenticates and a retired generation
     * still decrypts delayed packets.
     *
     * A short header carries no CID length, so the Packet Number offset comes from the peer's Source
     * Connection ID length learned during the handshake; when that was never observed, the two
     * lengths real deployments use (8 and 0) are tried and a wrong guess simply fails AEAD.
     */
    private fun handleShortHeader(routed: QuicDatagramRouter.RoutedPacket): Boolean {
        protectedPacketsObserved++
        val suite = negotiatedCipherSuite ?: run {
            cipherSuiteUnavailable = true
            raiseStatus(QuicDecodeStatus.OBSERVED_NO_KEYS)
            return false
        }
        val random = clientRandom ?: run {
            raiseStatus(QuicDecodeStatus.OBSERVED_NO_KEYS)
            return false
        }
        val label = if (routed.direction == QuicDatagramRouter.Direction.OUTBOUND) {
            KeyLogLabel.CLIENT_TRAFFIC_SECRET_0
        } else {
            KeyLogLabel.SERVER_TRAFFIC_SECRET_0
        }
        val manager = keyPhaseManagers[routed.direction] ?: run {
            val secret = secretLookup(label, random) ?: run {
                missingLabels.add(label)
                raiseStatus(QuicDecodeStatus.OBSERVED_NO_KEYS)
                return false
            }
            val created = try {
                QuicCrypto.KeyPhaseManager(version, suite, secret)
            } catch (e: Exception) {
                return false
            }
            resolvedLabels.add(label)
            missingLabels.remove(label)
            keyPhaseManagers[routed.direction] = created
            created
        }

        val spaceKey = SpaceKey(PacketNumberSpace.APPLICATION_DATA, routed.direction)
        val largestPn = largestPacketNumbers[spaceKey] ?: -1L
        // Header protection never rotates (RFC 9001 §5.4), so the connection's fixed `hp` key removes
        // protection for every key phase -- using the current generation's freshly derived `hp` would
        // corrupt the packet number of any packet sent after a key update.
        val hpKeys = manager.headerProtectionKeys()
        for (dcidLen in candidateDcidLengths(routed.direction)) {
            val unprotected = QuicCrypto.unprotectShortHeaderPacket(
                keys = hpKeys,
                packet = routed.payloadBytes,
                dcidLen = dcidLen,
                largestPn = largestPn,
            ) ?: continue
            val decryptResult = manager.attemptUnprotectPacket(
                keyPhaseBit = unprotected.keyPhaseBit,
                packetNumber = unprotected.packetNumber,
                aad = unprotected.aad,
                ciphertext = unprotected.ciphertext,
            ) ?: continue

            // The length that actually authenticated is the connection's real one — remember it so
            // later packets in this direction skip the candidate loop entirely.
            shortHeaderDcidLength[routed.direction] = dcidLen
            if (decryptResult.promoted) keyUpdatesPromoted++
            recordLargestPacketNumber(spaceKey, unprotected.packetNumber)
            packetsDecrypted++
            advanceLifecycle(QuicState.HANDSHAKE_COMPLETE)
            raiseStatus(QuicDecodeStatus.PARTIALLY_DECODED)
            processFrames(decryptResult.plaintext, Level.ONE_RTT, routed.direction)
            return true
        }
        decryptFailures++
        return false
    }

    /** Known DCID length for [direction] first, then the lengths real deployments use. */
    private fun candidateDcidLengths(direction: QuicDatagramRouter.Direction): List<Int> {
        val known = shortHeaderDcidLength[direction]
        return if (known != null) listOf(known) else FALLBACK_DCID_LENGTHS
    }

    /**
     * Resolves (and caches) the {key, iv, hp} for a Handshake/0-RTT level+direction from the key-log
     * registry. Records the precise unavailability reason instead of silently returning null:
     * [cipherSuiteUnavailable] when no ServerHello was ever seen, a [missingKeyLabels] entry when the
     * registry has no secret for this connection's identity.
     */
    private fun protectionKeysFor(level: Level, direction: QuicDatagramRouter.Direction): QuicCrypto.PacketProtectionKeys? {
        val cached = levelKeys[LevelKey(level, direction)]
        if (cached != null) return cached
        val suite = negotiatedCipherSuite ?: run {
            cipherSuiteUnavailable = true
            raiseStatus(QuicDecodeStatus.OBSERVED_NO_KEYS)
            return null
        }
        val random = clientRandom ?: run {
            raiseStatus(QuicDecodeStatus.OBSERVED_NO_KEYS)
            return null
        }
        val label = when {
            level == Level.ZERO_RTT -> KeyLogLabel.CLIENT_EARLY_TRAFFIC_SECRET
            direction == QuicDatagramRouter.Direction.OUTBOUND -> KeyLogLabel.CLIENT_HANDSHAKE_TRAFFIC_SECRET
            else -> KeyLogLabel.SERVER_HANDSHAKE_TRAFFIC_SECRET
        }
        val secret = secretLookup(label, random) ?: run {
            missingLabels.add(label)
            raiseStatus(QuicDecodeStatus.OBSERVED_NO_KEYS)
            return null
        }
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(version, suite, secret) ?: return null
        resolvedLabels.add(label)
        missingLabels.remove(label)
        levelKeys[LevelKey(level, direction)] = keys
        return keys
    }

    // ── Frame processing ─────────────────────────────────────────────────────────────────

    /**
     * Walks a decrypted payload's frames and applies the ones that carry decode state: CRYPTO into the
     * level's TLS handshake reassembly (which is where the negotiated cipher suite comes from), STREAM
     * into per-stream byte-offset reassembly and HTTP/3 parsing, CONNECTION_CLOSE into the lifecycle.
     * NEW/RETIRE_CONNECTION_ID were already applied to the shared CID table by the walker itself.
     */
    private fun processFrames(plaintext: ByteArray, level: Level, direction: QuicDatagramRouter.Direction) {
        for (frame in frameWalker.walk(connectionId, plaintext)) {
            when (frame) {
                is QuicFrameWalker.Frame.Crypto -> handleCryptoFrame(frame, level, direction)
                is QuicFrameWalker.Frame.Stream ->
                    if (level == Level.ONE_RTT || level == Level.ZERO_RTT) handleStreamFrame(frame, direction)
                is QuicFrameWalker.Frame.ConnectionClose -> {
                    closeReason = frame.reasonPhrase.ifBlank { "error 0x${frame.errorCode.toString(16)}" }
                    advanceLifecycle(QuicState.CLOSED)
                }
                is QuicFrameWalker.Frame.NewConnectionId, is QuicFrameWalker.Frame.RetireConnectionId -> Unit
            }
        }
    }

    /**
     * Reassembles a CRYPTO frame into its level+direction TLS handshake stream by byte offset (a
     * ServerHello or a certificate chain is not guaranteed to arrive in one frame or one packet) and,
     * for the server's Initial level, locks the negotiated cipher suite as soon as a complete
     * ServerHello is present.
     */
    private fun handleCryptoFrame(frame: QuicFrameWalker.Frame.Crypto, level: Level, direction: QuicDatagramRouter.Direction) {
        val key = LevelKey(level, direction)
        val reassembler = cryptoReassemblers.getOrPut(key) { QuicStreamReassembler(CRYPTO_STREAM_ID) }
        if (reassembler.feed(frame.offset, frame.data, isFin = false) != QuicStreamReassembler.FeedResult.ACCEPTED) return
        val contiguous = reassembler.drain()
        if (contiguous.isEmpty()) return
        val stream = cryptoStreams.getOrPut(key) { ByteArrayOutputStream() }
        val room = maxCryptoBytes - stream.size()
        if (room <= 0) return
        stream.write(contiguous, 0, minOf(room, contiguous.size))

        if (level == Level.INITIAL && direction == QuicDatagramRouter.Direction.INBOUND && negotiatedCipherSuite == null) {
            // RFC 9001 §4.1.3 maps the ServerHello to the Initial encryption level, so this is the one
            // place a passive observer can read the negotiated suite without any key material.
            TlsDecoder.findServerHello(stream.toByteArray())?.let { serverHello ->
                lockCipherSuiteFromIana(serverHello.cipherSuiteId)
            }
        }
    }

    /**
     * Feeds a STREAM frame into the per-(stream, direction) reassembler by byte offset, then hands the
     * contiguous prefix to that direction's [Http3StreamParser]. Each direction of a bidirectional
     * stream is an independent byte stream with its own offsets (RFC 9000 §2.1), so they are never
     * mixed. Duplicates are dropped by the reassembler and overlapping offsets rejected, so a
     * retransmission cannot corrupt the HTTP/3 parse.
     */
    private fun handleStreamFrame(frame: QuicFrameWalker.Frame.Stream, direction: QuicDatagramRouter.Direction) {
        val key = StreamKey(frame.streamId, direction)
        val reassembler = streamReassemblers[key] ?: run {
            if (streamReassemblers.size >= maxTrackedStreams) evictOldestStream()
            val created = QuicStreamReassembler(frame.streamId)
            streamReassemblers[key] = created
            created
        }
        if (reassembler.feed(frame.offset, frame.data, frame.fin) != QuicStreamReassembler.FeedResult.ACCEPTED) return
        val contiguous = reassembler.drain()
        if (contiguous.isNotEmpty()) {
            val parser = http3Parsers.getOrPut(direction) { Http3StreamParser() }
            val events = parser.feed(
                streamId = frame.streamId,
                data = contiguous,
                isOutbound = direction == QuicDatagramRouter.Direction.OUTBOUND,
            )
            applyHttp3Events(events)
        }
        if (reassembler.isComplete) markStreamComplete(frame.streamId, direction)
    }

    /** Applies decoded HTTP/3 events onto their exchange; only real decodes create or touch a row. */
    private fun applyHttp3Events(events: List<Http3StreamParser.Event>) {
        for (event in events) {
            when (event) {
                is Http3StreamParser.Event.RequestHeaders -> {
                    val exchange = exchangeFor(event.streamId) ?: continue
                    exchange.hasRequestHeaders = true
                    exchange.method = event.headers[":method"]
                    exchange.path = event.headers[":path"]
                    exchange.authority = event.headers[":authority"]
                    exchange.scheme = event.headers[":scheme"]
                    exchange.requestHeaders = event.headers.filterKeys { !it.startsWith(":") }
                    exchange.requestHeadersAt = clock()
                    touch(exchange)
                    raiseStatus(QuicDecodeStatus.FULLY_DECODED)
                }
                is Http3StreamParser.Event.ResponseHeaders -> {
                    val exchange = exchangeFor(event.streamId) ?: continue
                    exchange.hasResponseHeaders = true
                    exchange.statusCode = event.status?.toIntOrNull()
                    exchange.responseHeaders = event.headers.filterKeys { !it.startsWith(":") }
                    exchange.responseHeadersAt = clock()
                    touch(exchange)
                    raiseStatus(QuicDecodeStatus.FULLY_DECODED)
                }
                is Http3StreamParser.Event.RequestData -> {
                    val exchange = exchangeFor(event.streamId) ?: continue
                    exchange.appendRequestBody(event.bytes, maxBodyBytes)
                    touch(exchange)
                }
                is Http3StreamParser.Event.ResponseData -> {
                    val exchange = exchangeFor(event.streamId) ?: continue
                    exchange.appendResponseBody(event.bytes, maxBodyBytes)
                    touch(exchange)
                }
                is Http3StreamParser.Event.Goaway -> advanceLifecycle(QuicState.DRAINING)
                is Http3StreamParser.Event.Settings -> Unit // capacity applied inside the parser
            }
        }
    }

    /** Marks the request/response half of an exchange complete once its stream FIN fully reassembled. */
    private fun markStreamComplete(streamId: Long, direction: QuicDatagramRouter.Direction) {
        val exchange = exchangeMap[streamId] ?: return
        if (direction == QuicDatagramRouter.Direction.OUTBOUND) exchange.requestComplete = true
        else exchange.responseComplete = true
        touch(exchange)
    }

    private fun exchangeFor(streamId: Long): Http3Exchange? {
        exchangeMap[streamId]?.let { return it }
        if (exchangeMap.size >= maxTrackedExchanges) {
            // Bounded: drop the oldest *finished* exchange first, otherwise the oldest one outright, so
            // a long-lived connection can never grow this map without limit.
            val victim = exchangeMap.entries.firstOrNull { it.value.requestComplete && it.value.responseComplete }
                ?: exchangeMap.entries.firstOrNull()
            victim?.let { exchangeMap.remove(it.key) }
        }
        val created = Http3Exchange(streamId, clock())
        exchangeMap[streamId] = created
        return created
    }

    private fun touch(exchange: Http3Exchange) {
        exchange.lastUpdatedAt = clock()
        touchedExchanges.add(exchange)
    }

    private fun evictOldestStream() {
        val oldest = streamReassemblers.keys.firstOrNull() ?: return
        streamReassemblers.remove(oldest)
    }

    // ── Status, lifecycle, provenance ────────────────────────────────────────────────────

    /**
     * Locks the negotiated AEAD suite for this connection from an IANA cipher-suite code (RFC 8446
     * §4.1.3). Returns true when a suite QUIC can negotiate was recognized and locked. Changing an
     * already-locked suite invalidates every derived key so nothing decrypts under a stale AEAD.
     */
    fun lockCipherSuiteFromIana(ianaCode: Int): Boolean {
        val suite = QuicCrypto.CipherSuite.fromIanaCode(ianaCode) ?: return false
        lockCipherSuite(suite)
        return true
    }

    /** Locks the negotiated AEAD suite directly (see [lockCipherSuiteFromIana]). */
    fun lockCipherSuite(suite: QuicCrypto.CipherSuite) {
        if (negotiatedCipherSuite == suite) return
        negotiatedCipherSuite = suite
        cipherSuiteUnavailable = false
        levelKeys.clear()
        keyPhaseManagers.clear()
    }

    /**
     * Seeds this connection's TLS-key-log identity from outside the Initial path (e.g. a MITM leg or
     * SDK plane that already knows the ClientHello random for this connection). Ignores a wrong-length
     * value rather than throwing, mirroring [com.example.interception.tls.TlsKeyLogRegistry.record].
     */
    fun recordClientRandom(random: ByteArray) {
        if (random.size != CLIENT_RANDOM_LEN) return
        clientRandom = random.copyOf()
        clientRandomHex = random.toHexLower()
    }

    /** Comma-separated resolved key-log labels for the row's provenance column, or null when none. */
    fun resolvedKeyLabelsCsv(): String? =
        if (resolvedLabels.isEmpty()) null else resolvedLabels.joinToString(",") { it.name }

    /** Monotonic: [decodeStatus] only ever moves to a strictly better-evidenced state. */
    private fun raiseStatus(candidate: QuicDecodeStatus) {
        if (statusRank(candidate) > statusRank(decodeStatus)) decodeStatus = candidate
    }

    private fun statusRank(status: QuicDecodeStatus): Int = when (status) {
        QuicDecodeStatus.DETECTED_ONLY -> 0
        QuicDecodeStatus.OBSERVED_NO_KEYS -> 1
        QuicDecodeStatus.PARTIALLY_DECODED -> 2
        QuicDecodeStatus.FULLY_DECODED -> 3
    }

    /** Lifecycle only advances (INITIALIZING → … → CLOSED); a late packet never rewinds it. */
    private fun advanceLifecycle(candidate: QuicState) {
        if (lifecycleRank(candidate) > lifecycleRank(lifecycle)) lifecycle = candidate
    }

    private fun lifecycleRank(state: QuicState): Int = when (state) {
        QuicState.INITIALIZING -> 0
        QuicState.HANDSHAKE_IN_PROGRESS -> 1
        QuicState.HANDSHAKE_COMPLETE -> 2
        QuicState.DRAINING -> 3
        QuicState.CLOSED -> 4
    }

    private fun recordLargestPacketNumber(key: SpaceKey, packetNumber: Long) {
        val current = largestPacketNumbers[key] ?: -1L
        if (packetNumber > current) largestPacketNumbers[key] = packetNumber
    }

    /** The largest processed packet number in a space+direction, or -1 when none — test/inspection hook. */
    fun largestPacketNumber(space: PacketNumberSpace, direction: QuicDatagramRouter.Direction): Long =
        largestPacketNumbers[SpaceKey(space, direction)] ?: -1L

    /** Retained 1-RTT key generations for [direction] (RFC 9001 §6 bounded retirement), for inspection. */
    fun keyGeneration(direction: QuicDatagramRouter.Direction): Int? = keyPhaseManagers[direction]?.generation

    private fun provenanceFingerprint(): String =
        "${clientRandomHex ?: "-"}|${negotiatedCipherSuite?.name ?: "-"}|${resolvedKeyLabelsCsv() ?: "-"}|" +
            "${missingLabels.joinToString(",") { it.name }}|$cipherSuiteUnavailable"

    /**
     * Releases everything that is only needed while a connection is live: per-stream reassemblers,
     * HTTP/3 parser state, crypto reassembly buffers, and finished exchange bodies. Called by the
     * owner when the connection is closed or evicted, so a long capture's memory is bounded by the
     * number of *active* connections rather than by every connection ever seen.
     */
    fun release() {
        streamReassemblers.clear()
        http3Parsers.clear()
        cryptoReassemblers.clear()
        cryptoStreams.clear()
        levelKeys.clear()
        keyPhaseManagers.clear()
        exchangeMap.clear()
        touchedExchanges.clear()
    }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }

    companion object {
        private const val CLIENT_RANDOM_LEN = 32

        /** Synthetic stream id for a CRYPTO stream, which is not a QUIC stream and has no real id. */
        private const val CRYPTO_STREAM_ID = -1L

        /** Short-header DCID lengths to try when the peer's SCID length was never observed. */
        private val FALLBACK_DCID_LENGTHS = listOf(8, 0)

        /** Per-connection bounds — a hostile or very busy peer must not grow this state without limit. */
        const val MAX_TRACKED_STREAMS = 64
        const val MAX_TRACKED_EXCHANGES = 64
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_CRYPTO_BYTES = 32 * 1024
    }
}
