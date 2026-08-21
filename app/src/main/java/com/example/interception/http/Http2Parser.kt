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
            (pos + len) to huffmanDecode(bytes, pos, len)
        } else {
            (pos + len) to String(bytes, pos, len, Charsets.UTF_8)
        }
    }

    // ── HPACK Huffman decoding (RFC 7541 Appendix B) ────────────────────

    /** Huffman code values for symbols 0..255, right-aligned (EOS excluded). */
    private val HUFFMAN_CODES = intArrayOf(
        0x00001ff8, 0x007fffd8, 0x0fffffe2, 0x0fffffe3, 0x0fffffe4, 0x0fffffe5, 0x0fffffe6, 0x0fffffe7, 0x0fffffe8, 0x00ffffea, 0x3ffffffc, 0x0fffffe9,
        0x0fffffea, 0x3ffffffd, 0x0fffffeb, 0x0fffffec, 0x0fffffed, 0x0fffffee, 0x0fffffef, 0x0ffffff0, 0x0ffffff1, 0x0ffffff2, 0x3ffffffe, 0x0ffffff3,
        0x0ffffff4, 0x0ffffff5, 0x0ffffff6, 0x0ffffff7, 0x0ffffff8, 0x0ffffff9, 0x0ffffffa, 0x0ffffffb, 0x00000014, 0x000003f8, 0x000003f9, 0x00000ffa,
        0x00001ff9, 0x00000015, 0x000000f8, 0x000007fa, 0x000003fa, 0x000003fb, 0x000000f9, 0x000007fb, 0x000000fa, 0x00000016, 0x00000017, 0x00000018,
        0x00000000, 0x00000001, 0x00000002, 0x00000019, 0x0000001a, 0x0000001b, 0x0000001c, 0x0000001d, 0x0000001e, 0x0000001f, 0x0000005c, 0x000000fb,
        0x00007ffc, 0x00000020, 0x00000ffb, 0x000003fc, 0x00001ffa, 0x00000021, 0x0000005d, 0x0000005e, 0x0000005f, 0x00000060, 0x00000061, 0x00000062,
        0x00000063, 0x00000064, 0x00000065, 0x00000066, 0x00000067, 0x00000068, 0x00000069, 0x0000006a, 0x0000006b, 0x0000006c, 0x0000006d, 0x0000006e,
        0x0000006f, 0x00000070, 0x00000071, 0x00000072, 0x000000fc, 0x00000073, 0x000000fd, 0x00001ffb, 0x0007fff0, 0x00001ffc, 0x00003ffc, 0x00000022,
        0x00007ffd, 0x00000003, 0x00000023, 0x00000004, 0x00000024, 0x00000005, 0x00000025, 0x00000026, 0x00000027, 0x00000006, 0x00000074, 0x00000075,
        0x00000028, 0x00000029, 0x0000002a, 0x00000007, 0x0000002b, 0x00000076, 0x0000002c, 0x00000008, 0x00000009, 0x0000002d, 0x00000077, 0x00000078,
        0x00000079, 0x0000007a, 0x0000007b, 0x00007ffe, 0x000007fc, 0x00003ffd, 0x00001ffd, 0x0ffffffc, 0x000fffe6, 0x003fffd2, 0x000fffe7, 0x000fffe8,
        0x003fffd3, 0x003fffd4, 0x003fffd5, 0x007fffd9, 0x003fffd6, 0x007fffda, 0x007fffdb, 0x007fffdc, 0x007fffdd, 0x007fffde, 0x00ffffeb, 0x007fffdf,
        0x00ffffec, 0x00ffffed, 0x003fffd7, 0x007fffe0, 0x00ffffee, 0x007fffe1, 0x007fffe2, 0x007fffe3, 0x007fffe4, 0x001fffdc, 0x003fffd8, 0x007fffe5,
        0x003fffd9, 0x007fffe6, 0x007fffe7, 0x00ffffef, 0x003fffda, 0x001fffdd, 0x000fffe9, 0x003fffdb, 0x003fffdc, 0x007fffe8, 0x007fffe9, 0x001fffde,
        0x007fffea, 0x003fffdd, 0x003fffde, 0x00fffff0, 0x001fffdf, 0x003fffdf, 0x007fffeb, 0x007fffec, 0x001fffe0, 0x001fffe1, 0x003fffe0, 0x001fffe2,
        0x007fffed, 0x003fffe1, 0x007fffee, 0x007fffef, 0x000fffea, 0x003fffe2, 0x003fffe3, 0x003fffe4, 0x007ffff0, 0x003fffe5, 0x003fffe6, 0x007ffff1,
        0x03ffffe0, 0x03ffffe1, 0x000fffeb, 0x0007fff1, 0x003fffe7, 0x007ffff2, 0x003fffe8, 0x01ffffec, 0x03ffffe2, 0x03ffffe3, 0x03ffffe4, 0x07ffffde,
        0x07ffffdf, 0x03ffffe5, 0x00fffff1, 0x01ffffed, 0x0007fff2, 0x001fffe3, 0x03ffffe6, 0x07ffffe0, 0x07ffffe1, 0x03ffffe7, 0x07ffffe2, 0x00fffff2,
        0x001fffe4, 0x001fffe5, 0x03ffffe8, 0x03ffffe9, 0x0ffffffd, 0x07ffffe3, 0x07ffffe4, 0x07ffffe5, 0x000fffec, 0x00fffff3, 0x000fffed, 0x001fffe6,
        0x003fffe9, 0x001fffe7, 0x001fffe8, 0x007ffff3, 0x003fffea, 0x003fffeb, 0x01ffffee, 0x01ffffef, 0x00fffff4, 0x00fffff5, 0x03ffffea, 0x007ffff4,
        0x03ffffeb, 0x07ffffe6, 0x03ffffec, 0x03ffffed, 0x07ffffe7, 0x07ffffe8, 0x07ffffe9, 0x07ffffea, 0x07ffffeb, 0x0ffffffe, 0x07ffffec, 0x07ffffed,
        0x07ffffee, 0x07ffffef, 0x07fffff0, 0x03ffffee
    )

    /** Bit lengths of [HUFFMAN_CODES] for symbols 0..255. */
    private val HUFFMAN_BITS = intArrayOf(
        13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28,
        28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 30, 28,
        28, 28, 28, 28, 28, 28, 28, 28, 6, 10, 10, 12,
        13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6,
        5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8,
        15, 6, 12, 10, 13, 6, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6,
        15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7,
        6, 6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7,
        7, 7, 7, 15, 11, 14, 13, 28, 20, 22, 20, 20,
        22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23,
        24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23,
        22, 23, 23, 24, 22, 21, 20, 22, 22, 23, 23, 21,
        23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21,
        23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
        26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27,
        27, 26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24,
        21, 21, 26, 26, 28, 27, 27, 27, 20, 24, 20, 21,
        22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
        26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27,
        27, 27, 27, 26
    )

    /** Binary decode tree over [HUFFMAN_CODES]/[HUFFMAN_BITS], built once. */
    private class HuffmanNode {
        var symbol = -1
        val child = arrayOfNulls<HuffmanNode>(2)
    }

    private val huffmanRoot: HuffmanNode by lazy {
        val root = HuffmanNode()
        for (sym in 0..255) {
            val code = HUFFMAN_CODES[sym]
            val bits = HUFFMAN_BITS[sym]
            var node = root
            for (i in bits - 1 downTo 0) {
                val bit = (code ushr i) and 1
                var next = node.child[bit]
                if (next == null) {
                    next = HuffmanNode()
                    node.child[bit] = next
                }
                node = next
            }
            node.symbol = sym
        }
        root
    }

    /**
     * Decode a Huffman-coded string (RFC 7541 §5.2). Padding of up to 7
     * trailing one-bits (an EOS prefix) is tolerated; a walk that hits an
     * undefined path stops decoding and returns what was decoded so far
     * (best effort — capture must not crash on malformed input).
     */
    private fun huffmanDecode(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        var node = huffmanRoot
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            for (bitIdx in 7 downTo 0) {
                val bit = (b ushr bitIdx) and 1
                val next = node.child[bit] ?: return sb.toString() // invalid path
                node = next
                if (node.symbol >= 0) {
                    sb.append(node.symbol.toChar())
                    node = huffmanRoot
                }
            }
        }
        // Trailing partial path = all-ones padding (< 8 bits) — ignored.
        return sb.toString()
    }

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
