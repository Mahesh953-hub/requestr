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

    /** Parsed header block from HPACK-decompressed HEADERS frame */
    data class H2Headers(
        val streamId: Int,
        val pseudoHeaders: Map<String, String>,
        val regularHeaders: Map<String, String>,
        val endStream: Boolean,
    ) {
        /** Pseudo-headers overlaid with regular headers for convenient lookup. */
        val all: Map<String, String> get() = pseudoHeaders + regularHeaders
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

        /** HPACK index 62 == most recently inserted dynamic entry (element 0). */
        fun dynamicEntry(hpackIndex: Int): Pair<String, String>? =
            dynamic.elementAtOrNull(hpackIndex - STATIC_TABLE_SIZE - 1)

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
            val streamId = ((data[pos + 5].toInt() and 0xFF) shl 24) or
                           ((data[pos + 6].toInt() and 0xFF) shl 16) or
                           ((data[pos + 7].toInt() and 0xFF) shl 8) or
                           (data[pos + 8].toInt() and 0xFF)

            if (frameLen < 0 || frameLen > MAX_FRAME_LENGTH || pos + 9 + frameLen > end) break

            val payload = data.copyOfRange(pos + 9, pos + 9 + frameLen)
            frames.add(H2Frame(frameLen, type, flags, streamId, payload))
            pos += 9 + frameLen
        }
        return frames
    }

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
        val pseudoHeaders = mutableMapOf<String, String>()
        val regularHeaders = mutableMapOf<String, String>()
        var pos = 0
        val end = payload.size

        fun store(name: String, value: String) {
            if (name.startsWith(":")) pseudoHeaders[name] = value else regularHeaders[name] = value
        }

        // Skip padding if FLAG_PADDED is set
        var effectiveEnd = end
        if ((frame.flags and FLAG_PADDED) != 0 && pos < effectiveEnd) {
            val padLen = payload[pos].toInt() and 0xFF
            pos++
            effectiveEnd = end - padLen
        }

        // Skip priority if FLAG_PRIORITY is set
        if ((frame.flags and FLAG_PRIORITY) != 0 && pos + 5 <= effectiveEnd) {
            pos += 5 // E bit + stream dependency + weight
        }

        while (pos < effectiveEnd) {
            val firstByte = payload[pos].toInt() and 0xFF
            pos++

            if (firstByte and 0x80 == 0x80) {
                // Indexed header field — 7-bit prefix
                val index = decodeInteger(payload, pos, 7, firstByte)
                pos = index.first
                val (name, value) = lookup(index.second, state)
                store(name, value)
            } else if (firstByte and 0x40 == 0x40) {
                // Literal with incremental indexing — 6-bit prefix name index
                val nameIdx = decodeInteger(payload, pos, 6, firstByte)
                pos = nameIdx.first
                val name: String
                if (nameIdx.second != 0) {
                    name = lookup(nameIdx.second, state).first
                } else {
                    val (np, n) = decodeString(payload, pos)
                    pos = np
                    name = n
                }
                val (newPos, value) = decodeString(payload, pos)
                pos = newPos
                store(name, value)
                state.insert(name, value)
            } else if (firstByte and 0x20 == 0x20) {
                // Dynamic table size update — 5-bit prefix
                val result = decodeInteger(payload, pos, 5, firstByte)
                pos = result.first
                state.resize(result.second)
            } else {
                // Literal without indexing (0000xxxx) / never indexed (0001xxxx) — 4-bit prefix
                val name: String
                if ((firstByte and 0x0F) != 0) {
                    val res = decodeInteger(payload, pos, 4, firstByte)
                    pos = res.first
                    name = lookup(res.second, state).first
                } else {
                    val (np2, n) = decodeString(payload, pos)
                    pos = np2
                    name = n
                }
                val (np2, value) = decodeString(payload, pos)
                pos = np2
                store(name, value)
            }
        }

        return H2Headers(
            streamId = frame.streamId,
            pseudoHeaders = pseudoHeaders,
            regularHeaders = regularHeaders,
            endStream = (frame.flags and FLAG_END_STREAM) != 0
        )
    }

    /** Resolve a 1-based HPACK index against the static + dynamic tables. */
    private fun lookup(index: Int, state: HpackDecoder): Pair<String, String> =
        if (index in 1..STATIC_TABLE_SIZE) getStaticEntry(index)
        else state.dynamicEntry(index) ?: ("(idx$index)" to "")

    private fun decodeInteger(bytes: ByteArray, offset: Int, prefixBits: Int, firstByte: Int): Pair<Int, Int> {
        val mask = (1 shl prefixBits) - 1
        val prefix = firstByte and mask
        if (prefix < mask) return offset to prefix
        var result = mask
        var m = 0
        var pos = offset
        while (true) {
            if (pos >= bytes.size) return pos to result
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result += (b and 127) shl m
            if (b and 128 == 0) break
            m += 7
        }
        return pos to result
    }

    /**
     * Decode an HPACK string literal at [offset].
     * @return (offset past the string) to decoded text. Huffman-coded strings
     *         (H bit set) are decoded with the RFC 7541 Appendix B code.
     */
    private fun decodeString(bytes: ByteArray, offset: Int): Pair<Int, String> {
        if (offset >= bytes.size) return offset to ""
        val firstByte = bytes[offset].toInt() and 0xFF
        val huffman = firstByte and 0x80 == 0x80
        val (pos, len) = decodeInteger(bytes, offset + 1, 7, firstByte)
        if (len < 0 || pos + len > bytes.size) return bytes.size to ""
        return if (huffman) {
            (pos + len) to HpackHuffman.decode(bytes, pos, len)
        } else {
            (pos + len) to String(bytes, pos, len, Charsets.UTF_8)
        }
    }

    // Huffman decoding (RFC 7541 Appendix B) lives in [HpackHuffman] (v5.6/P2) so
    // [com.example.interception.quic.QpackDecoder] can reuse the identical table that
    // RFC 9204 §4.1.2 mandates for QPACK, instead of duplicating ~180 lines of code tables.

    /** Full RFC 7541 Appendix A static table (61 entries). */
    private const val STATIC_TABLE_SIZE = 61

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
