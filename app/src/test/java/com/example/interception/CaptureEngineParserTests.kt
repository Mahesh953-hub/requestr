package com.example.interception

import com.example.interception.http.HttpParser
import com.example.interception.http.WebSocketParser
import com.example.interception.http.SseParser
import com.example.interception.dns.DnsParser
import com.example.interception.quic.QuicParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the capture pipeline parsers.
 * Each test feeds synthetic bytes/strings and asserts the parser output.
 */
class CaptureEngineParserTests {

    // ── HttpParser ─────────────────────────────────────────────────────

    @Test
    fun `parse HTTP GET request`() {
        val request = "GET /api/users HTTP/1.1\r\nHost: example.com\r\nAccept: application/json\r\n\r\n"
        val parsed = HttpParser.tryParseRequest(request.toByteArray(), "example.com", 80, isHttps = false)
        assertNotNull(parsed)
        assertEquals("GET", parsed!!.method?.name)
        assertEquals("/api/users", parsed.path)
        assertEquals("example.com", parsed.host)
        assertEquals("HTTP/1.1", parsed.version)
        assertTrue(parsed.isRequest)
    }

    @Test
    fun `parse HTTP POST with body`() {
        val body = "{\"key\":\"value\"}"
        val request = "POST /submit HTTP/1.1\r\nHost: api.test\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val parsed = HttpParser.tryParseRequest(request.toByteArray(), "api.test", 80, isHttps = false)
        assertNotNull(parsed)
        assertEquals("POST", parsed!!.method?.name)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `parse HTTP response`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"status\":\"ok\"}"
        val parsed = HttpParser.tryParseResponse(response.toByteArray())
        assertNotNull(parsed)
        assertEquals(200, parsed!!.statusCode)
        assertFalse(parsed.isRequest)
    }

    // ── WebSocketParser ───────────────────────────────────────────────

    @Test
    fun `detect WebSocket upgrade`() {
        val headers = mapOf(
            "Upgrade" to "websocket",
            "Connection" to "Upgrade",
        )
        assertTrue(WebSocketParser.looksLikeUpgrade(headers, isRequest = true))
        assertFalse(WebSocketParser.looksLikeUpgrade(emptyMap(), isRequest = true))
    }

    @Test
    fun `parse WebSocket text frame`() {
        // Unmasked text frame: FIN=1, opcode=1, len=5, payload="hello"
        val frame = byteArrayOf(
            0x81.toByte(), // FIN + TEXT opcode
            0x05,          // payload len = 5
            'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()
        )
        val parsed = WebSocketParser.tryParseFrame(frame)
        assertNotNull(parsed)
        assertEquals(WebSocketParser.OP_TEXT, parsed!!.opcode)
        assertEquals("hello", parsed.payloadText)
        assertFalse(parsed.isMasked)
    }

    @Test
    fun `parse masked WebSocket frame`() {
        // Masked text frame: FIN=1, opcode=1, MASK=1, len=3, mask=[1,2,3,4], payload="abc"
        val mask = byteArrayOf(1, 2, 3, 4)
        val payload = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        val masked = payload.mapIndexed { i, b -> (b.toInt() xor mask[i % 4].toInt()).toByte() }.toByteArray()
        val frame = byteArrayOf(0x81.toByte(), 0x83.toByte()) + mask + masked
        val parsed = WebSocketParser.tryParseFrame(frame)
        assertNotNull(parsed)
        assertEquals("abc", parsed!!.payloadText)
        assertTrue(parsed.isMasked)
    }

    // ── SseParser ─────────────────────────────────────────────────────

    @Test
    fun `detect SSE content-type`() {
        assertTrue(SseParser.looksLikeSse(mapOf("Content-Type" to "text/event-stream")))
        assertFalse(SseParser.looksLikeSse(mapOf("Content-Type" to "application/json")))
        assertFalse(SseParser.looksLikeSse(null))
    }

    @Test
    fun `parse SSE events`() {
        // Direct construction test — bypass string escaping ambiguity
        val body = buildString {
            append("event: update")
            append('\n')
            append("data: {\"count\": 5}")
            append('\n')
            append('\n')
        }
        // Verify the split produces the expected blocks
        val blocks = body.split("\n\n")
        assertTrue("Should have at least 1 block", blocks.isNotEmpty())

        val (events, _) = SseParser.parseEvents(body)
        assertTrue("Events should not be empty, got ${events.size}", events.isNotEmpty())
        assertEquals("update", events[0].event)
        assertEquals("{\"count\": 5}", events[0].data)
    }

    // ── DnsParser ─────────────────────────────────────────────────────

    @Test
    fun `parse DNS query`() {
        // Synthetic DNS query for "example.com", type A
        val header = byteArrayOf(
            0x12, 0x34, // Transaction ID
            0x01, 0x00, // Flags: standard query
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, // ANCOUNT = 0
            0x00, 0x00, // NSCOUNT
            0x00, 0x00, // ARCOUNT
        )
        // QNAME: 7example3com0
        val qname = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        // QTYPE=A(1), QCLASS=IN(1)
        val question = qname + byteArrayOf(0x00, 0x01, 0x00, 0x01)
        val packet = header + question

        val parsed = DnsParser.parse(packet, 12345, 53)
        assertNotNull(parsed)
        assertEquals("example.com", parsed!!.hostname)
        assertEquals("A", parsed.qtypeName)
        assertFalse(parsed.isResponse)
    }

    // ── QuicParser ────────────────────────────────────────────────────

    @Test
    fun `reject non-QUIC packet`() {
        val notQuic = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertNull(QuicParser.extractSni(notQuic))
    }

    // ── TextDiffer ─────────────────────────────────────────────────────

    @Test
    fun `diff two similar texts`() {
        val old = "line1\nline2\nline3"
        val new = "line1\nmodified\nline3"
        val hunks = com.example.data.analysis.TextDiffer.diff(old, new)
        val (added, removed, _) = com.example.data.analysis.TextDiffer.summarize(hunks)
        assertTrue(added > 0 || removed > 0)
        assertTrue(hunks.any { it.op == com.example.data.analysis.TextDiffer.Op.EQUAL })
    }

    @Test
    fun `diff empty to non-empty`() {
        val hunks = com.example.data.analysis.TextDiffer.diff("", "hello")
        assertTrue(hunks.isNotEmpty())
        assertTrue(hunks.any { it.op == com.example.data.analysis.TextDiffer.Op.INSERT })
    }
}
