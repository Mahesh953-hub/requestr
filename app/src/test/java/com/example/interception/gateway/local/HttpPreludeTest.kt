package com.example.interception.gateway.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpPreludeTest {

    private fun parsed(wire: String): HttpPrelude.Head =
        (HttpPrelude.read(ByteArrayInputStream(wire.toByteArray(Charsets.ISO_8859_1))) as HttpPrelude.Result.Parsed).head

    @Test
    fun `read stops exactly at the blank line and keeps the raw head bytes`() {
        val head = "GET http://example.com/a?b=1 HTTP/1.1\r\nHost: example.com\r\nX-Y: z\r\n\r\n"
        val body = "BODYBYTES"
        val input = ByteArrayInputStream((head + body).toByteArray())
        val result = HttpPrelude.read(input) as HttpPrelude.Result.Parsed
        assertArrayEquals(head.toByteArray(), result.head.raw)
        assertEquals(body, String(input.readBytes()))
        assertEquals("GET", result.head.method)
        assertEquals("http://example.com/a?b=1", result.head.target)
        assertEquals("HTTP/1.1", result.head.version)
        assertEquals("example.com", result.head.host)
        assertEquals("z", result.head.headers["X-Y"])
    }

    @Test
    fun `connect destination defaults to 443 and handles explicit ports and ipv6`() {
        assertEquals(Destination("example.com", 443), HttpPrelude.connectDestination(parsed("CONNECT example.com HTTP/1.1\r\n\r\n")))
        assertEquals(Destination("example.com", 8443), HttpPrelude.connectDestination(parsed("CONNECT example.com:8443 HTTP/1.1\r\n\r\n")))
        assertEquals(Destination("2001:db8::1", 443), HttpPrelude.connectDestination(parsed("CONNECT [2001:db8::1]:443 HTTP/1.1\r\n\r\n")))
        assertNull(HttpPrelude.connectDestination(parsed("CONNECT example.com:abc HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `absolute destination derives port from scheme`() {
        assertEquals(Destination("example.com", 80), HttpPrelude.absoluteDestination(parsed("GET http://example.com/ HTTP/1.1\r\n\r\n")))
        assertEquals(Destination("example.com", 443), HttpPrelude.absoluteDestination(parsed("GET https://example.com/ HTTP/1.1\r\n\r\n")))
        assertEquals(Destination("example.com", 8080), HttpPrelude.absoluteDestination(parsed("GET http://example.com:8080/x HTTP/1.1\r\n\r\n")))
        assertNull(HttpPrelude.absoluteDestination(parsed("GET http:///nohost HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `host header destination is used for transparent plaintext`() {
        assertEquals(Destination("svc.internal", 80), HttpPrelude.hostHeaderDestination(parsed("GET /health HTTP/1.1\r\nHost: svc.internal\r\n\r\n")))
        assertEquals(Destination("svc.internal", 8081), HttpPrelude.hostHeaderDestination(parsed("GET /health HTTP/1.1\r\nhost: svc.internal:8081\r\n\r\n")))
        assertNull(HttpPrelude.hostHeaderDestination(parsed("GET /health HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `absolute url reconstruction omits default ports`() {
        assertEquals("http://h/p", HttpPrelude.absoluteUrlFor(Destination("h", 80), "/p", "http"))
        assertEquals("http://h:8080/p", HttpPrelude.absoluteUrlFor(Destination("h", 8080), "/p", "http"))
        assertEquals("https://h/", HttpPrelude.absoluteUrlFor(Destination("h", 443), "/", "https"))
        assertEquals("http://[::1]:8080/x", HttpPrelude.absoluteUrlFor(Destination("::1", 8080), "x", "http"))
    }

    @Test
    fun `malformed heads and eof are reported not thrown`() {
        assertTrue(HttpPrelude.read(ByteArrayInputStream(ByteArray(0))) is HttpPrelude.Result.Eof)
        assertTrue(HttpPrelude.read(ByteArrayInputStream("GET / HTTP/1.1\r\nHost".toByteArray())) is HttpPrelude.Result.Malformed)
        assertTrue(HttpPrelude.read(ByteArrayInputStream("GARBAGE\r\n\r\n".toByteArray())) is HttpPrelude.Result.Malformed)
        assertTrue(HttpPrelude.read(ByteArrayInputStream("GET / SPDY/3\r\n\r\n".toByteArray())) is HttpPrelude.Result.Malformed)
        assertTrue(HttpPrelude.read(ByteArrayInputStream("GET / HTTP/1.1\r\nnocolon\r\n\r\n".toByteArray())) is HttpPrelude.Result.Malformed)
    }

    @Test
    fun `oversized head is rejected instead of buffered forever`() {
        val huge = "GET / HTTP/1.1\r\n" + "X: " + "a".repeat(HttpPrelude.MAX_HEAD_BYTES) + "\r\n\r\n"
        assertTrue(HttpPrelude.read(ByteArrayInputStream(huge.toByteArray())) is HttpPrelude.Result.Malformed)
    }
}
