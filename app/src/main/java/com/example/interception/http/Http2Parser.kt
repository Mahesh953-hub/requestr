package com.example.interception.http

import android.util.Log

/**
 * HTTP/2 frame parser and HPACK decoder (RFC 7540 / RFC 7541).
 * Parses the plaintext frame stream that appears after MITM TLS termination.
 *
 * States: Frame parsing → HPACK decompression → Headers/Data extraction
 *
 * v5.4 (gRPC support): full RFC 7541 static table (61 entries), real HPACK
 * Huffman decoding (Appendix B), an optional per-direction dynamic table
 * ([HpackDecoder]) so literal-with-incremental-indexing references resolve
 * across frames, pseudo-header routing by `:` prefix, and the RFC 7540
 * 2^24-1 frame-length ceiling (large gRPC DATA frames no longer stall the
 * parser).
 */
object Http2Parser {
    private const val TAG = "Http2Parser"

    // Frame types
    const val FRAME_DATA = 0x0
    const val FRAME_HEADERS = 0x1
    const val FRAME_PRIORITY = 0x2
    const val FRAME_RST_STREAM = 0x3
    const val FRAME_SETTINGS = 0x4
    const val FRAME_PUSH_PROMISE = 0x5
    const val FRAME_PING = 0x6
    const val FRAME_GOAWAY = 0x7
    const val FRAME_WINDOW_UPDATE = 0x8
    const val FRAME_CONTINUATION = 0x9

    /** RFC 9113 §7 error code names (RST_STREAM / GOAWAY payloads). */
    private val ERROR_CODE_NAMES = arrayOf(
        "NO_ERROR", "PROTOCOL_ERROR", "INTERNAL_ERROR", "FLOW_CONTROL_ERROR", "SETTINGS_TIMEOUT",
        "STREAM_CLOSED", "FRAME_SIZE_ERROR", "REFUSED_STREAM", "CANCEL", "COMPRESSION_ERROR",
        "CONNECT_ERROR", "ENHANCE_YOUR_CALM", "INADEQUATE_SECURITY", "HTTP_1_1_REQUIRED",
    )

    /** Name of the 32-bit error code at the start of [payload] (RST_STREAM), or `0x..` when unknown. */
    fun errorCodeName(payload: ByteArray): String {
        if (payload.size < 4) return "?"
        val code = ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        return ERROR_CODE_NAMES.getOrNull(code) ?: "0x${Integer.toHexString(code)}"
    }

    /** RFC 9113 §6 frame type name for logs/timelines. */
    fun frameTypeName(type: Int): String = when (type) {
        FRAME_DATA -> "DATA"
        FRAME_HEADERS -> "HEADERS"
        FRAME_PRIORITY -> "PRIORITY"
        FRAME_RST_STREAM -> "RST_STREAM"
        FRAME_SETTINGS -> "SETTINGS"
        FRAME_PUSH_PROMISE -> "PUSH_PROMISE"
        FRAME_PING -> "PING"
        FRAME_GOAWAY -> "GOAWAY"
        FRAME_WINDOW_UPDATE -> "WINDOW_UPDATE"
        FRAME_CONTINUATION -> "CONTINUATION"
        else -> "0x${Integer.toHexString(type)}"
    }

    // Flag masks
    const val FLAG_ACK = 0x01
    const val FLAG_END_STREAM = 0x01
    const val FLAG_END_HEADERS = 0x04
    const val FLAG_PADDED = 0x08
    const val FLAG_PRIORITY = 0x20

    /** RFC 7540 §4.2: frame payloads are 24-bit length prefixed. */
    private const val MAX_FRAME_LENGTH = 16777215

    private const val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

    /** Parsed H2 frame */
    data class H2Frame(
        val length: Int,
        val type: Int,
        val flags: Int,
        val streamId: Int,
        val payload: ByteArray,
    ) {
        val typeName get() = when (type) {
            FRAME_DATA -> "DATA"; FRAME_HEADERS -> "HEADERS"
            FRAME_PRIORITY -> "PRIORITY"; FRAME_RST_STREAM -> "RST_STREAM"
            FRAME_SETTINGS -> "SETTINGS"; FRAME_PUSH_PROMISE -> "PUSH_PROMISE"
            FRAME_PING -> "PING"; FRAME_GOAWAY -> "GOAWAY"
            FRAME_WINDOW_UPDATE -> "WINDOW_UPDATE"; FRAME_CONTINUATION -> "CONTINUATION"
            else -> "UNKNOWN($type)"
        }

        val endStream: Boolean get() = (flags and FLAG_END_STREAM) != 0
        val endHeaders: Boolean get() = (flags and FLAG_END_HEADERS) != 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is H2Frame) return false
            return type == other.type && streamId == other.streamId && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * (31 * type + streamId) + payload.contentHashCode()
    }

    /**
     * Parsed header block from an HPACK-decompressed HEADERS frame.
     *
     * S3 task 20: [entries] is the primary representation -- every field in wire order with
     * duplicates preserved (repeated `set-cookie` is the norm on h2, and RFC 9113 §8.2.3 only
     * folds `cookie`). [pseudoHeaders]/[regularHeaders] are derived last-wins lookup maps kept for
     * the many readers that only need one value per name. Equality is by [entries].
     */
    class H2Headers(
        val streamId: Int,
        val entries: List<Pair<String, String>>,
        val endStream: Boolean,
        /**
         * S3 task 23 (gate G11): the first RFC 7541 decoding violation met in this block, or
         * `null` for a clean decode. [entries] then holds everything decoded before the fault.
         * Observe-only readers may keep using the partial list; a terminating endpoint (task 57)
         * must answer a non-null [error] with `GOAWAY(COMPRESSION_ERROR)` because the peer's
         * dynamic table and ours can no longer be assumed to agree.
         */
        val error: String? = null,
    ) {
        /** Compatibility constructor for callers/tests that still build the two maps. */
        constructor(
            streamId: Int,
            pseudoHeaders: Map<String, String>,
            regularHeaders: Map<String, String>,
            endStream: Boolean,
        ) : this(
            streamId,
            pseudoHeaders.map { it.key to it.value } + regularHeaders.map { it.key to it.value },
            endStream,
        )

        /** `:`-prefixed fields in wire order (last wins on a duplicate). */
        val pseudoHeaders: Map<String, String> = LinkedHashMap<String, String>().also { m ->
            for ((n, v) in entries) if (n.startsWith(":")) m[n] = v
        }

        /** Non-pseudo fields in wire order (last wins on a duplicate -- use [entries] for all values). */
        val regularHeaders: Map<String, String> = LinkedHashMap<String, String>().also { m ->
            for ((n, v) in entries) if (!n.startsWith(":")) m[n] = v
        }

        /** Pseudo-headers overlaid with regular headers for convenient lookup. */
        val all: Map<String, String> get() = pseudoHeaders + regularHeaders

        /** Non-pseudo fields in wire order, duplicates kept. */
        val regularEntries: List<Pair<String, String>> get() = entries.filter { !it.first.startsWith(":") }

        fun copy(
            streamId: Int = this.streamId,
            entries: List<Pair<String, String>> = this.entries,
            endStream: Boolean = this.endStream,
            error: String? = this.error,
        ) = H2Headers(streamId, entries, endStream, error)

        override fun equals(other: Any?): Boolean =
            other is H2Headers && streamId == other.streamId && entries == other.entries && endStream == other.endStream

        override fun hashCode(): Int = 31 * (31 * streamId + entries.hashCode()) + endStream.hashCode()

        override fun toString(): String = "H2Headers(streamId=$streamId, entries=$entries, endStream=$endStream${error?.let { ", error=$it" } ?: ""})"
    }

    /**
     * Per-direction HPACK decoder state (RFC 7541 §4 dynamic table).
     *
     * HPACK contexts are independent per connection direction — a stream's
     * client→server HEADERS must use one instance and server→client another.
     * Stateless callers may omit it; each call then starts from the static
     * table only (dynamic references resolve to "(idxN)" placeholders).
     */
    class HpackDecoder {
        /** Dynamic entries, most-recent first. Index 62 == first element. */
        private val dynamic = ArrayDeque<Pair<String, String>>()
        private var dynamicSize = 0
        private var maxDynamicSize = DEFAULT_TABLE_SIZE

        /**
         * `SETTINGS_HEADER_TABLE_SIZE` we advertised and the peer acknowledged -- the ceiling a
         * dynamic table size update on the wire may request (RFC 7541 §4.2, §6.3).
         */
        var maxHeaderTableSize: Int = DEFAULT_TABLE_SIZE
            private set

        /** HPACK index 62 == most recently inserted dynamic entry (element 0). */
        fun dynamicEntry(hpackIndex: Int): Pair<String, String>? =
            dynamic.elementAtOrNull(hpackIndex - STATIC_TABLE_SIZE - 1)

        /** Number of dynamic entries currently held (tests, diagnostics). */
        val dynamicCount: Int get() = dynamic.size

        /** Current dynamic table size in RFC 7541 §4.1 octets. */
        val size: Int get() = dynamicSize

        /**
         * Apply a new acknowledged `SETTINGS_HEADER_TABLE_SIZE`. The encoder is then required to
         * emit a dynamic table size update at the start of its next block (a shrink takes effect
         * immediately so oversized entries are evicted; a growth only widens the ceiling).
         */
        fun setMaxHeaderTableSize(settingsValue: Int) {
            maxHeaderTableSize = settingsValue
            if (settingsValue < maxDynamicSize) resize(settingsValue)
        }

        fun insert(name: String, value: String) {
            val entrySize = name.length + value.length + 32 // RFC 7541 §4.1
            dynamic.addFirst(name to value)
            dynamicSize += entrySize
            evict()
        }

        fun resize(newMax: Int) {
            maxDynamicSize = newMax
            evict()
        }

        private fun evict() {
            while (dynamicSize > maxDynamicSize && dynamic.isNotEmpty()) {
                val (n, v) = dynamic.removeLast()
                dynamicSize -= n.length + v.length + 32
            }
        }

        companion object { const val DEFAULT_TABLE_SIZE = 4096 }
    }

    /**
     * Parse a byte array containing potentially multiple HTTP/2 frames.
     * Returns list of parsed frames. Consumes only complete frames.
     */
    fun parseFrames(data: ByteArray, offset: Int = 0, len: Int = data.size - offset): List<H2Frame> {
        val frames = mutableListOf<H2Frame>()
        var pos = offset
        val end = offset + len

        while (pos + 9 <= end) {
            // 9-byte frame header: 3 length + 1 type + 1 flags + 4 stream-id (lower 31 bits)
            val frameLen = ((data[pos].toInt() and 0xFF) shl 16) or
                           ((data[pos + 1].toInt() and 0xFF) shl 8) or
                           (data[pos + 2].toInt() and 0xFF)
            val type = data[pos + 3].toInt() and 0xFF
            val flags = data[pos + 4].toInt() and 0xFF
            // RFC 9113 §4.1: the high bit of the stream identifier is reserved and MUST be
            // ignored on receipt -- without the mask a peer setting it yields a negative id.
            val streamId = (((data[pos + 5].toInt() and 0xFF) shl 24) or
                           ((data[pos + 6].toInt() and 0xFF) shl 16) or
                           ((data[pos + 7].toInt() and 0xFF) shl 8) or
                           (data[pos + 8].toInt() and 0xFF)) and 0x7FFFFFFF

            if (frameLen < 0 || frameLen > MAX_FRAME_LENGTH || pos + 9 + frameLen > end) break

            val payload = data.copyOfRange(pos + 9, pos + 9 + frameLen)
            frames.add(H2Frame(frameLen, type, flags, streamId, payload))
            pos += 9 + frameLen
        }
        return frames
    }

    /**
     * Decode a SETTINGS frame payload (RFC 7540 §6.5) into its `(id, value)` pairs, in the
     * order they appeared on the wire. An ACK'd SETTINGS frame (`FLAG_ACK`) carries no payload
     * and decodes to an empty list, which callers should treat as "no new settings", not
     * "settings cleared".
     *
     * Used by [com.example.runtime.http.StackFingerprinter] (W4): the exact `(id, value)`
     * multiset a client sends on stream 0 is a strong per-library fingerprint (verified against
     * OkHttp 4.x, which sends exactly one setting, `INITIAL_WINDOW_SIZE(7) = 16777216`) — no
     * production call site needed this before W4 because [CaptureEngine] never inspected
     * SETTINGS frame bytes, only routed on the frame *type* being present.
     */
    fun decodeSettings(frame: H2Frame): List<Pair<Int, Int>> {
        if (frame.type != FRAME_SETTINGS || (frame.flags and FLAG_ACK) != 0) return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        var pos = 0
        val payload = frame.payload
        while (pos + 6 <= payload.size) {
            val id = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
            val value = ((payload[pos + 2].toInt() and 0xFF) shl 24) or
                ((payload[pos + 3].toInt() and 0xFF) shl 16) or
                ((payload[pos + 4].toInt() and 0xFF) shl 8) or
                (payload[pos + 5].toInt() and 0xFF)
            out.add(id to value)
            pos += 6
        }
        return out
    }

    // RFC 7540 §11.3 SETTINGS parameter identifiers, as they appear on the wire (this is
    // what decodeSettings() above decodes). Not to be confused with any library's own
    // internal array-index numbering for the same parameters.
    const val SETTINGS_HEADER_TABLE_SIZE = 1
    const val SETTINGS_ENABLE_PUSH = 2
    const val SETTINGS_MAX_CONCURRENT_STREAMS = 3
    const val SETTINGS_INITIAL_WINDOW_SIZE = 4
    const val SETTINGS_MAX_FRAME_SIZE = 5
    const val SETTINGS_MAX_HEADER_LIST_SIZE = 6

    /**
     * Check if the data starts with the HTTP/2 connection preface.
     */
    fun isPreface(data: ByteArray): Boolean {
        val pre = PREFACE.toByteArray()
        if (data.size < pre.size) return false
        for (i in pre.indices) if (data[i] != pre[i]) return false
        return true
    }

    /** Byte length of the client connection preface (for draining). */
    val PREFACE_LENGTH: Int get() = PREFACE.toByteArray().size

    /**
     * Strips RFC 7540 §6.1 DATA-frame padding from [frame]'s payload, returning only the real
     * data octets. [decodeHeaders] already strips padding inline for HEADERS/PUSH_PROMISE
     * frames; DATA frames need the equivalent applied by the caller *before* the bytes reach
     * HTTP/gRPC body reassembly ([Http2Session.onDataFrame]) -- padding is attacker/server
     * controlled filler (RFC 7540 §10.7 notes it exists purely to obscure payload sizes), not
     * part of the body, and letting it flow into `requestBody`/`responseBody`/gRPC framing
     * would corrupt the decoded body and (for gRPC) desync the 5-byte length-prefix framing.
     *
     * No-op (returns [H2Frame.payload] unchanged) when `FLAG_PADDED` is not set. Never throws:
     * a malformed pad-length (claiming more bytes than the frame actually holds) degrades to an
     * empty result rather than an out-of-bounds read.
     */
    fun stripDataPadding(frame: H2Frame): ByteArray {
        val payload = frame.payload
        if ((frame.flags and FLAG_PADDED) == 0 || payload.isEmpty()) return payload
        val padLen = payload[0].toInt() and 0xFF
        val dataEnd = payload.size - padLen
        if (dataEnd < 1) return ByteArray(0)
        return payload.copyOfRange(1, dataEnd)
    }

    /**
     * Heuristic: do these bytes begin with a plausible first HTTP/2 frame?
     * RFC 7540 §3.5 mandates SETTINGS (stream 0) as the first frame each
     * endpoint sends — used to spot h2 when the preface was already consumed
     * (e.g. mid-stream MITM decryption).
     */
    fun looksLikeFrameStart(data: ByteArray): Boolean {
        if (data.size < 9) return false
        val type = data[3].toInt() and 0xFF
        val streamId = ((data[5].toInt() and 0xFF) shl 24) or
            ((data[6].toInt() and 0xFF) shl 16) or
            ((data[7].toInt() and 0xFF) shl 8) or
            (data[8].toInt() and 0xFF)
        return type == FRAME_SETTINGS && streamId == 0
    }

    /**
     * Decompress an HPACK header block (RFC 7541) from a HEADERS/PUSH_PROMISE
     * frame payload (already coalesced with any CONTINUATION frames).
     *
     * @param state per-direction decoder state; pass the same instance for
     *        every header block travelling in one direction of a connection.
     */
    fun decodeHeaders(frame: H2Frame, payload: ByteArray, state: HpackDecoder = HpackDecoder()): H2Headers {
        val entries = ArrayList<Pair<String, String>>()
        var pos = 0
        val end = payload.size
        var error: String? = null

        fun store(name: String, value: String) {
            entries.add(name to value)
        }

        // Skip padding if FLAG_PADDED is set
        var effectiveEnd = end
        if ((frame.flags and FLAG_PADDED) != 0) {
            if (pos < effectiveEnd) {
                val padLen = payload[pos].toInt() and 0xFF
                pos++
                effectiveEnd = end - padLen
                if (effectiveEnd < pos) error = "padding ($padLen) exceeds frame payload" // RFC 9113 §6.2
            } else {
                error = "PADDED flag on empty payload"
            }
        }

        // Skip priority if FLAG_PRIORITY is set
        if (error == null && (frame.flags and FLAG_PRIORITY) != 0) {
            if (pos + 5 <= effectiveEnd) pos += 5 // E bit + stream dependency + weight
            else error = "PRIORITY flag with fewer than 5 octets"
        }

        var fieldsSeen = false
        decode@ while (error == null && pos < effectiveEnd) {
            val firstByte = payload[pos].toInt() and 0xFF
            pos++

            if (firstByte and 0x80 == 0x80) {
                // Indexed header field — 7-bit prefix
                val index = decodeInteger(payload, pos, 7, firstByte, effectiveEnd)
                if (index.second < 0) { error = "indexed field: integer overflow or truncated"; break@decode }
                pos = index.first
                val entry = lookupStrict(index.second, state)
                if (entry == null) { error = "indexed field: invalid index ${index.second}"; break@decode }
                store(entry.first, entry.second)
                fieldsSeen = true
            } else if (firstByte and 0x40 == 0x40) {
                // Literal with incremental indexing — 6-bit prefix name index
                val nameIdx = decodeInteger(payload, pos, 6, firstByte, effectiveEnd)
                if (nameIdx.second < 0) { error = "literal name index: integer overflow or truncated"; break@decode }
                pos = nameIdx.first
                val name: String
                if (nameIdx.second != 0) {
                    val entry = lookupStrict(nameIdx.second, state)
                    if (entry == null) { error = "literal name: invalid index ${nameIdx.second}"; break@decode }
                    name = entry.first
                } else {
                    val (np, n) = decodeString(payload, pos, effectiveEnd)
                    if (n == null) { error = "literal name: bad string literal"; break@decode }
                    pos = np
                    name = n
                }
                val (newPos, value) = decodeString(payload, pos, effectiveEnd)
                if (value == null) { error = "literal value: bad string literal"; break@decode }
                pos = newPos
                store(name, value)
                state.insert(name, value)
                fieldsSeen = true
            } else if (firstByte and 0x20 == 0x20) {
                // Dynamic table size update — 5-bit prefix
                val result = decodeInteger(payload, pos, 5, firstByte, effectiveEnd)
                if (result.second < 0) { error = "table size update: integer overflow or truncated"; break@decode }
                pos = result.first
                if (fieldsSeen) { error = "table size update after a header field (RFC 7541 §4.2)"; break@decode }
                if (result.second > state.maxHeaderTableSize) {
                    error = "table size update ${result.second} exceeds SETTINGS_HEADER_TABLE_SIZE ${state.maxHeaderTableSize}"
                    break@decode
                }
                state.resize(result.second)
            } else {
                // Literal without indexing (0000xxxx) / never indexed (0001xxxx) — 4-bit prefix
                val name: String
                if ((firstByte and 0x0F) != 0) {
                    val res = decodeInteger(payload, pos, 4, firstByte, effectiveEnd)
                    if (res.second < 0) { error = "literal name index: integer overflow or truncated"; break@decode }
                    pos = res.first
                    val entry = lookupStrict(res.second, state)
                    if (entry == null) { error = "literal name: invalid index ${res.second}"; break@decode }
                    name = entry.first
                } else {
                    val (np2, n) = decodeString(payload, pos, effectiveEnd)
                    if (n == null) { error = "literal name: bad string literal"; break@decode }
                    pos = np2
                    name = n
                }
                val (np2, value) = decodeString(payload, pos, effectiveEnd)
                if (value == null) { error = "literal value: bad string literal"; break@decode }
                pos = np2
                store(name, value)
                fieldsSeen = true
            }
        }

        return H2Headers(
            streamId = frame.streamId,
            entries = entries,
            endStream = (frame.flags and FLAG_END_STREAM) != 0,
            error = error,
        )
    }

    /** Resolve a 1-based HPACK index against the static + dynamic tables; `null` when out of range. */
    private fun lookupStrict(index: Int, state: HpackDecoder): Pair<String, String>? =
        if (index in 1..STATIC_TABLE_SIZE) getStaticEntry(index)
        else if (index > STATIC_TABLE_SIZE) state.dynamicEntry(index)
        else null

    /**
     * RFC 7541 §5.1 integer. Returns `(next offset, value)`, or a negative value when the
     * continuation bytes run past [end] or the value would exceed [MAX_HPACK_INTEGER]
     * (unbounded `1111 1111 ...` sequences are a classic decoder DoS -- cap instead of wrapping).
     */
    private fun decodeInteger(bytes: ByteArray, offset: Int, prefixBits: Int, firstByte: Int, end: Int = bytes.size): Pair<Int, Int> {
        val mask = (1 shl prefixBits) - 1
        val prefix = firstByte and mask
        if (prefix < mask) return offset to prefix
        var result = mask.toLong()
        var m = 0
        var pos = offset
        while (true) {
            if (pos >= end) return pos to -1
            val b = bytes[pos].toInt() and 0xFF
            pos++
            if (m > 28) return pos to -1 // more than 5 continuation octets can never fit
            result += (b and 127).toLong() shl m
            if (result > MAX_HPACK_INTEGER) return pos to -1
            if (b and 128 == 0) break
            m += 7
        }
        return pos to result.toInt()
    }

    /** Largest integer any HPACK field may carry (index, length or table size): 2^28 - 1. */
    private const val MAX_HPACK_INTEGER = (1 shl 28) - 1

    /**
     * Decode an HPACK string literal at [offset] (never reading past [end]).
     * @return (offset past the string) to decoded text, or `null` text when the literal is
     *         malformed (bad length, or an invalid Huffman encoding -- see [HpackHuffman.decodeStrict]).
     *         Octets become text via [HpackHuffman.octetsToString] (UTF-8 when valid, else
     *         ISO-8859-1) for raw and Huffman literals alike.
     */
    private fun decodeString(bytes: ByteArray, offset: Int, end: Int = bytes.size): Pair<Int, String?> {
        if (offset >= end) return offset to null
        val firstByte = bytes[offset].toInt() and 0xFF
        val huffman = firstByte and 0x80 == 0x80
        val (pos, len) = decodeInteger(bytes, offset + 1, 7, firstByte, end)
        if (len < 0 || pos + len > end) return end to null
        return if (huffman) {
            (pos + len) to HpackHuffman.decodeStrict(bytes, pos, len)
        } else {
            (pos + len) to HpackHuffman.octetsToString(bytes, pos, len)
        }
    }

    // Huffman decoding (RFC 7541 Appendix B) lives in [HpackHuffman] (v5.6/P2) so
    // [com.example.interception.quic.QpackDecoder] can reuse the identical table that
    // RFC 9204 §4.1.2 mandates for QPACK, instead of duplicating ~180 lines of code tables.

    /** Full RFC 7541 Appendix A static table (61 entries). */
    internal const val STATIC_TABLE_SIZE = 61

    /** S3 task 57: static table as an indexable list (element 0 == HPACK index 1) for [HpackEncoder]. */
    internal val STATIC_TABLE: List<Pair<String, String>> = (1..STATIC_TABLE_SIZE).map { getStaticEntry(it) }

    private fun getStaticEntry(index: Int): Pair<String, String> = when (index) {
        1 -> ":authority" to ""
        2 -> ":method" to "GET"
        3 -> ":method" to "POST"
        4 -> ":path" to "/"
        5 -> ":path" to "/index.html"
        6 -> ":scheme" to "http"
        7 -> ":scheme" to "https"
        8 -> ":status" to "200"
        9 -> ":status" to "204"
        10 -> ":status" to "206"
        11 -> ":status" to "304"
        12 -> ":status" to "400"
        13 -> ":status" to "404"
        14 -> ":status" to "500"
        15 -> "accept-charset" to ""
        16 -> "accept-encoding" to "gzip, deflate"
        17 -> "accept-language" to ""
        18 -> "accept-ranges" to ""
        19 -> "accept" to ""
        20 -> "access-control-allow-origin" to ""
        21 -> "age" to ""
        22 -> "allow" to ""
        23 -> "authorization" to ""
        24 -> "cache-control" to ""
        25 -> "content-disposition" to ""
        26 -> "content-encoding" to ""
        27 -> "content-language" to ""
        28 -> "content-length" to ""
        29 -> "content-location" to ""
        30 -> "content-range" to ""
        31 -> "content-type" to ""
        32 -> "cookie" to ""
        33 -> "date" to ""
        34 -> "etag" to ""
        35 -> "expect" to ""
        36 -> "expires" to ""
        37 -> "from" to ""
        38 -> "host" to ""
        39 -> "if-match" to ""
        40 -> "if-modified-since" to ""
        41 -> "if-none-match" to ""
        42 -> "if-range" to ""
        43 -> "if-unmodified-since" to ""
        44 -> "last-modified" to ""
        45 -> "link" to ""
        46 -> "location" to ""
        47 -> "max-forwards" to ""
        48 -> "proxy-authenticate" to ""
        49 -> "proxy-authorization" to ""
        50 -> "range" to ""
        51 -> "referer" to ""
        52 -> "refresh" to ""
        53 -> "retry-after" to ""
        54 -> "server" to ""
        55 -> "set-cookie" to ""
        56 -> "strict-transport-security" to ""
        57 -> "transfer-encoding" to ""
        58 -> "user-agent" to ""
        59 -> "vary" to ""
        60 -> "via" to ""
        61 -> "www-authenticate" to ""
        else -> "(idx$index)" to ""
    }
}
