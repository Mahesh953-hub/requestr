package com.example.interception.gateway.local

import com.example.interception.gateway.local.ProtocolClassifier.Classification
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolClassifierTest {

    private fun classify(s: String) = ProtocolClassifier.classify(s.toByteArray(Charsets.ISO_8859_1))
    private fun classify(vararg b: Int) = ProtocolClassifier.classify(ByteArray(b.size) { b[it].toByte() })

    @Test
    fun `socks5 greeting is classified from its version byte alone`() {
        assertEquals(Classification.Socks5, classify(0x05))
        assertEquals(Classification.Socks5, classify(0x05, 0x01, 0x00))
    }

    @Test
    fun `socks4 request is classified from its version byte alone`() {
        assertEquals(Classification.Socks4, classify(0x04, 0x01, 0x01, 0xBB))
    }

    @Test
    fun `tls handshake record needs two bytes and requires major version 3`() {
        assertEquals(Classification.NeedMore, classify(0x16))
        assertEquals(Classification.Tls, classify(0x16, 0x03, 0x01))
        assertEquals(Classification.Tls, classify(0x16, 0x03, 0x03, 0x00, 0x80))
        assertEquals(Classification.Unknown, classify(0x16, 0x02))
    }

    @Test
    fun `http CONNECT is recognised as a tunnel entry`() {
        assertEquals(Classification.HttpConnect, classify("CONNECT example.com:443 HTTP/1.1\r\n"))
        assertEquals(Classification.HttpConnect, classify("CONNECT "))
    }

    @Test
    fun `absolute-form request target is a forward-proxy entry`() {
        assertEquals(Classification.HttpAbsolute, classify("GET http://example.com/ HTTP/1.1\r\n"))
        assertEquals(Classification.HttpAbsolute, classify("POST https://api.example.com/v1 HTTP/1.1\r\n"))
        assertEquals(Classification.HttpAbsolute, classify("GET HTTP://UPPER.example/ HTTP/1.1"))
    }

    @Test
    fun `origin-form request target is a transparent plaintext entry`() {
        assertEquals(Classification.HttpOrigin, classify("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"))
        assertEquals(Classification.HttpOrigin, classify("PROPFIND /dav/ HTTP/1.1"))
        assertEquals(Classification.HttpOrigin, classify("OPTIONS * HTTP/1.1"))
    }

    @Test
    fun `truncated first read asks for more instead of guessing`() {
        assertEquals(Classification.NeedMore, classify("G"))
        assertEquals(Classification.NeedMore, classify("GET"))
        assertEquals(Classification.NeedMore, classify("GET "))
        assertEquals(Classification.NeedMore, classify("GET htt"))
        assertEquals(Classification.NeedMore, classify("GET https:/"))
        assertEquals(Classification.NeedMore, classify("PROPFIND"))
        assertEquals(Classification.NeedMore, ProtocolClassifier.classify(ByteArray(0)))
    }

    @Test
    fun `non-http printable garbage and binary garbage are unknown`() {
        assertEquals(Classification.Unknown, classify("SSH-2.0-OpenSSH_9.6\r\n"))
        assertEquals(Classification.Unknown, classify("GET ftp://x/ HTTP/1.1"))
        assertEquals(Classification.Unknown, classify("FOO / HTTP/1.1"))
        assertEquals(Classification.Unknown, classify(0x00, 0x00, 0x00, 0x2c))
        assertEquals(Classification.Unknown, classify(0xFF, 0xFE))
        assertEquals(Classification.Unknown, classify("\r\nGET / HTTP/1.1"))
    }

    @Test
    fun `length parameter limits the bytes considered`() {
        val buf = "CONNECT x:443 HTTP/1.1".toByteArray()
        assertEquals(Classification.NeedMore, ProtocolClassifier.classify(buf, 3))
        assertEquals(Classification.HttpConnect, ProtocolClassifier.classify(buf, 8))
    }
}
