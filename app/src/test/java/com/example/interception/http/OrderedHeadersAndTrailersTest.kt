package com.example.interception.http

import com.example.data.models.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S3 task 20: headers are captured as an ORDERED, duplicate-preserving list on both protocols, and
 * trailers are parsed on HTTP/1.1 (chunked) and HTTP/2 (second HEADERS block per direction).
 *
 *  - [Http2Parser.H2Headers] keeps `entries` verbatim while `pseudoHeaders`/`regularHeaders`
 *    stay last-wins maps for the legacy callers.
 *  - [HttpParser] fills `headerLines`/`trailers` on [ParsedHttpMessage] next to the collapsed
 *    lowercase `headers` map that every existing consumer still reads.
 *  - [Http2Session] surfaces request/response trailers and a bounded per-stream frame timeline
 *    on [Http2Transaction].
 */
class OrderedHeadersAndTrailersTest {

    // -- H2Headers -----------------------------------------------------------------------------------

    @Test
    fun `H2Headers keeps duplicate set-cookie entries in wire order while the maps stay last-wins`() {
        val h = Http2Parser.H2Headers(
            streamId = 1,
            entries = listOf(":status" to "200", "set-cookie" to "a=1", "content-type" to "text/html", "set-cookie" to "b=2"),
            endStream = false,
        )
        assertEquals(listOf("a=1", "b=2"), h.entries.filter { it.first.equals("set-cookie", ignoreCase = true) }.map { it.second })
        assertEquals(listOf("set-cookie" to "a=1", "content-type" to "text/html", "set-cookie" to "b=2"), h.regularEntries)
        assertEquals(mapOf(":status" to "200"), h.pseudoHeaders)
        assertEquals("b=2", h.regularHeaders["set-cookie"])
        assertEquals(2, h.regularHeaders.size)
        assertEquals("200", h.all[":status"])
    }

    @Test
    fun `H2Headers map constructor is equal to the list constructor with the same content`() {
        val fromMaps = Http2Parser.H2Headers(
            3, linkedMapOf(":method" to "GET", ":path" to "/"), linkedMapOf("accept" to "*/*"), endStream = true,
        )
        val fromList = Http2Parser.H2Headers(3, listOf(":method" to "GET", ":path" to "/", "accept" to "*/*"), endStream = true)
        assertEquals(fromList, fromMaps)
        assertEquals(fromList.hashCode(), fromMaps.hashCode())
        assertEquals(fromList, fromList.copy())
        assertTrue(fromList.copy(endStream = false) != fromList)
    }

    // -- HTTP/1.1 ------------------------------------------------------------------------------------

    @Test
    fun `HTTP1 response with chunked body exposes raw-cased ordered header lines and parsed trailers`() {
        val wire = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "Set-Cookie: a=1\r\n" +
            "Set-Cookie: b=2\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Trailer: X-Checksum, X-Elapsed\r\n" +
            "\r\n" +
            "5\r\nhello\r\n" +
            "6\r\n world\r\n" +
            "0\r\n" +
            "X-Checksum: abc123\r\n" +
            "X-Elapsed: 42\r\n" +
            "\r\n"
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())
        assertNotNull(parsed)
        parsed!!
        assertEquals("hello world", parsed.body)
        assertEquals(wire.toByteArray().size, parsed.consumedBytes)
        assertEquals(
            listOf("Content-Type", "Set-Cookie", "Set-Cookie", "Transfer-Encoding", "Trailer"),
            parsed.headerLines.map { it.first },
        )
        assertEquals(listOf("a=1", "b=2"), parsed.headerLines.filter { it.first == "Set-Cookie" }.map { it.second })
        // The legacy map is unchanged: lowercase keys, last duplicate wins.
        assertEquals("b=2", parsed.headers["set-cookie"])
        assertEquals(listOf("X-Checksum" to "abc123", "X-Elapsed" to "42"), parsed.trailers)
    }

    @Test
    fun `HTTP1 chunked response without trailers has an empty trailer list`() {
        val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
        val parsed = HttpParser.tryParseResponse(wire.toByteArray())!!
        assertEquals("abc", parsed.body)
        assertTrue(parsed.trailers.isEmpty())
        assertEquals(listOf("Transfer-Encoding" to "chunked"), parsed.headerLines)
    }

    @Test
    fun `HTTP1 request header lines keep order and casing`() {
        val wire = "GET /a HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\nX-Dup: 1\r\nX-Dup: 2\r\n\r\n"
        val parsed = HttpParser.tryParseRequest(wire.toByteArray(), "example.com", 80, isHttps = false)!!
        assertEquals(HttpMethod.GET, parsed.method)
        assertEquals(listOf("Host", "Accept", "X-Dup", "X-Dup"), parsed.headerLines.map { it.first })
        assertEquals("2", parsed.headers["x-dup"])
        assertTrue(parsed.trailers.isEmpty())
    }

    // -- HTTP/2 session ------------------------------------------------------------------------------

    private fun literalHeader(name: String, value: String): ByteArray {
        require(name.length < 127 && value.length < 127)
        return byteArrayOf(0x40) +
            byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(value.length.toByte()) + value.toByteArray(Charsets.US_ASCII)
    }

    private fun headersFrame(streamId: Int, block: ByteArray, flags: Int) =
        Http2Parser.H2Frame(block.size, Http2Parser.FRAME_HEADERS, flags, streamId, block)

    private fun dataFrame(streamId: Int, payload: ByteArray, endStream: Boolean) =
        Http2Parser.H2Frame(payload.size, Http2Parser.FRAME_DATA, if (endStream) Http2Parser.FLAG_END_STREAM else 0, streamId, payload)

    @Test
    fun `Http2Session records request and response trailers and a frame timeline on the transaction`() {
        val transactions = CopyOnWriteArrayList<Http2Transaction>()
        val session = Http2Session(onTransaction = { transactions += it }, onGrpcMessage = { throw AssertionError(it) })

        val req = literalHeader(":method", "POST") + literalHeader(":scheme", "https") + literalHeader(":path", "/upload") +
            literalHeader(":authority", "h2.example") + literalHeader("cookie", "a=1") + literalHeader("cookie", "b=2")
        session.onClientFrames(listOf(headersFrame(1, req, Http2Parser.FLAG_END_HEADERS)))
        session.onClientFrames(listOf(dataFrame(1, "payload".toByteArray(), endStream = false)))
        session.onClientFrames(listOf(headersFrame(1, literalHeader("x-request-digest", "r1"), Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_END_STREAM)))

        val resp = literalHeader(":status", "200") + literalHeader("set-cookie", "s=1") + literalHeader("set-cookie", "s=2")
        session.onServerFrames(listOf(headersFrame(1, resp, Http2Parser.FLAG_END_HEADERS)))
        session.onServerFrames(listOf(dataFrame(1, "ok".toByteArray(), endStream = false)))
        session.onServerFrames(listOf(headersFrame(1, literalHeader("x-response-digest", "d1"), Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_END_STREAM)))

        val tx = transactions.single()
        assertEquals(200, tx.response.statusCode)
        assertEquals("payload", tx.request.body)
        assertEquals("ok", tx.response.body)
        // Ordered header lines with the duplicate cookie / set-cookie preserved.
        assertEquals(listOf("cookie" to "a=1", "cookie" to "b=2"), tx.request.headerLines)
        assertEquals(listOf("set-cookie" to "s=1", "set-cookie" to "s=2"), tx.response.headerLines)
        assertEquals(listOf("x-request-digest" to "r1"), tx.request.trailers)
        assertEquals(listOf("x-response-digest" to "d1"), tx.response.trailers)
        assertEquals(listOf("x-request-digest" to "r1"), tx.requestTrailers?.regularEntries)
        assertEquals(listOf("x-response-digest" to "d1"), tx.responseTrailers?.regularEntries)
        assertEquals(listOf(":method", ":scheme", ":path", ":authority"), tx.requestHeaders!!.entries.filter { it.first.startsWith(":") }.map { it.first })
        // Trailers never leak into the header map that the UI/rules read.
        assertNull(tx.request.headers["x-request-digest"])

        // Frame timeline: 3 client + 3 server frames in arrival order.
        assertEquals(6, tx.frameEvents.size)
        assertEquals(listOf(true, true, true, false, false, false), tx.frameEvents.map { it.fromClient })
        assertEquals(
            listOf(Http2Parser.FRAME_HEADERS, Http2Parser.FRAME_DATA, Http2Parser.FRAME_HEADERS, Http2Parser.FRAME_HEADERS, Http2Parser.FRAME_DATA, Http2Parser.FRAME_HEADERS),
            tx.frameEvents.map { it.frameType },
        )
        assertEquals("END_STREAM END_HEADERS", tx.frameEvents[2].note)
        assertEquals("END_HEADERS", tx.frameEvents[0].note)
        assertNull(tx.frameEvents[1].note)
        assertTrue(tx.frameEvents.all { it.streamId == 1 })
    }

    @Test
    fun `frame type and error code names cover the RFC 7540 registries`() {
        assertEquals("HEADERS", Http2Parser.frameTypeName(Http2Parser.FRAME_HEADERS))
        assertEquals("DATA", Http2Parser.frameTypeName(Http2Parser.FRAME_DATA))
        assertEquals("RST_STREAM", Http2Parser.frameTypeName(Http2Parser.FRAME_RST_STREAM))
        assertEquals("CANCEL", Http2Parser.errorCodeName(byteArrayOf(0, 0, 0, 8)))
        assertEquals("NO_ERROR", Http2Parser.errorCodeName(byteArrayOf(0, 0, 0, 0)))
        assertEquals("PROTOCOL_ERROR", Http2Parser.errorCodeName(byteArrayOf(0, 0, 0, 1)))
    }
}
