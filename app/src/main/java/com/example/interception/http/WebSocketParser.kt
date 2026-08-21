package com.example.interception.http

import android.util.Log

/**
 * RFC 6455 WebSocket frame parser.
 *
 * Detects the HTTP Upgrade handshake and parses subsequent binary frames
 * into (opcode, payload, direction) records for display in the capture log.
 *
 * Frame layout:
 *   byte 0: FIN(1) RSV(3) OPCODE(4)
 *   byte 1: MASK(1) PAYLOAD_LEN(7)
 *   bytes 2-3 (if len==126): 16-bit extended length
 *   bytes 2-9 (if len==127): 64-bit extended length
 *   masking key (4 bytes, if MASK bit set)
 *   payload
 */
object WebSocketParser {

    private const val TAG = "WebSocketParser"

    // Opcodes
    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    data class WsFrame(
        val opcode: Int,
        val isMasked: Boolean,
        val payload: ByteArray,
        val totalLength: Int,
    ) {
        val opcodeName: String
            get() = when (opcode) {
                OP_CONTINUATION -> "CONTINUATION"
                OP_TEXT -> "TEXT"
                OP_BINARY -> "BINARY"
                OP_CLOSE -> "CLOSE"
                OP_PING -> "PING"
                OP_PONG -> "PONG"
                else -> "OPCODE_$opcode"
            }

        val payloadText: String
            get() = if (opcode == OP_TEXT || opcode == OP_CONTINUATION) {
                String(payload, Charsets.UTF_8)
            } else {
                "(${payload.size} bytes)"
            }
    }

    /** True if the HTTP headers contain a WebSocket upgrade handshake. */
    fun looksLikeUpgrade(headers: Map<String, String>, isRequest: Boolean): Boolean {
        val upgrade = (headers["Upgrade"] ?: headers["upgrade"] ?: "").lowercase()
        val connection = (headers["Connection"] ?: headers["connection"] ?: "").lowercase()
        return upgrade.contains("websocket") ||
            (isRequest && connection.contains("upgrade"))
    }

    /**
     * Attempts to parse one WebSocket frame from the byte array.
     * Returns null if insufficient data (need more bytes).
     */
    fun tryParseFrame(data: ByteArray, offset: Int = 0): WsFrame? {
        if (data.size - offset < 2) return null

        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val opcode = b0 and 0x0F
        val isMasked = (b1 and 0x80) != 0
        var payloadLen = b1 and 0x7F
        var idx = offset + 2

        when (payloadLen) {
            126 -> {
                if (data.size - idx < 2) return null
                payloadLen = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
                idx += 2
            }
            127 -> {
                if (data.size - idx < 8) return null
                var len = 0L
                for (i in 0 until 8) {
                    len = (len shl 8) or (data[idx + i].toLong() and 0xFF)
                }
                payloadLen = len.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                idx += 8
            }
        }

        val maskKey = if (isMasked) {
            if (data.size - idx < 4) return null
            val key = data.copyOfRange(idx, idx + 4)
            idx += 4
            key
        } else {
            null
        }

        if (data.size - idx < payloadLen) return null

        val payload = data.copyOfRange(idx, idx + payloadLen)

        // Unmask if masked (client -> server frames are always masked)
        if (isMasked && maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return WsFrame(
            opcode = opcode,
            isMasked = isMasked,
            payload = payload,
            totalLength = (idx + payloadLen) - offset,
        )
    }

    /** Parses all complete frames from a buffer, returning consumed bytes and frames. */
    fun parseFrames(data: ByteArray): Pair<Int, List<WsFrame>> {
        val frames = mutableListOf<WsFrame>()
        var consumed = 0
        while (true) {
            val frame = tryParseFrame(data, consumed) ?: break
            frames.add(frame)
            consumed += frame.totalLength
            if (consumed >= data.size) break
        }
        return consumed to frames
    }

    /** Log-friendly one-line description of a frame. */
    fun describe(frame: WsFrame): String =
        "${frame.opcodeName} ${if (frame.isMasked) "masked" else "unmasked"} len=${frame.payload.size} ${frame.payloadText.take(80)}"
}
