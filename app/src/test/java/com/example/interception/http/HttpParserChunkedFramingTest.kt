package com.example.interception.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MUST-FIX 3: chunked-transfer-encoding decoding must give an unambiguous answer to "is this
 * message actually complete yet" -- distinguishing "not enough bytes have arrived" and "this
 * framing is corrupt and will never resolve" from "done" -- and must never throw regardless of
 * how malformed the chunk framing is.
 *
 * Before this fix, [HttpParser]'s private `decodeChunked` returned whatever bytes it had
 * accumulated when a chunk-size line, a chunk's data, or the terminal chunk's trailer section
 * was cut off mid-stream, and [HttpParser.tryParseResponse] treated that as a complete
 * message. CaptureEngine (unlike MitmProxyEngine, which has its own separate
 * `chunkedBodyComplete` re-check) relies solely on [HttpParser.tryParseResponse] returning
 * null for "not ready yet" -- so a truncated chunked body used to get persisted as if it were
 * the whole response.
 */
class HttpParserChunkedFramingTest {

    private fun chunkedResponse(chunkedBody: String, extraHeaders: String = ""): String =
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n$extraHeaders\r\n$chunkedBody"

    @Test
    fun `complete single-chunk body with no trailers parses correctly`() {
        val wire = chunkedResponse("5\r\nhello\r\n0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNotNull(parsed)
        assertEquals("hello", parsed!!.body)
        assertEquals(wire.toByteArray().size, parsed.consumedBytes)
    }

    @Test
    fun `complete multi-chunk body reassembles all chunks in order`() {
        val wire = chunkedResponse("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNotNull(parsed)
        assertEquals("Wikipedia", parsed!!.body)
    }

    @Test
    fun `body cut off mid-chunk-data is treated as incomplete, not a truncated success`() {
        // Declares a 20-byte chunk but only 5 bytes of chunk data have actually arrived.
        val wire = chunkedResponse("14\r\nhello")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNull("a truncated chunk body must never be treated as a complete message", parsed)
    }

    @Test
    fun `body cut off mid-chunk-size-line is treated as incomplete`() {
        val wire = chunkedResponse("1")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNull(parsed)
    }

    @Test
    fun `terminal chunk present but trailer section not yet terminated is treated as incomplete`() {
        // "0\r\n" (last-chunk) has arrived, but the final blank-line terminator has not.
        val wire = chunkedResponse("5\r\nhello\r\n0\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNull(parsed)
    }

    @Test
    fun `multiple trailer field lines are all consumed through the final blank line`() {
        // Regression: the old code only consumed the FIRST trailer line, leaving any
        // additional trailer lines in the buffer to be misparsed as the start of the next
        // message.
        val trailerBlock = "X-Checksum: abc123\r\nX-Extra: value\r\n\r\n"
        val chunked = "5\r\nhello\r\n0\r\n$trailerBlock"
        val wire = chunkedResponse(chunked)

        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNotNull(parsed)
        assertEquals("hello", parsed!!.body)
        assertEquals(
            "consumedBytes must account for every trailer line through the final blank line",
            wire.toByteArray().size,
            parsed.consumedBytes,
        )
    }

    @Test
    fun `trailer section with zero trailer lines still requires the final blank line to be present`() {
        // "0\r\n" followed by only a partial terminator ("\r" with no trailing "\n" yet).
        val incompleteWire = chunkedResponse("5\r\nhello\r\n0\r\n\r")
        assertNull(HttpParser.tryParseResponse(incompleteWire.toByteArray()))

        val completeWire = chunkedResponse("5\r\nhello\r\n0\r\n\r\n")
        assertNotNull(HttpParser.tryParseResponse(completeWire.toByteArray()))
    }

    @Test
    fun `non-hex chunk size is malformed and never throws or completes`() {
        val wire = chunkedResponse("ZZZ\r\nhello\r\n0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNull(parsed)
    }

    @Test
    fun `negative-looking chunk size token is rejected rather than parsed as a negative number`() {
        val wire = chunkedResponse("-5\r\nhello\r\n0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNull(parsed)
    }

    @Test
    fun `missing CRLF after chunk data is malformed and never throws`() {
        // Declares a 5-byte chunk, supplies 5 bytes of data, but the next two bytes are not
        // the required CRLF.
        val wire = chunkedResponse("5\r\nhelloXX0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNull(parsed)
    }

    @Test
    fun `absurdly large chunk-size hex value never overflows or throws`() {
        // Large enough hex value to overflow Int arithmetic if added to a byte offset unchecked.
        val wire = chunkedResponse("FFFFFFFFFFFFFFF\r\nhello\r\n0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNull(parsed)
    }

    @Test
    fun `chunk-extension after semicolon is ignored`() {
        val wire = chunkedResponse("5;ext=value\r\nhello\r\n0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNotNull(parsed)
        assertEquals("hello", parsed!!.body)
    }

    @Test
    fun `empty chunked body (immediate terminal chunk) parses to an empty string`() {
        val wire = chunkedResponse("0\r\n\r\n")
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())

        assertNotNull(parsed)
        assertEquals("", parsed!!.body)
    }
}
