package com.example.interception.http

import com.example.data.models.HttpMethod
import com.example.interception.http.Http2Parser.H2Headers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 task 35: the h2 <-> [ParsedHttpMessage] codec. The re-encoders must recompute
 * `content-length` after every mutation, keep repeated untouched fields, lower-case names, drop
 * connection-specific fields and let a rule-set `Host` become `:authority`.
 */
class Http2MessagesTest {

    private fun requestHeaders(vararg extra: Pair<String, String>, method: String = "POST", path: String = "/v1/items?x=1") = H2Headers(
        1,
        listOf(":method" to method, ":scheme" to "https", ":authority" to "api.example.com", ":path" to path) + extra,
        endStream = false,
    )

    @Test
    fun `request model mirrors what Http2Session persists`() {
        val body = """{"a":1}""".toByteArray()
        val message = Http2Messages.request(requestHeaders("content-type" to "application/json", "cookie" to "a=1", "cookie" to "b=2"), body)
        assertEquals(HttpMethod.POST, message.method)
        assertEquals("https://api.example.com/v1/items?x=1", message.url)
        assertEquals("api.example.com", message.host)
        assertEquals("h2", message.version)
        assertEquals("""{"a":1}""", message.body)
        assertEquals(listOf("content-type" to "application/json", "cookie" to "a=1", "cookie" to "b=2"), message.headerLines)
        assertArrayEquals(body, message.rawBodyBytes)
    }

    @Test
    fun `an untouched request re-encodes with every repeated field kept and no content-length invented`() {
        val original = requestHeaders("cookie" to "a=1", "cookie" to "b=2", "accept" to "*/*", method = "GET")
        val message = Http2Messages.request(original, ByteArray(0))
        val encoded = Http2Messages.encodeRequest(message, original)
        assertEquals(
            listOf(":method" to "GET", ":scheme" to "https", ":path" to "/v1/items?x=1", ":authority" to "api.example.com",
                "cookie" to "a=1", "cookie" to "b=2", "accept" to "*/*"),
            encoded.headers,
        )
        assertEquals(0, encoded.body.size)
        assertTrue(encoded.endsOnHeaders)
    }

    @Test
    fun `header edits collapse the edited name, drop removed names, append new ones lower-cased`() {
        val original = requestHeaders("cookie" to "a=1", "cookie" to "b=2", "x-old" to "1", "accept" to "*/*", method = "GET")
        val message = Http2Messages.request(original, ByteArray(0))
        message.headers = message.headers.toMutableMap().apply {
            put("cookie", "z=9")
            remove("x-old")
            put("X-Added", "yes")
        }
        val encoded = Http2Messages.encodeRequest(message, original)
        val regular = encoded.headers.filter { !it.first.startsWith(":") }
        assertEquals(listOf("cookie" to "z=9", "accept" to "*/*", "x-added" to "yes"), regular)
    }

    @Test
    fun `a replaced body recomputes content-length, drops content-encoding and transfer-encoding`() {
        val original = requestHeaders("content-type" to "text/plain", "content-length" to "4", "content-encoding" to "gzip")
        val message = Http2Messages.request(original, byteArrayOf(1, 2, 3, 4))
        message.body = "hello world"
        message.bodyReplaced = true
        val encoded = Http2Messages.encodeRequest(message, original)
        assertEquals("hello world", String(encoded.body))
        val regular = encoded.headers.filter { !it.first.startsWith(":") }
        assertEquals(listOf("content-type" to "text/plain", "content-length" to "11"), regular)
        assertFalse(encoded.endsOnHeaders)
    }

    @Test
    fun `byte-exact replacement wins over the text body`() {
        val original = requestHeaders("content-length" to "1")
        val message = Http2Messages.request(original, byteArrayOf(0))
        message.body = "ignored"
        message.bodyReplaced = true
        message.replacedBodyBytes = byteArrayOf(0x00, 0x7f, 0x10)
        val encoded = Http2Messages.encodeRequest(message, original)
        assertArrayEquals(byteArrayOf(0x00, 0x7f, 0x10), encoded.body)
        assertEquals("3", encoded.headers.first { it.first == "content-length" }.second)
    }

    @Test
    fun `an untouched body forwards the captured wire bytes even when the text rendering is lossy`() {
        val gz = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x11, 0x22)
        val original = requestHeaders("content-encoding" to "gzip", "content-length" to "6")
        val message = Http2Messages.request(original, gz)
        message.headers = message.headers + ("x-touched" to "1") // header-only edit
        val encoded = Http2Messages.encodeRequest(message, original)
        assertArrayEquals(gz, encoded.body)
        assertEquals("gzip", encoded.headers.first { it.first == "content-encoding" }.second)
        assertEquals("6", encoded.headers.first { it.first == "content-length" }.second)
    }

    @Test
    fun `a redirect moves path and authority and a Host header a rule set wins for the authority`() {
        val original = requestHeaders(method = "GET")
        val message = Http2Messages.request(original, ByteArray(0))
        // What RewriteStage.applyRedirect does with preserveHost = false.
        message.url = "https://mirror.example.net/other/path?q=2"
        message.host = "mirror.example.net"
        message.headers = message.headers + ("host" to "mirror.example.net")
        val encoded = Http2Messages.encodeRequest(message, original)
        assertEquals(":method" to "GET", encoded.headers[0])
        assertEquals(":scheme" to "https", encoded.headers[1])
        assertEquals(":path" to "/other/path?q=2", encoded.headers[2])
        assertEquals(":authority" to "mirror.example.net", encoded.headers[3])
        assertNull(encoded.headers.firstOrNull { it.first == "host" })
    }

    @Test
    fun `a redirect with preserveHost keeps the original authority while the path follows the target`() {
        val original = requestHeaders(method = "GET")
        val message = Http2Messages.request(original, ByteArray(0))
        message.url = "https://elsewhere.example/new"
        message.host = "elsewhere.example"
        val encoded = Http2Messages.encodeRequest(message, original)
        assertEquals(":path" to "/new", encoded.headers[2])
        assertEquals(":authority" to "api.example.com", encoded.headers[3])
    }

    @Test
    fun `connection-specific fields never survive re-encoding`() {
        val original = requestHeaders(method = "GET")
        val message = Http2Messages.request(original, ByteArray(0))
        message.headers = message.headers + mapOf("Connection" to "close", "Transfer-Encoding" to "chunked", "Upgrade" to "h2c", "te" to "gzip")
        val encoded = Http2Messages.encodeRequest(message, original)
        val names = encoded.headers.map { it.first }
        assertFalse("connection" in names); assertFalse("transfer-encoding" in names); assertFalse("upgrade" in names); assertFalse("te" in names)
    }

    @Test
    fun `a synthetic response encodes status, headers and a recomputed content-length`() {
        val response = ParsedHttpMessage(
            isRequest = false, method = null, statusCode = 403, statusMessage = "Blocked by requestr",
            headers = mapOf("Content-Length" to "999", "X-Requestr-Rule" to "block ads"), body = "",
        )
        val encoded = Http2Messages.encodeResponse(response)
        assertEquals(listOf(":status" to "403", "x-requestr-rule" to "block ads", "content-length" to "0"), encoded.headers)
        assertTrue(encoded.endsOnHeaders)

        val mock = ParsedHttpMessage(isRequest = false, method = null, statusCode = 201, headers = mapOf("Content-Type" to "application/json"), body = """{"ok":true}""")
        val encodedMock = Http2Messages.encodeResponse(mock)
        assertEquals(listOf(":status" to "201", "content-type" to "application/json", "content-length" to "11"), encodedMock.headers)
        assertEquals("""{"ok":true}""", String(encodedMock.body))
    }

    @Test
    fun `an edited origin response keeps repeated set-cookie fields and recomputes content-length from the new body`() {
        val original = H2Headers(1, listOf(":status" to "200", "content-type" to "text/plain", "set-cookie" to "a=1", "set-cookie" to "b=2", "content-length" to "5"), false)
        val message = Http2Messages.response(original, "hello".toByteArray())
        message.body = "hello, rewritten"
        message.bodyReplaced = true
        message.headers = message.headers + ("x-requestr-rewrite" to "yes")
        val encoded = Http2Messages.encodeResponse(message, trailers = listOf("grpc-status" to "0"))
        assertEquals(
            listOf(":status" to "200", "content-type" to "text/plain", "set-cookie" to "a=1", "set-cookie" to "b=2", "x-requestr-rewrite" to "yes", "content-length" to "16"),
            encoded.headers,
        )
        assertEquals("hello, rewritten", String(encoded.body))
        assertEquals(listOf("grpc-status" to "0"), encoded.trailers)
    }

    @Test
    fun `bodyless responses keep their declared length and never frame a body`() {
        val head = ParsedHttpMessage(isRequest = false, method = null, statusCode = 200, headers = mapOf("content-length" to "1234", "x-a" to "1"), body = "")
        val encoded = Http2Messages.encodeResponse(head, bodyless = true)
        assertEquals(listOf(":status" to "200", "content-length" to "1234", "x-a" to "1"), encoded.headers)
        assertTrue(encoded.endsOnHeaders)

        val noContent = ParsedHttpMessage(isRequest = false, method = null, statusCode = 204, headers = emptyMap(), body = "ignored")
        assertEquals(listOf(":status" to "204"), Http2Messages.encodeResponse(noContent).headers)
    }
}
