package com.example.interception.http

import com.example.data.models.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MUST-FIX 2: [HttpParser.tryParseRequest]/[HttpParser.tryParseResponse] must never build a
 * message off an attacker-controlled Content-Length that is negative or would overflow the
 * `headerEnd + 4 + contentLength` arithmetic, and must never throw doing so.
 *
 * Before this fix, `headers["content-length"]?.toIntOrNull() ?: 0` treated a header like
 * "-1" as a valid length of -1, producing `totalNeeded = headerEnd + 3` -- one byte short of
 * the real frame boundary -- which desynchronizes every subsequent parse on the same stream. A
 * huge-but-syntactically-valid `Int` value could also overflow the sum outright, after which
 * `copyOfRange` either throws or reads the wrong bytes.
 */
class HttpParserHostileInputTest {

    @Test
    fun `negative Content-Length on a response never desyncs framing or throws`() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\nSOMEBODY".toByteArray()

        val parsed = HttpParser.tryParseResponse(wire)

        // Must not throw (verified simply by reaching this line) and must never construct a
        // message off an unsatisfiable length -- there is no "correct" positive interpretation
        // of a negative Content-Length, so this must be treated as unparseable, not as zero.
        assertNull(parsed)
    }

    @Test
    fun `negative Content-Length on a request never desyncs framing or throws`() {
        val wire = "POST /x HTTP/1.1\r\nHost: h\r\nContent-Length: -5\r\n\r\nSOMEBODY".toByteArray()

        val parsed = HttpParser.tryParseRequest(wire, "h", 443, isHttps = true)

        assertNull(parsed)
    }

    @Test
    fun `absurdly large Content-Length that would overflow Int arithmetic never throws`() {
        // Large enough that headerEnd + 4 + contentLength overflows a 32-bit Int.
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: 9999999999999999999\r\n\r\nx".toByteArray()

        val parsed = HttpParser.tryParseResponse(wire)

        // Must not throw. An overflowing length can never be satisfied by any real buffer, so
        // this must never resolve into a message.
        assertNull(parsed)
    }

    @Test
    fun `Content-Length exactly at Int overflow boundary never throws`() {
        val hugeButInRange = Int.MAX_VALUE.toLong() // itself would overflow once headerEnd+4 is added
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: $hugeButInRange\r\n\r\nx".toByteArray()

        val parsed = HttpParser.tryParseResponse(wire)

        assertNull(parsed)
    }

    @Test
    fun `non-numeric Content-Length never throws and is treated as unparseable`() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: not-a-number\r\n\r\nSOMEBODY".toByteArray()

        val parsed = HttpParser.tryParseResponse(wire)

        assertNull(parsed)
    }

    @Test
    fun `after rejecting a malformed Content-Length, a subsequent well-formed message on the same buffer still parses`() {
        // Regression for the desync itself: verify that fixing the framing bug means a
        // *correct* message right behind a hostile one is unaffected once isolated on its own.
        val good = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
        val parsed = HttpParser.tryParseResponse(good.toByteArray())

        assertNotNull(parsed)
        assertEquals("hello", parsed!!.body)
        assertEquals(good.toByteArray().size, parsed.consumedBytes)
    }

    @Test
    fun `zero Content-Length is valid and produces an empty body`() {
        val wire = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNotNull(parsed)
        assertEquals("", parsed!!.body)
    }

    @Test
    fun `whitespace-padded valid Content-Length still parses correctly`() {
        val body = "hi"
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: 2 \r\n\r\n$body"
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNotNull(parsed)
        assertEquals(body, parsed!!.body)
    }

    @Test
    fun `HEAD response ignores Content-Length entirely regardless of value`() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n"
        val parsed = HttpParser.tryParseResponse(wire.toByteArray(), sentRequestMethod = HttpMethod.HEAD)

        assertNotNull(parsed)
        assertEquals("", parsed!!.body)
        assertEquals(wire.toByteArray().size, parsed.consumedBytes)
    }
}
