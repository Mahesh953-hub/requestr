package com.example.interception.http

import android.util.Log

/**
 * HTTP/2 frame parser and HPACK decoder (RFC 7540 / RFC 7541).
 * Parses the plaintext frame stream that appears after MITM TLS termination.
 *
 * States: Frame parsing → HPACK decompression → Headers/Data extraction
 */
object Http2Parser {
    private const val TAG = "Http2Parser"

    // Frame types
    private const val FRAME_DATA = 0x0
    private const val FRAME_HEADERS = 0x1
    private const val FRAME_PRIORITY = 0x2
    private const val FRAME_RST_STREAM = 0x3
    private const val FRAME_SETTINGS = 0x4
    private const val FRAME_PUSH_PROMISE = 0x5
    private const val FRAME_PING = 0x6
    private const val FRAME_GOAWAY = 0x7
    private const val FRAME_WINDOW_UPDATE = 0x8
    private const val FRAME_CONTINUATION = 0x9

    // Flag masks
    private const val FLAG_ACK = 0x01
    private const val FLAG_END_STREAM = 0x01
    private const val FLAG_END_HEADERS = 0x04
    private const val FLAG_PADDED = 0x08
    private const val FLAG_PRIORITY = 0x20

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
    )

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

            if (frameLen < 0 || frameLen > 16384 || pos + 9 + frameLen > end) break

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

    /**
     * Decompress an HPACK header block (RFC 7541) from a HEADERS/PUSH_PROMISE frame payload.
     * Uses a minimal static table (most common headers) + basic integer decoding.
     */
    fun decodeHeaders(frame: H2Frame, payload: ByteArray): H2Headers {
        val pseudoHeaders = mutableMapOf<String, String>()
        val regularHeaders = mutableMapOf<String, String>()
        var pos = 0
        val end = payload.size

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
                // Indexed header field
                val index = decodeInteger(payload, pos, 7, firstByte)
                pos = index.first
                val (name, value) = getStaticEntry(index.second)
                if (index.second in 1..7) pseudoHeaders[name] = value
                else regularHeaders[name] = value
            } else if (firstByte and 0x40 == 0x40) {
                // Literal with incremental indexing — skip for now
                val nameIdx = decodeInteger(payload, pos, 6, firstByte)
                pos = nameIdx.first
                val (name, _) = getStaticEntry(nameIdx.second)
                val (newPos, value) = decodeString(payload, pos)
                pos = newPos
                regularHeaders[name] = value
            } else if (firstByte and 0x20 == 0x20) {
                // Dynamic table size update — skip
                val result = decodeInteger(payload, pos, 5, firstByte)
                pos = result.first
            } else {
                // Literal without indexing — name string + value string
                val (np, name) = if ((firstByte and 0x0F) != 0) {
                    val res = decodeInteger(payload, pos, 3, firstByte)
                    val (n, _) = getStaticEntry(res.second)
                    res.first to n
                } else {
                    val (np2, n) = decodeString(payload, pos)
                    np2 to n
                }
                pos = np
                val (np2, value) = decodeString(payload, pos)
                pos = np2
                regularHeaders[name] = value
            }
        }

        return H2Headers(
            streamId = frame.streamId,
            pseudoHeaders = pseudoHeaders,
            regularHeaders = regularHeaders,
            endStream = (frame.flags and FLAG_END_STREAM) != 0
        )
    }

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

    private fun decodeString(bytes: ByteArray, offset: Int): Pair<Int, String> {
        val firstByte = bytes[offset].toInt() and 0xFF
        val (pos, len) = if (firstByte and 0x80 == 0x80) {
            // Huffman encoded — decode7
            val (np, hlen) = decodeInteger(bytes, offset + 1, 7, firstByte)
            np to hlen
        } else {
            // Raw
            val (np, rawLen) = decodeInteger(bytes, offset, 7, firstByte)
            np to rawLen
        }
        return (pos + len) to String(bytes, pos, len, Charsets.UTF_8)
    }

    /** Minimal HPACK static table — the 8 most common entries */
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
        else -> "(idx$index)" to ""
    }
}
