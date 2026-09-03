package com.example.interception.gateway.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class GatewaySessionReplyTest {

    private class FakeSocket(val out: ByteArrayOutputStream = ByteArrayOutputStream()) : Socket() {
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = out
    }

    private fun session(entry: EntryKind): Pair<GatewaySession, FakeSocket> {
        val sock = FakeSocket()
        return GatewaySession(sock, entry, Destination("h", 443), origin = null) to sock
    }

    @Test
    fun `http connect replies are the legacy status lines`() {
        val (s1, o1) = session(EntryKind.HTTP_CONNECT); s1.replyEstablished()
        assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n", o1.out.toString())
        val (s2, o2) = session(EntryKind.HTTP_CONNECT); s2.replyBlocked()
        assertEquals("HTTP/1.1 403 Blocked by requestr\r\n\r\n", o2.out.toString())
        val (s3, o3) = session(EntryKind.HTTP_CONNECT); s3.replyMitmUnavailable()
        assertEquals("HTTP/1.1 403 MITM required but not available\r\n\r\n", o3.out.toString())
        val (s4, o4) = session(EntryKind.HTTP_CONNECT); s4.replyUnreachable()
        assertEquals("HTTP/1.1 502 Bad Gateway\r\n\r\n", o4.out.toString())
    }

    @Test
    fun `socks5 replies are rfc1928 reply frames`() {
        val (s1, o1) = session(EntryKind.SOCKS5); s1.replyEstablished()
        assertArrayEquals(Socks5Handler.reply(0x00), o1.out.toByteArray())
        val (s2, o2) = session(EntryKind.SOCKS5); s2.replyBlocked()
        assertArrayEquals(Socks5Handler.reply(0x02), o2.out.toByteArray())
        val (s3, o3) = session(EntryKind.SOCKS5); s3.replyUnreachable()
        assertArrayEquals(Socks5Handler.reply(0x04), o3.out.toByteArray())
    }

    @Test
    fun `socks4 replies are granted or rejected frames`() {
        val (s1, o1) = session(EntryKind.SOCKS4); s1.replyEstablished()
        assertArrayEquals(Socks4Handler.reply(0x5A), o1.out.toByteArray())
        val (s2, o2) = session(EntryKind.SOCKS4); s2.replyBlocked()
        assertArrayEquals(Socks4Handler.reply(0x5B), o2.out.toByteArray())
    }

    @Test
    fun `transparent and absolute entries write nothing`() {
        for (entry in listOf(EntryKind.TRANSPARENT_TLS, EntryKind.TRANSPARENT_HTTP, EntryKind.HTTP_ABSOLUTE)) {
            val (s, o) = session(entry)
            s.replyEstablished(); s.replyBlocked(); s.replyUnreachable()
            assertEquals(0, o.out.size())
        }
    }

    @Test
    fun `only the first reply is written`() {
        val (s, o) = session(EntryKind.HTTP_CONNECT)
        s.replyEstablished()
        s.replyBlocked()
        assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n", o.out.toString())
    }

    @Test
    fun `destination renders ipv6 with brackets`() {
        assertEquals("[::1]:443", Destination("::1", 443).toString())
        assertEquals("example.com:80", Destination("example.com", 80).toString())
    }
}
