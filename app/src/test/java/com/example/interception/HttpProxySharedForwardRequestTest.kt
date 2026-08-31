package com.example.interception

import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket

/**
 * Fix #31 follow-up: [HttpProxyShared.forwardRequest] used to dial upstream via
 * `HttpURLConnection`, which Android's Network Security Config refuses for cleartext HTTP to any
 * host other than loopback/emulator-host -- every real-world plain-HTTP forward through the
 * HTTP_PROXY tier failed in ~1ms with "Cleartext HTTP traffic to <host> not permitted", even
 * though CONNECT/HTTPS through the same proxy worked fine. It now dials upstream with a raw
 * [Socket] (not governed by Network Security Config), so these tests drive a real loopback
 * [ServerSocket] standing in for "upstream" and assert the request it faithfully forwards and the
 * response it faithfully relays back -- including framing (`Content-Length` vs
 * `Transfer-Encoding: chunked` vs read-until-EOF), hop-by-hop header stripping, correct reason
 * phrases, and binary-safe (non-UTF-8) bodies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class HttpProxySharedForwardRequestTest {

    /** A one-shot fake upstream: accepts a single connection, captures the request it received
     * (status line consumed by the caller, headers + body captured here), then writes back
     * [rawResponseBytes] verbatim and closes. */
    private class FakeUpstream(private val rawResponseBytes: ByteArray) {
        val server = ServerSocket(0)
        val port: Int get() = server.localPort

        @Volatile var requestLine: String? = null
        @Volatile var requestHeaders: Map<String, String> = emptyMap()
        @Volatile var requestBody: ByteArray = ByteArray(0)

        private val thread = Thread {
            runCatching {
                server.accept().use { sock ->
                    val input = sock.getInputStream()
                    requestLine = HttpProxyShared.readLine(input)
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = HttpProxyShared.readLine(input) ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                    }
                    requestHeaders = headers
                    val len = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
                    requestBody = if (len > 0) HttpProxyShared.readExactBytes(input, len) else ByteArray(0)
                    sock.getOutputStream().write(rawResponseBytes)
                    sock.getOutputStream().flush()
                }
            }
        }

        fun start(): FakeUpstream = also { thread.start() }
        fun awaitDone() { thread.join(5_000) }
        fun close() { runCatching { server.close() } }
    }

    private fun crlf(vararg lines: String) = lines.joinToString("\r\n", postfix = "\r\n\r\n")

    @Test
    fun `forwards request line and headers, strips hop-by-hop, adds correct Content-Length`() {
        val upstream = FakeUpstream(
            (crlf("HTTP/1.1 200 OK", "Content-Length: 2") + "hi").toByteArray()
        ).start()

        val headers = mapOf(
            "Host" to "example.com",
            "X-Custom" to "abc",
            "Proxy-Connection" to "keep-alive",
            "Connection" to "keep-alive",
            "Content-Length" to "999", // deliberately wrong -- must be recomputed from the real body
        )
        val body = "payload".toByteArray(Charsets.UTF_8)

        val (status, _, respBody) = HttpProxyShared.forwardRequest(
            "http://127.0.0.1:${upstream.port}/path?x=1", "POST", headers, body,
        )
        upstream.awaitDone()
        upstream.close()

        assertEquals(200, status)
        assertArrayEquals("hi".toByteArray(), respBody)
        assertEquals("POST /path?x=1 HTTP/1.1", upstream.requestLine)
        assertFalse("Proxy-Connection must not be forwarded", upstream.requestHeaders.keys.any { it.equals("Proxy-Connection", true) })
        // We always send our own "Connection: close" (fresh socket per call, no pooling) -- the
        // client's original "keep-alive" value must not have been blindly forwarded verbatim.
        assertEquals("close", upstream.requestHeaders["Connection"])
        assertEquals("example.com", upstream.requestHeaders["Host"])
        assertEquals("abc", upstream.requestHeaders["X-Custom"])
        // The client's (wrong) Content-Length must have been discarded and recomputed from the
        // actual body bytes we sent, not forwarded verbatim.
        assertEquals(body.size.toString(), upstream.requestHeaders["Content-Length"])
        assertArrayEquals(body, upstream.requestBody)
    }

    @Test
    fun `synthesizes a Host header from the request target when the client omitted one`() {
        val upstream = FakeUpstream((crlf("HTTP/1.1 204 No Content")).toByteArray()).start()

        val (status, _, _) = HttpProxyShared.forwardRequest(
            "http://127.0.0.1:${upstream.port}/", "GET", emptyMap(), ByteArray(0),
        )
        upstream.awaitDone()
        upstream.close()

        assertEquals(204, status)
        assertEquals("127.0.0.1:${upstream.port}", upstream.requestHeaders["Host"])
    }

    @Test
    fun `decodes a chunked-transfer-encoded response body`() {
        val chunked = crlf("HTTP/1.1 200 OK", "Transfer-Encoding: chunked") +
            "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        val upstream = FakeUpstream(chunked.toByteArray(Charsets.ISO_8859_1)).start()

        val (status, headers, respBody) = HttpProxyShared.forwardRequest(
            "http://127.0.0.1:${upstream.port}/", "GET", emptyMap(), ByteArray(0),
        )
        upstream.awaitDone()
        upstream.close()

        assertEquals(200, status)
        assertEquals("hello world", String(respBody, Charsets.UTF_8))
        // Transfer-Encoding describes wire framing that's already been fully decoded into
        // respBody -- forwarding it to our own client (who gets a Content-Length instead) would
        // corrupt the client's framing, so it must not appear in the returned header map.
        assertNull(headers.entries.firstOrNull { it.key.equals("Transfer-Encoding", true) })
    }

    @Test
    fun `falls back to read-until-EOF when upstream declares neither Content-Length nor chunked`() {
        val upstream = FakeUpstream(
            (crlf("HTTP/1.1 200 OK") + "no length header, closed at EOF").toByteArray()
        ).start()

        val (status, _, respBody) = HttpProxyShared.forwardRequest(
            "http://127.0.0.1:${upstream.port}/", "GET", emptyMap(), ByteArray(0),
        )
        upstream.awaitDone()
        upstream.close()

        assertEquals(200, status)
        assertEquals("no length header, closed at EOF", String(respBody, Charsets.UTF_8))
    }

    @Test
    fun `HEAD response has no body even if a stale Content-Length is present`() {
        // A real server that got a HEAD request shouldn't send a body at all; this asserts the
        // proxy honors that per RFC 7230 §3.3.3 rather than trying to read phantom bytes and hang.
        val upstream = FakeUpstream(crlf("HTTP/1.1 200 OK", "Content-Length: 123").toByteArray()).start()

        val (status, _, respBody) = HttpProxyShared.forwardRequest(
            "http://127.0.0.1:${upstream.port}/", "HEAD", emptyMap(), ByteArray(0),
        )
        upstream.awaitDone()
        upstream.close()

        assertEquals(200, status)
        assertEquals(0, respBody.size)
    }

    @Test
    fun `round-trips a binary (non-UTF-8) body exactly, in both directions`() {
        val binaryResponse = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x80.toByte(), 0x7F, 0x00)
        val responseBytes = crlf("HTTP/1.1 200 OK", "Content-Length: ${binaryResponse.size}")
            .toByteArray(Charsets.ISO_8859_1) + binaryResponse
        val upstream = FakeUpstream(responseBytes).start()

        val binaryRequest = byteArrayOf(0x00, 0x10, 0xAA.toByte(), 0xFE.toByte(), 0x00, 0x99.toByte())
        val (status, _, respBody) = HttpProxyShared.forwardRequest(
            "http://127.0.0.1:${upstream.port}/", "POST", mapOf("Content-Type" to "application/octet-stream"), binaryRequest,
        )
        upstream.awaitDone()
        upstream.close()

        assertEquals(200, status)
        assertArrayEquals(binaryRequest, upstream.requestBody)
        assertArrayEquals(binaryResponse, respBody)
    }

    @Test
    fun `an unreachable upstream degrades to an honest 502, not a crash or a hang`() {
        // Bind then immediately close -- guarantees nothing is listening on this port.
        val deadPort = ServerSocket(0).let { it.localPort.also { _ -> it.close() } }

        val (status, headers, respBody) = HttpProxyShared.forwardRequest(
            "http://127.0.0.1:$deadPort/", "GET", emptyMap(), ByteArray(0),
        )

        assertEquals(502, status)
        assertTrue(headers.containsKey("X-Proxy-Error"))
        assertTrue(String(respBody, Charsets.UTF_8).contains("error"))
    }

    @Test
    fun `origin-form request targets are resolved via the Host header`() {
        val upstream = FakeUpstream(crlf("HTTP/1.1 200 OK", "Content-Length: 0").toByteArray()).start()

        val (status, _, _) = HttpProxyShared.forwardRequest(
            "/some/path", "GET", mapOf("Host" to "127.0.0.1:${upstream.port}"), ByteArray(0),
        )
        upstream.awaitDone()
        upstream.close()

        assertEquals(200, status)
        assertEquals("GET /some/path HTTP/1.1", upstream.requestLine)
    }

    @Test
    fun `writeResponse emits the correct reason phrase, not a hardcoded OK`() {
        val serverSocket = ServerSocket(0)
        val received = StringBuilder()
        val thread = Thread {
            serverSocket.accept().use { sock ->
                val input = sock.getInputStream()
                var line = HttpProxyShared.readLine(input)
                while (line != null) {
                    received.append(line).append('\n')
                    if (line.isEmpty()) break
                    line = HttpProxyShared.readLine(input)
                }
            }
        }
        thread.start()

        Socket("127.0.0.1", serverSocket.localPort).use { client ->
            HttpProxyShared.writeResponse(
                client.getOutputStream(), "HTTP/1.1", 502, mapOf("X-Proxy-Error" to "boom"), ByteArray(0),
            )
        }
        thread.join(5_000)
        serverSocket.close()

        assertTrue("expected a real reason phrase, not 'OK': ${received}", received.contains("HTTP/1.1 502 Bad Gateway"))
    }

    @Test
    fun `reasonPhraseFor returns standard phrases for common codes and a fallback for unknown ones`() {
        assertEquals("OK", HttpProxyShared.reasonPhraseFor(200))
        assertEquals("Not Found", HttpProxyShared.reasonPhraseFor(404))
        assertEquals("Bad Gateway", HttpProxyShared.reasonPhraseFor(502))
        assertEquals("Unknown Status", HttpProxyShared.reasonPhraseFor(499))
    }
}
