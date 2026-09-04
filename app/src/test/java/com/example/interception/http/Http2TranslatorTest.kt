package com.example.interception.http

import com.example.interception.http.Http2Parser.H2Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * S3 task 57 (gate G27): message validation (RFC 9113 §8.2/§8.3) and the h2 <-> HTTP/1.1
 * mapping the terminating proxy applies when the two legs speak different versions.
 */
class Http2TranslatorTest {

    private fun h(vararg entries: Pair<String, String>, endStream: Boolean = false) = H2Headers(1, entries.toList(), endStream)

    private val okRequest = arrayOf(":method" to "GET", ":scheme" to "https", ":authority" to "example.com", ":path" to "/x")

    // ── validate ────────────────────────────────────────────────────────────────────────

    @Test
    fun `validate accepts a well-formed request and response`() {
        assertNull(Http2Translator.validate(h(*okRequest, "accept" to "*/*"), isRequest = true, trailers = false))
        assertNull(Http2Translator.validate(h(":status" to "204"), isRequest = false, trailers = false))
        assertNull(Http2Translator.validate(h("grpc-status" to "0"), isRequest = false, trailers = true))
        assertNull(Http2Translator.validate(h(":method" to "CONNECT", ":authority" to "example.com:443"), isRequest = true, trailers = false))
        assertNull(Http2Translator.validate(h(*okRequest, "te" to "trailers"), isRequest = true, trailers = false))
    }

    @Test
    fun `validate rejects malformed field names and values`() {
        assertNotNull(Http2Translator.validate(h(*okRequest, "Accept" to "*/*"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "" to "x"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "bad name" to "x"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "a:b" to "x"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "x" to "a\r\nb"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "x" to "a\u0000b"), true, false))
    }

    @Test
    fun `validate enforces pseudo-header rules`() {
        assertNotNull("pseudo after regular", Http2Translator.validate(h(":method" to "GET", "accept" to "*/*", ":scheme" to "https", ":path" to "/"), true, false))
        assertNotNull("duplicate", Http2Translator.validate(h(*okRequest, ":method" to "POST"), true, false))
        assertNotNull("response pseudo in request", Http2Translator.validate(h(*okRequest, ":status" to "200"), true, false))
        assertNotNull("request pseudo in response", Http2Translator.validate(h(":status" to "200", ":path" to "/"), false, false))
        assertNotNull("pseudo in trailers", Http2Translator.validate(h(":status" to "200"), false, true))
        assertNotNull("missing :method", Http2Translator.validate(h(":scheme" to "https", ":path" to "/"), true, false))
        assertNotNull("missing :path", Http2Translator.validate(h(":method" to "GET", ":scheme" to "https"), true, false))
        assertNotNull("CONNECT with :path", Http2Translator.validate(h(":method" to "CONNECT", ":authority" to "a", ":path" to "/"), true, false))
        assertNotNull("CONNECT without authority", Http2Translator.validate(h(":method" to "CONNECT"), true, false))
        assertNotNull("missing :status", Http2Translator.validate(h("content-type" to "a"), false, false))
        assertNotNull("bad :status", Http2Translator.validate(h(":status" to "20"), false, false))
        assertNotNull("bad :status", Http2Translator.validate(h(":status" to "2x0"), false, false))
    }

    @Test
    fun `validate rejects connection-specific fields and bad te or content-length`() {
        assertNotNull(Http2Translator.validate(h(*okRequest, "connection" to "keep-alive"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "transfer-encoding" to "chunked"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "upgrade" to "websocket"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "keep-alive" to "timeout=5"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "te" to "gzip"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "content-length" to "-1"), true, false))
        assertNotNull(Http2Translator.validate(h(*okRequest, "content-length" to "abc"), true, false))
        assertNull(Http2Translator.validate(h(*okRequest, "content-length" to "12"), true, false))
    }

    @Test
    fun `validate enforces SETTINGS_MAX_HEADER_LIST_SIZE`() {
        val big = h(*okRequest, "x" to "v".repeat(1000))
        assertNotNull(Http2Translator.validate(big, true, false, maxHeaderListSize = 500))
        assertNull(Http2Translator.validate(big, true, false, maxHeaderListSize = 5_000))
    }

    // ── h2 -> HTTP/1.1 ───────────────────────────────────────────────────────────────────

    private fun String.head() = toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `requestToHttp1 writes the request line, Host and Content-Length and folds cookies`() {
        val headers = h(
            ":method" to "POST", ":scheme" to "https", ":authority" to "api.example.com", ":path" to "/v1/items",
            "content-type" to "application/json", "cookie" to "a=1", "X-Trace" to "abc", "cookie" to "b=2",
            "connection" to "close", "content-length" to "999", "expect" to "100-continue", "te" to "trailers",
        )
        val head = Http2Translator.requestToHttp1(headers, bodyLength = 12, trailers = null)
        assertFalse(head.chunked)
        val text = String(head.bytes, Charsets.ISO_8859_1)
        assertEquals(
            "POST /v1/items HTTP/1.1\r\nHost: api.example.com\r\ncontent-type: application/json\r\nCookie: a=1; b=2\r\nX-Trace: abc\r\nContent-Length: 12\r\n\r\n",
            text,
        )
    }

    @Test
    fun `requestToHttp1 omits Content-Length for a bodyless GET and adds it for an empty POST`() {
        val get = String(Http2Translator.requestToHttp1(h(*okRequest), 0, null).bytes, Charsets.ISO_8859_1)
        assertEquals("GET /x HTTP/1.1\r\nHost: example.com\r\n\r\n", get)
        val post = String(Http2Translator.requestToHttp1(h(":method" to "POST", ":scheme" to "https", ":authority" to "e", ":path" to "/"), 0, null).bytes, Charsets.ISO_8859_1)
        assertTrue(post, post.contains("Content-Length: 0\r\n"))
    }

    @Test
    fun `requestToHttp1 with trailers uses chunked framing and chunkedBody carries them`() {
        val head = Http2Translator.requestToHttp1(h(":method" to "POST", ":scheme" to "https", ":authority" to "e", ":path" to "/", "te" to "trailers"), 5, listOf("grpc-status" to "0"))
        assertTrue(head.chunked)
        val text = String(head.bytes, Charsets.ISO_8859_1)
        assertTrue(text, text.contains("TE: trailers\r\n"))
        assertTrue(text, text.contains("Transfer-Encoding: chunked\r\n"))
        assertFalse(text, text.contains("Content-Length"))
        val body = String(Http2Translator.chunkedBody("hello".toByteArray(), listOf("grpc-status" to "0")), Charsets.ISO_8859_1)
        assertEquals("5\r\nhello\r\n0\r\ngrpc-status: 0\r\n\r\n", body)
        assertEquals("0\r\n\r\n", String(Http2Translator.chunkedBody(ByteArray(0), emptyList()), Charsets.ISO_8859_1))
    }

    @Test
    fun `requestToHttp1 keeps an explicit host header and uses authority as CONNECT target`() {
        val withHost = String(Http2Translator.requestToHttp1(h(*okRequest, "Host" to "other.example"), 0, null).bytes, Charsets.ISO_8859_1)
        assertEquals("GET /x HTTP/1.1\r\nHost: other.example\r\n\r\n", withHost)
        val connect = String(Http2Translator.requestToHttp1(h(":method" to "CONNECT", ":authority" to "h:443"), 0, null).bytes, Charsets.ISO_8859_1)
        assertTrue(connect, connect.startsWith("CONNECT h:443 HTTP/1.1\r\n"))
    }

    // ── HTTP/1.1 -> h2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `responseToH2 puts status first, lowercases and strips connection-specific and nominated fields`() {
        val out = Http2Translator.responseToH2(
            200,
            listOf("Content-Type" to "text/plain", "Connection" to "keep-alive, X-Hop", "Keep-Alive" to "timeout=5", "X-Hop" to "1", "Transfer-Encoding" to "chunked", "Set-Cookie" to "a=1", "Set-Cookie" to "b=2"),
        )
        assertEquals(listOf(":status" to "200", "content-type" to "text/plain", "set-cookie" to "a=1", "set-cookie" to "b=2"), out)
        assertEquals(listOf("grpc-status" to "0"), Http2Translator.trailersToH2(listOf("Grpc-Status" to "0", "Connection" to "close", ":x" to "y")))
    }

    @Test
    fun `requestToH2 maps request line and Host into pseudo-headers`() {
        val out = Http2Translator.requestToH2("GET", "/a?b=1", "https", listOf("Host" to "example.com", "Accept" to "*/*", "Connection" to "Upgrade, X-Drop", "Upgrade" to "h2c", "X-Drop" to "1", "TE" to "gzip"))
        assertEquals(listOf(":method" to "GET", ":scheme" to "https", ":path" to "/a?b=1", ":authority" to "example.com", "accept" to "*/*"), out)
        val absolute = Http2Translator.requestToH2("GET", "https://h.example/p", "https", emptyList())
        assertEquals(listOf(":method" to "GET", ":scheme" to "https", ":path" to "/p", ":authority" to "h.example"), absolute)
        val connect = Http2Translator.requestToH2("CONNECT", "h:443", "https", listOf("Host" to "h:443"))
        assertEquals(listOf(":method" to "CONNECT", ":authority" to "h:443"), connect)
    }

    @Test
    fun `responseToHttp1 frames by content-length, chunked or nothing for bodyless statuses`() {
        val fixed = String(Http2Translator.responseToHttp1(h(":status" to "200", "content-type" to "a/b"), 5).bytes, Charsets.ISO_8859_1)
        assertEquals("HTTP/1.1 200 OK\r\ncontent-type: a/b\r\nContent-Length: 5\r\n\r\n", fixed)
        val chunked = Http2Translator.responseToHttp1(h(":status" to "404"), null, keepAlive = false)
        assertTrue(chunked.chunked)
        assertEquals("HTTP/1.1 404 Not Found\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", String(chunked.bytes, Charsets.ISO_8859_1))
        val noContent = Http2Translator.responseToHttp1(h(":status" to "204"), null)
        assertFalse(noContent.chunked)
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", String(noContent.bytes, Charsets.ISO_8859_1))
        val explicit = String(Http2Translator.responseToHttp1(h(":status" to "200", "content-length" to "3"), null).bytes, Charsets.ISO_8859_1)
        assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 3\r\n\r\n", explicit)
    }

    // ── Http1ResponseReader ──────────────────────────────────────────────────────────────

    private fun reader(vararg parts: String) = Http2Translator.Http1ResponseReader(ByteArrayInputStream(parts.joinToString("").head()), bufferSize = 7)

    private fun collect(block: ((ByteArray, Int, Int) -> Unit) -> Unit): String {
        val out = ByteArrayOutputStream()
        block { b, o, n -> out.write(b, o, n) }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    @Test
    fun `reader parses a fixed-length response and the next one on the same connection`() {
        val r = reader("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX-A: 1\r\n\r\nhello", "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
        val first = r.readHead()
        assertEquals(200, first.statusCode)
        assertEquals("OK", first.reason)
        assertEquals(5L, first.contentLength)
        assertFalse(first.chunked)
        assertEquals("hello", collect { r.readFixed(5, it) })
        val second = r.readHead()
        assertEquals(404, second.statusCode)
        assertEquals(0L, second.contentLength)
        assertEquals("1", first.header("x-a"))
    }

    @Test
    fun `reader decodes chunked bodies with extensions and returns trailers`() {
        val r = reader("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: Grpc-Status\r\n\r\n", "5;ext=1\r\nhello\r\n6\r\n world\r\n0\r\nGrpc-Status: 0\r\nGrpc-Message: ok\r\n\r\n")
        val head = r.readHead()
        assertTrue(head.chunked)
        var trailers: List<Pair<String, String>> = emptyList()
        val body = collect { sink -> trailers = r.readChunked(sink) }
        assertEquals("hello world", body)
        assertEquals(listOf("Grpc-Status" to "0", "Grpc-Message" to "ok"), trailers)
    }

    @Test
    fun `reader reads until close and reports eof and Connection close`() {
        val r = reader("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n", "streamed until the server hangs up")
        val head = r.readHead()
        assertTrue(head.connectionClose)
        assertNull(head.contentLength)
        assertFalse(r.eof)
        assertEquals("streamed until the server hangs up", collect { r.readUntilClose(it) })
        assertTrue(r.eof)
    }

    @Test
    fun `reader throws on a truncated body and a non-HTTP status line`() {
        val truncated = reader("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc")
        truncated.readHead()
        try {
            collect { truncated.readFixed(10, it) }
            org.junit.Assert.fail("expected EOF")
        } catch (_: java.io.EOFException) {
        }
        try {
            reader("garbage\r\n\r\n").readHead()
            org.junit.Assert.fail("expected IOException")
        } catch (_: java.io.IOException) {
        }
    }
}
