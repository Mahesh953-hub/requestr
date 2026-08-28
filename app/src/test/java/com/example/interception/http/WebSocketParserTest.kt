package com.example.interception.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WebSocketParser] (RFC 6455 frame parsing, masking, fragmentation) and
 * [WsMessageAssembler] (fragment reassembly with control-frame interleaving). Frames are built
 * byte-by-byte per the RFC 6455 §5.2 layout, including a real client masking key XOR'd over the
 * payload -- not pre-canned frame fixtures.
 */
class WebSocketParserTest {

    /** Builds one raw RFC 6455 frame. When [maskKey] is non-null the payload is masked in place. */
    private fun buildFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        maskKey: ByteArray? = null,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b0 = (if (fin) 0x80 else 0x00) or opcode
        out.write(b0)
        val len = payload.size
        val maskBit = if (maskKey != null) 0x80 else 0x00
        when {
            len < 126 -> out.write(maskBit or len)
            len < 65536 -> {
                out.write(maskBit or 126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> error("test helper only supports short payloads")
        }
        if (maskKey != null) {
            require(maskKey.size == 4)
            out.write(maskKey)
            val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
            out.write(masked)
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    // ── Masking ──────────────────────────────────────────────────────────

    @Test
    fun `masked client frame is unmasked back to the original payload`() {
        val original = "hello websocket".toByteArray()
        val maskKey = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val raw = buildFrame(WebSocketParser.OP_TEXT, original, maskKey = maskKey)

        val frame = WebSocketParser.tryParseFrame(raw)
        assertTrue(frame != null)
        frame!!
        assertTrue(frame.isMasked)
        assertArrayEquals(original, frame.payload)
        assertEquals(raw.size, frame.totalLength)
        assertEquals("hello websocket", frame.payloadText)
    }

    @Test
    fun `unmasked server frame passes through unchanged`() {
        val original = "server says hi".toByteArray()
        val raw = buildFrame(WebSocketParser.OP_TEXT, original, maskKey = null)
        val frame = WebSocketParser.tryParseFrame(raw)!!
        assertFalse(frame.isMasked)
        assertArrayEquals(original, frame.payload)
    }

    @Test
    fun `extended 16-bit length payload round-trips through masking`() {
        val original = ByteArray(500) { (it % 251).toByte() } // > 125, forces the 126-length form
        val maskKey = byteArrayOf(0xAA.toByte(), 0x00, 0xFF.toByte(), 0x55)
        val raw = buildFrame(WebSocketParser.OP_BINARY, original, maskKey = maskKey)
        val frame = WebSocketParser.tryParseFrame(raw)!!
        assertArrayEquals(original, frame.payload)
        assertEquals(raw.size, frame.totalLength)
    }

    @Test
    fun `truncated frame returns null instead of throwing`() {
        val raw = buildFrame(WebSocketParser.OP_TEXT, "not enough bytes yet".toByteArray())
        assertNull(WebSocketParser.tryParseFrame(raw.copyOfRange(0, raw.size - 3)))
        assertNull(WebSocketParser.tryParseFrame(byteArrayOf(0x81.toByte())))
    }

    @Test
    fun `parseFrames consumes back-to-back frames and leaves a trailing partial frame unconsumed`() {
        val f1 = buildFrame(WebSocketParser.OP_TEXT, "one".toByteArray())
        val f2 = buildFrame(WebSocketParser.OP_TEXT, "two".toByteArray())
        val partial = buildFrame(WebSocketParser.OP_TEXT, "three".toByteArray()).let { it.copyOfRange(0, it.size - 2) }

        val (consumed, frames) = WebSocketParser.parseFrames(f1 + f2 + partial)
        assertEquals((f1 + f2).size, consumed)
        assertEquals(2, frames.size)
        assertEquals("one", frames[0].payloadText)
        assertEquals("two", frames[1].payloadText)
    }

    // ── Upgrade detection ────────────────────────────────────────────────

    @Test
    fun `upgrade handshake headers are detected case-insensitively`() {
        assertTrue(
            WebSocketParser.looksLikeUpgrade(
                mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
                isRequest = true,
            ),
        )
        assertTrue(
            WebSocketParser.looksLikeUpgrade(mapOf("upgrade" to "WebSocket"), isRequest = false),
        )
        assertFalse(WebSocketParser.looksLikeUpgrade(mapOf("Content-Type" to "text/html"), isRequest = true))
    }

    // ── WsMessageAssembler: fragmentation ────────────────────────────────

    @Test
    fun `single unfragmented frame passes through immediately`() {
        val assembler = WsMessageAssembler()
        val frame = WebSocketParser.tryParseFrame(buildFrame(WebSocketParser.OP_TEXT, "whole".toByteArray()))!!
        val result = assembler.offer(frame)
        assertSame(frame, result)
    }

    @Test
    fun `fragmented text message reassembles across multiple continuation frames`() {
        val assembler = WsMessageAssembler()

        val start = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_TEXT, "Hello, ".toByteArray(), fin = false),
        )!!
        assertNull(assembler.offer(start))

        val mid = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_CONTINUATION, "cruel ".toByteArray(), fin = false),
        )!!
        assertNull(assembler.offer(mid))

        val end = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_CONTINUATION, "world!".toByteArray(), fin = true),
        )!!
        val complete = assembler.offer(end)

        assertTrue(complete != null)
        assertEquals(WebSocketParser.OP_TEXT, complete!!.opcode)
        assertEquals("Hello, cruel world!", complete.payloadText)
    }

    @Test
    fun `control frames interleaved mid-fragmentation pass through immediately without disrupting reassembly`() {
        val assembler = WsMessageAssembler()

        val start = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_BINARY, byteArrayOf(1, 2, 3), fin = false),
        )!!
        assertNull(assembler.offer(start))

        // A PING can legally arrive mid-fragmentation (RFC 6455 §5.5.2) and must not be buffered.
        val ping = WebSocketParser.tryParseFrame(buildFrame(WebSocketParser.OP_PING, byteArrayOf(9)))!!
        val pingResult = assembler.offer(ping)
        assertSame(ping, pingResult)

        val end = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_CONTINUATION, byteArrayOf(4, 5), fin = true),
        )!!
        val complete = assembler.offer(end)!!
        assertEquals(WebSocketParser.OP_BINARY, complete.opcode)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), complete.payload)
    }

    @Test
    fun `masked fragments unmask each piece before reassembly`() {
        val assembler = WsMessageAssembler()
        val key = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val start = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_TEXT, "abc".toByteArray(), fin = false, maskKey = key),
        )!!
        assertNull(assembler.offer(start))
        val end = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_CONTINUATION, "def".toByteArray(), fin = true, maskKey = key),
        )!!
        val complete = assembler.offer(end)!!
        assertEquals("abcdef", complete.payloadText)
    }

    @Test
    fun `stray continuation frame with no message in progress is tolerated`() {
        val assembler = WsMessageAssembler()
        val stray = WebSocketParser.tryParseFrame(
            buildFrame(WebSocketParser.OP_CONTINUATION, "orphan".toByteArray(), fin = true),
        )!!
        val result = assembler.offer(stray)
        assertSame(stray, result)
    }
}
