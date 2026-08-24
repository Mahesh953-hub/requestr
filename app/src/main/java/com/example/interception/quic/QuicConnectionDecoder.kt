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
 * @param initialVersion the QUIC version first observed. A connection first seen through a short
 *   header carries no version at all, so this is only a starting value -- see [version].
 * @param frameWalker shared frame walker; also applies NEW/RETIRE_CONNECTION_ID to the CID table.
 * @param cidTable shared CID routing table, re-keyed here when a Retry is accepted.
 * @param secretLookup resolves a TLS 1.3 traffic secret for (label, client random), or null on a miss.
 */
class QuicConnectionDecoder(
    val connectionId: String,
    initialVersion: Int,
    private val frameWalker: QuicFrameWalker,
    private val cidTable: QuicCidTable,
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
         * Increments on every mutation that changes what a persisted row would contain. The persist path
         * records the revision it last wrote and skips an exchange whose revision has not moved, which is
         * what keeps a batched flush from re-serializing and re-writing unchanged bodies.
         */
        var revision: Int = 0
            private set

        private val requestBodyBuffer = ByteArrayOutputStream()
        private val responseBodyBuffer = ByteArrayOutputStream()

        /** Bytes currently held for the request body — a size read, never a copy of the buffer. */
        val requestBodyBuffered: Int get() = requestBodyBuffer.size()

        /** Bytes currently held for the response body — a size read, never a copy of the buffer. */
        val responseBodyBuffered: Int get() = responseBodyBuffer.size()

        internal fun touchRevision() {
            revision += 1
        }

        /**
         * Continues the observed byte totals of a previous, evicted instance for this same stream. The
         * bytes counted here were really observed; they are just no longer buffered, which is exactly what
         * the truncation flags say.
         */
        internal fun seedFromEvicted(totals: EvictedExchangeTotals) {
            requestBodyBytes = totals.requestBodyBytes
            responseBodyBytes = totals.responseBodyBytes
            requestBodyTruncated = totals.requestBodyTruncated
            responseBodyTruncated = totals.responseBodyTruncated
        }

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

    /**
     * The QUIC version in force for this connection, which selects the `quic`/`quicv2` HKDF labels for
     * every derived key. A connection first observed through a short header (1-RTT, mid-connection
     * capture start) has no version on the wire at all, so this starts at whatever was first seen —
     * possibly `0` — and is corrected the moment a long header carries the real value. A correction
     * discards key material derived under the wrong labels rather than leaving it to fail silently.
     */
    var version: Int = initialVersion
        private set

    /**
     * True once [version] has been *proven* by an AEAD that authenticated under it. Until then the
     * value is only what an unauthenticated header claimed, so it may still be adopted from a later
     * long header; afterwards it is immutable, because any packet claiming a different version is
     * either a spoof or a different connection and must not be allowed to discard proven key material.
     */
    var versionConfirmed: Boolean = false
        private set

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

    /** Count of Retry packets whose integrity tag authenticated and which were therefore applied. */
    var retriesAccepted: Int = 0
        private set

    /**
     * Count of Retry packets rejected because their integrity tag did not authenticate — a corrupted
     * or spoofed Retry (RFC 9001 §5.8). Such a packet never re-keys the connection.
     */
    var retriesRejected: Int = 0
        private set

    /** Count of times [version] was corrected from a later long header (see [version]). */
    var versionCorrections: Int = 0
        private set

    /**
     * Count of long headers whose claimed version disagreed with an already-proven [version] and was
     * therefore ignored. A non-zero value means something injected or misrouted packets into this
     * connection's 5-tuple; the connection's key material was left intact.
     */
    var versionMismatchesIgnored: Int = 0
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

    /**
     * Streams whose FIN has been received *and* fully drained. Their reassemblers are released
     * immediately (they can never contribute another byte), and the key is remembered so a post-FIN
     * retransmission is dropped instead of re-creating a reassembler and re-feeding already-parsed
     * bytes into the HTTP/3 parser. Bounded, oldest-first.
     */
    private val completedStreams = LinkedHashSet<StreamKey>()
    private val http3Parsers = mutableMapOf<QuicDatagramRouter.Direction, Http3StreamParser>()
    private val exchangeMap = LinkedHashMap<Long, Http3Exchange>()

    /**
     * Observed body totals of exchanges dropped by the [maxTrackedExchanges] bound, keyed by stream, so a
     * stream that speaks again after its exchange was evicted resumes its real cumulative counters rather
     * than restarting at zero (which would make a persisted row's recorded size appear to shrink).
     * Bounded to the same size as the exchange map.
     */
    private val evictedExchanges = LinkedHashMap<Long, EvictedExchangeTotals>()

    /** Body totals carried across an exchange eviction (see [evictedExchanges]). */
    internal class EvictedExchangeTotals(
        val requestBodyBytes: Long,
        val responseBodyBytes: Long,
        val requestBodyTruncated: Boolean,
        val responseBodyTruncated: Boolean,
    )

    /**
     * Destination-Connection-ID length *proven* for a short header in each direction, i.e. the length at
     * which an AEAD actually authenticated. Once proven it is tried first for every later packet.
     */
    private val confirmedDcidLength = mutableMapOf<QuicDatagramRouter.Direction, Int>()

    /**
     * Candidate short-header DCID lengths merely *observed* in a long header's Source Connection ID.
     * A long header is unauthenticated at the point its CIDs are read, so an injected packet can put a
     * wrong length in here; keeping every observation as a candidate (newest first, bounded) means such
     * an injection can at worst cost extra decrypt attempts, never permanently break decryption of a
     * connection whose real length is still in the set.
     */
    private val observedDcidLengths = mutableMapOf<QuicDatagramRouter.Direction, LinkedHashSet<Int>>()

    /**
     * Candidate values for the client's original Destination Connection ID, newest first and bounded.
     * Both directions' Initial secrets derive from this CID (RFC 9001 §5.2) and it is read from an
     * unauthenticated Initial header, so an injected Initial arriving before the genuine one would
     * otherwise permanently prevent the server's Initial — and therefore the ServerHello, and therefore
     * every later level — from decrypting. Each candidate is tried until one authenticates.
     */
    private val originalDcidCandidates = mutableListOf<ByteArray>()

    /**
     * True once [originalDcid] has been proven: a server Initial decrypted under it, or a Retry
     * Integrity Tag authenticated against it. A proven value is never replaced by an unauthenticated
     * observation.
     */
    var originalDcidConfirmed: Boolean = false
        private set

    /** Decoded HTTP/3 exchanges on this connection, in first-seen order. */
    val exchanges: Collection<Http3Exchange> get() = exchangeMap.values

    val trackedStreamCount: Int get() = streamReassemblers.size

    /**
     * Bytes currently buffered across HTTP/3 bodies, stream reassembly and crypto streams — the
     * bounded-memory probe. Reads buffer sizes only; it never copies a body out, so calling it is not a
     * hidden allocation of every buffered payload.
     */
    fun bufferedByteCount(): Int {
        var total = cryptoStreams.values.sumOf { it.size() }
        for (ex in exchangeMap.values) total += ex.requestBodyBuffered + ex.responseBodyBuffered
        for (reassembler in streamReassemblers.values) total += reassembler.bufferedByteCount
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
            correctVersion(routed)
            recordConnectionIdLengths(routed)
            val firstByte = routed.payloadBytes.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            val isLongHeader = (firstByte and 0x80) != 0
            decrypted = when {
                routed.packetType == QuicCrypto.LongHeaderPacketType.INITIAL -> handleInitial(routed)
                routed.packetType == QuicCrypto.LongHeaderPacketType.HANDSHAKE ->
                    handleProtectedLongHeader(routed, Level.HANDSHAKE)
                routed.packetType == QuicCrypto.LongHeaderPacketType.ZERO_RTT ->
                    handleProtectedLongHeader(routed, Level.ZERO_RTT)
                routed.packetType == QuicCrypto.LongHeaderPacketType.RETRY -> handleRetry(routed)
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
     * Adopts the real QUIC version from a long header when the connection was first observed without
     * one (a mid-connection capture that started at a 1-RTT packet reports version `0`) or when the
     * version genuinely changes. Because the version selects the `quic` vs `quicv2` HKDF labels, any
     * key material already derived under the previous value is discarded, so nothing decrypts — or
     * fails to decrypt — under mismatched labels.
     */
    private fun correctVersion(routed: QuicDatagramRouter.RoutedPacket) {
        if (routed.packetType == null) return // short header / version negotiation: no version to trust
        val observed = routed.version
        if (observed == 0 || observed == version) return
        if (versionConfirmed) {
            // The current version is proven by an AEAD that authenticated under it. A header claiming a
            // different one is an injected or misrouted packet, and adopting it would discard working
            // keys and re-derive them under the wrong `quic`/`quicv2` labels -- a decode denial that
            // costs an attacker one spoofed datagram. Ignore it and record that it happened.
            versionMismatchesIgnored += 1
            return
        }
        version = observed
        versionCorrections += 1
        // Only unproven key material is discarded here: nothing has authenticated under the previous
        // value, so nothing of value is lost.
        levelKeys.clear()
        keyPhaseManagers.clear()
    }

    /** Marks [version] proven after an AEAD authenticated under it. */
    private fun confirmVersion() {
        versionConfirmed = true
    }

    /**
     * Applies a Retry packet (RFC 9000 §17.2.5 / RFC 9001 §5.8).
     *
     * A Retry is only acted on once its 128-bit Retry Integrity Tag authenticates against the client's
     * *original* Destination Connection ID: the tag exists precisely so that "only an entity that
     * observes an Initial packet can send a valid Retry packet", and acting on a spoofed Retry would
     * re-key the connection to an attacker's CID and make every subsequent Initial undecryptable.
     *
     * Once accepted, the client switches its DCID to the Retry's Source Connection ID, so:
     *  - [cidTable] is re-keyed via [QuicCidTable.rekeyAfterRetry] so packets carrying the new CID
     *    route to this same connection while the superseded CID is retired;
     *  - [originalDcid] becomes that new CID, which is what both directions' Initial secrets re-derive
     *    from (RFC 9001 §5.2) — without this the retried Initial exchange, including the ServerHello,
     *    stays undecryptable;
     *  - Initial-level CRYPTO reassembly is reset, because the retried handshake restarts its crypto
     *    stream at offset 0 and the previous coverage would make those bytes look like duplicates;
     *  - the previous ClientHello identity is dropped, since the retried ClientHello carries a new
     *    random and the key log is indexed by it. Packet-number spaces are deliberately *not* reset
     *    (RFC 9000 §17.2.5.3: a client must not restart a packet number space after a Retry).
     */
    private fun handleRetry(routed: QuicDatagramRouter.RoutedPacket): Boolean {
        advanceLifecycle(QuicState.HANDSHAKE_IN_PROGRESS)
        val candidates = originalDcidCandidatesInOrder()
        if (candidates.isEmpty()) return false // no observed client Initial: nothing to verify against
        val packet = routed.payloadBytes
        if (packet.size <= RETRY_INTEGRITY_TAG_LEN) {
            retriesRejected += 1
            return false
        }
        val withoutTag = packet.copyOfRange(0, packet.size - RETRY_INTEGRITY_TAG_LEN)
        val tag = packet.copyOfRange(packet.size - RETRY_INTEGRITY_TAG_LEN, packet.size)
        // Every candidate original DCID is tried, so an injected Initial that supplied a bogus candidate
        // cannot stop a genuine Retry from validating against the real one.
        val authenticated = candidates.firstOrNull { candidate ->
            QuicCrypto.validateRetryIntegrityTag(version, candidate, withoutTag, tag)
        }
        if (authenticated == null) {
            retriesRejected += 1
            return false
        }
        if (routed.scid.isEmpty() || routed.scid.size > MAX_CONNECTION_ID_LEN) {
            // The tag authenticated, but the packet cannot be acted on: a Retry must carry the new
            // connection ID it re-keys to. Counted as rejected rather than silently ignored, so the
            // surfaced counters account for every Retry that did not take effect.
            retriesRejected += 1
            return false
        }
        val odcid = authenticated

        retriesAccepted += 1
        // The tag is proof of authenticity for this CID (RFC 9001 §5.8), so both it and the version it
        // was computed under are now proven.
        confirmVersion()
        // Re-key through the CID table so the Retry's SCID routes to this connection and the superseded
        // CID is retired. If the table never held the original CID (it was evicted, or this observer
        // attached mid-handshake), register the new CID directly rather than silently not routing it.
        if (cidTable.rekeyAfterRetry(odcid, routed.scid) == null) {
            cidTable.addCid(connectionId, routed.scid)
        }
        confirmOriginalDcid(routed.scid)
        // The retried ClientHello carries a new random, so the old identity and anything resolved
        // against it no longer describe this connection.
        clientRandom = null
        clientRandomHex = null
        // The retried exchange uses only the new CID; stale candidates from the superseded exchange must
        // not be tried against it.
        originalDcidCandidates.clear()
        resolvedLabels.clear()
        missingLabels.clear()
        levelKeys.clear()
        keyPhaseManagers.clear()
        // The retried handshake negotiates from scratch, so anything locked from the superseded exchange
        // no longer describes this connection and must be re-read from the retried ServerHello.
        negotiatedCipherSuite = null
        cipherSuiteUnavailable = false
        for (direction in QuicDatagramRouter.Direction.entries) {
            val key = LevelKey(Level.INITIAL, direction)
            cryptoReassemblers.remove(key)
            cryptoStreams.remove(key)
        }
        return true
    }

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
                if (routed.scid.isNotEmpty()) observeDcidLength(QuicDatagramRouter.Direction.INBOUND, routed.scid.size)
                if (routed.packetType == QuicCrypto.LongHeaderPacketType.INITIAL) {
                    observeOriginalDcid(routed.dcid, decrypted = routed.decrypted)
                }
            }
            QuicDatagramRouter.Direction.INBOUND -> {
                // The server's SCID is what the client addresses its 1-RTT packets to.
                if (routed.scid.isNotEmpty()) observeDcidLength(QuicDatagramRouter.Direction.OUTBOUND, routed.scid.size)
            }
        }
    }

    /** Records an unauthenticated short-header DCID-length observation as a bounded candidate. */
    private fun observeDcidLength(direction: QuicDatagramRouter.Direction, length: Int) {
        if (length < 0 || length > MAX_CONNECTION_ID_LEN) return
        val candidates = observedDcidLengths.getOrPut(direction) { LinkedHashSet() }
        candidates.remove(length)
        candidates.add(length) // newest last; tried newest-first below
        while (candidates.size > MAX_DCID_LENGTH_CANDIDATES) {
            val oldest = candidates.firstOrNull() ?: break
            candidates.remove(oldest)
        }
    }

    /**
     * Records a candidate original DCID from an Initial header. [decrypted] is true when that Initial's
     * ClientHello really AEAD-decrypted under keys derived from this very CID, which is meaningful
     * evidence (though Initial keys are public, so it is not proof of authenticity) and therefore
     * promotes the candidate to the working value. A CID already proven by a server-Initial decrypt or a
     * Retry tag is never displaced.
     */
    private fun observeOriginalDcid(dcid: ByteArray, decrypted: Boolean) {
        if (dcid.isEmpty() || dcid.size > MAX_CONNECTION_ID_LEN) return
        if (originalDcidCandidates.none { it.contentEquals(dcid) }) {
            originalDcidCandidates.add(0, dcid.copyOf())
            while (originalDcidCandidates.size > MAX_ORIGINAL_DCID_CANDIDATES) {
                originalDcidCandidates.removeAt(originalDcidCandidates.size - 1)
            }
        }
        if (originalDcidConfirmed) return
        if (originalDcid == null || decrypted) originalDcid = dcid.copyOf()
    }

    /** Promotes [dcid] to the proven original DCID after it authenticated something. */
    private fun confirmOriginalDcid(dcid: ByteArray) {
        originalDcid = dcid.copyOf()
        originalDcidConfirmed = true
    }

    /** Original-DCID candidates in try order: the current working value first, then the rest. */
    private fun originalDcidCandidatesInOrder(): List<ByteArray> {
        val working = originalDcid
        if (working == null) return originalDcidCandidates.toList()
        return listOf(working) + originalDcidCandidates.filterNot { it.contentEquals(working) }
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
                // The ClientHello AEAD authenticated under keys derived from this version's salt, so the
                // version is no longer merely claimed by an unauthenticated header.
                confirmVersion()
                raiseStatus(QuicDecodeStatus.PARTIALLY_DECODED)
                return true
            }
            return false
        }

        // Inbound (server) Initial: derive the server-direction Initial keys from the client's original
        // DCID and decrypt for real. Every candidate CID is tried, because the working value was read from
        // an unauthenticated Initial header and an injected packet may have supplied it; whichever
        // candidate authenticates becomes the proven one.
        val spaceKey = SpaceKey(PacketNumberSpace.INITIAL, routed.direction)
        val largestPn = largestPacketNumbers[spaceKey] ?: -1L
        for (candidate in originalDcidCandidatesInOrder()) {
            val keys = QuicCrypto.deriveServerInitialKeys(version, candidate) ?: continue
            val decryptedInitial = QuicCrypto.decryptInitialPacketAt(
                packet = routed.payloadBytes,
                keys = keys,
                largestPn = largestPn,
            ) ?: continue
            confirmOriginalDcid(candidate)
            confirmVersion()
            recordLargestPacketNumber(spaceKey, decryptedInitial.packetNumber)
            packetsDecrypted++
            raiseStatus(QuicDecodeStatus.PARTIALLY_DECODED)
            processFrames(decryptedInitial.cryptoData, Level.INITIAL, routed.direction)
            return true
        }
        if (originalDcidCandidates.isNotEmpty()) decryptFailures++
        return false
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
        confirmVersion()
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

            // The length that actually authenticated is the connection's real one -- remember it as proven
            // so later packets in this direction try it first.
            confirmedDcidLength[routed.direction] = dcidLen
            confirmVersion()
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

    /**
     * Short-header DCID lengths to try, in order: the proven one, then every unauthenticated observation
     * newest-first, then the lengths real deployments use. A wrong length simply fails AEAD, so keeping
     * fallbacks means neither an injected long header nor a peer rotating to a CID of a different length
     * can permanently prevent this connection from decrypting.
     */
    private fun candidateDcidLengths(direction: QuicDatagramRouter.Direction): List<Int> {
        val ordered = LinkedHashSet<Int>()
        confirmedDcidLength[direction]?.let(ordered::add)
        // `toList().asReversed()` on purpose: LinkedHashSet.reversed() is Java 21's SequencedCollection
        // API, which resolves at compile time but does not exist on the JDK 17 runtime this project targets.
        observedDcidLengths[direction]?.toList()?.asReversed()?.forEach(ordered::add)
        ordered.addAll(FALLBACK_DCID_LENGTHS)
        return ordered.toList()
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
        // A stream that already reached its FIN and was fully drained can never contribute another byte;
        // a retransmission after that point is dropped rather than re-parsed into duplicate HTTP/3 data.
        if (key in completedStreams) return
        val reassembler = streamReassemblers[key] ?: run {
            if (streamReassemblers.size >= maxTrackedStreams) evictStreamVictim()
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
        if (reassembler.isComplete) {
            // Release the reassembler the moment the stream is finished, instead of holding its coverage
            // and FIN state until the whole connection is evicted.
            streamReassemblers.remove(key)
            rememberCompleted(key)
            markStreamComplete(frame.streamId, direction)
        }
    }

    /** Records [key] as finished, bounded oldest-first so the set cannot grow with the connection. */
    private fun rememberCompleted(key: StreamKey) {
        completedStreams.add(key)
        while (completedStreams.size > maxTrackedStreams * 2) {
            val oldest = completedStreams.firstOrNull() ?: break
            completedStreams.remove(oldest)
        }
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
            victim?.let {
                rememberEvictedExchange(it.key, it.value)
                exchangeMap.remove(it.key)
            }
        }
        val created = Http3Exchange(streamId, clock())
        // A stream whose exchange was evicted and then spoke again must not look like it started from
        // zero bytes: the earlier bytes were genuinely observed, they are simply no longer buffered. The
        // recreated exchange therefore continues the observed byte counters and is marked truncated,
        // which keeps the size it reports consistent with the (shorter) body it can still produce.
        evictedExchanges.remove(streamId)?.let(created::seedFromEvicted)
        exchangeMap[streamId] = created
        return created
    }

    /** Records an evicted exchange's observed byte totals so a later recreation continues from them. */
    private fun rememberEvictedExchange(streamId: Long, exchange: Http3Exchange) {
        evictedExchanges[streamId] = EvictedExchangeTotals(
            requestBodyBytes = exchange.requestBodyBytes,
            responseBodyBytes = exchange.responseBodyBytes,
            requestBodyTruncated = exchange.requestBodyTruncated || exchange.requestBodyBuffered > 0,
            responseBodyTruncated = exchange.responseBodyTruncated || exchange.responseBodyBuffered > 0,
        )
        while (evictedExchanges.size > maxTrackedExchanges) {
            val oldest = evictedExchanges.keys.firstOrNull() ?: break
            evictedExchanges.remove(oldest)
        }
    }

    private fun touch(exchange: Http3Exchange) {
        exchange.lastUpdatedAt = clock()
        exchange.touchRevision()
        touchedExchanges.add(exchange)
    }

    /**
     * Frees one stream reassembler when the per-connection cap is reached, preferring victims that
     * cannot lose data: first a finished stream (FIN drained), then one holding no buffered bytes, and
     * only then the oldest. Dropping a stream with buffered out-of-order bytes loses those bytes, so it
     * is the last resort rather than the default.
     */
    private fun evictStreamVictim() {
        val victim = streamReassemblers.entries.firstOrNull { it.value.isComplete }
            ?: streamReassemblers.entries.firstOrNull { it.value.pendingSegmentCount == 0 }
            ?: streamReassemblers.entries.firstOrNull()
            ?: return
        streamReassemblers.remove(victim.key)
        if (victim.value.isComplete) rememberCompleted(victim.key)
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

    /**
     * Rank of [decodeStatus] for the monotonic persisted comparison. Exposed because SQLite cannot rank
     * a Kotlin enum, so the persist path passes this alongside the value and the SQL CASE ladder in
     * [com.example.data.local.QuicConnectionDao.updateDecodeState] must agree with it.
     */
    val decodeStatusRank: Int get() = statusRank(decodeStatus)

    /** Rank of [lifecycle] for the same monotonic persisted comparison. */
    val lifecycleStateRank: Int get() = lifecycleRank(lifecycle)

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
        completedStreams.clear()
        observedDcidLengths.clear()
        originalDcidCandidates.clear()
        http3Parsers.clear()
        cryptoReassemblers.clear()
        cryptoStreams.clear()
        levelKeys.clear()
        keyPhaseManagers.clear()
        exchangeMap.clear()
        evictedExchanges.clear()
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

        /** RFC 9001 §5.8: a Retry packet's trailing Retry Integrity Tag is always 128 bits. */
        private const val RETRY_INTEGRITY_TAG_LEN = 16

        /** RFC 9000 §17.2: a connection ID is at most 20 bytes. */
        private const val MAX_CONNECTION_ID_LEN = 20

        /** Bound on unauthenticated short-header DCID-length candidates retained per direction. */
        private const val MAX_DCID_LENGTH_CANDIDATES = 4

        /** Bound on unauthenticated original-DCID candidates retained for a connection. */
        private const val MAX_ORIGINAL_DCID_CANDIDATES = 4

        /** Short-header DCID lengths to try when the peer's SCID length was never observed. */
        private val FALLBACK_DCID_LENGTHS = listOf(8, 0)

        /** Per-connection bounds — a hostile or very busy peer must not grow this state without limit. */
        const val MAX_TRACKED_STREAMS = 64
        const val MAX_TRACKED_EXCHANGES = 64
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_CRYPTO_BYTES = 32 * 1024
    }
}
