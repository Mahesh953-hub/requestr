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
        /**
         * RFC 6455 §5.2 FIN bit: true when this frame is the last (or only) fragment of a
         * message. A `false` TEXT/BINARY frame starts a fragmented message that continues in
         * one or more `false` CONTINUATION frames and ends with a `true` CONTINUATION frame --
         * see [WsMessageAssembler]. Control frames (CLOSE/PING/PONG) are never fragmented in
         * practice and always carry FIN=1.
         */
        val fin: Boolean = true,
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
        val fin = (b0 and 0x80) != 0
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
            fin = fin,
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

/**
 * Reassembles a fragmented WebSocket message (RFC 6455 §5.4): a `FIN=0` TEXT/BINARY frame
 * followed by zero or more `FIN=0` CONTINUATION frames, terminated by a `FIN=1` CONTINUATION
 * frame. Control frames (CLOSE/PING/PONG) are allowed to interleave mid-fragmentation (the RFC
 * permits this) and are always complete messages on their own -- [offer] returns them
 * immediately without touching the in-progress fragmentation state.
 *
 * One instance covers a single direction of a single connection; a WebSocket connection needs
 * two (client→server and server→client), matching the per-direction [Http2Parser.HpackDecoder]
 * convention used for HTTP/2.
 */
class WsMessageAssembler {
    private var messageOpcode = -1
    private var buffer: java.io.ByteArrayOutputStream? = null

    /**
     * Feed one parsed [WebSocketParser.WsFrame] through the assembler.
     *
     * @return the complete, reassembled message the moment its final (`FIN=1`) fragment
     *         arrives -- a control frame is returned as-is (never buffered); a non-final
     *         data/continuation fragment returns null while assembly continues.
     */
    fun offer(frame: WebSocketParser.WsFrame): WebSocketParser.WsFrame? {
        if (frame.opcode == WebSocketParser.OP_CLOSE ||
            frame.opcode == WebSocketParser.OP_PING ||
            frame.opcode == WebSocketParser.OP_PONG
        ) {
            return frame // control frames are never fragmented; pass through untouched
        }

        if (frame.opcode != WebSocketParser.OP_CONTINUATION) {
            // Starts a (possibly single-frame) message.
            if (frame.fin) return frame // common case: not fragmented at all
            messageOpcode = frame.opcode
            buffer = java.io.ByteArrayOutputStream().apply { write(frame.payload) }
            return null
        }

        // CONTINUATION: append to whatever message is in progress. A CONTINUATION with no
        // message in progress is a protocol violation on the wire -- tolerate it by treating
        // the payload as a message of its own rather than throwing, so a single malformed
        // frame never wedges the assembler for the rest of the connection.
        val inProgress = buffer
        if (inProgress == null) {
            return if (frame.fin) frame else null
        }
        inProgress.write(frame.payload)
        if (!frame.fin) return null

        val complete = WebSocketParser.WsFrame(
            opcode = messageOpcode,
            isMasked = frame.isMasked,
            payload = inProgress.toByteArray(),
            totalLength = frame.totalLength,
            fin = true,
        )
        buffer = null
        messageOpcode = -1
        return complete
    }
}
