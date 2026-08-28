package com.example.interception.tls

import com.example.interception.quic.QuicWireFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Unit tests for [ClientHelloCapture]: peeking a real ClientHello TLS record off a socket's
 * input stream before JSSE consumes it, and replaying those exact bytes back out through
 * [PeekedSocket] so the handshake is unaffected.
 *
 * [QuicWireFixtures.clientHello] builds a real, structurally-valid TLS 1.3 ClientHello
 * handshake message (RFC 8446 §4.1.2) -- the same fixture [TlsHandshakeIdentityTest] relies on
 * for [TlsDecoder.parseClientHello] coverage -- rather than a canned capture.
 */
class ClientHelloCaptureTest {

    private fun tlsRecord(handshake: ByteArray): ByteArray =
        byteArrayOf(22, 0x03, 0x03, ((handshake.size shr 8) and 0xFF).toByte(), (handshake.size and 0xFF).toByte()) + handshake

    /** A [Socket] whose [getInputStream]/[getOutputStream] are fixed, in-memory streams. */
    private class FakeSocket(private val input: ByteArrayInputStream, private val output: OutputStream = ByteArrayOutputStream()) : Socket() {
        override fun getInputStream() = input
        override fun getOutputStream() = output
    }

    @Test
    fun `peekClientHello parses a real ClientHello and matches an independent parse of the same bytes`() {
        val random = ByteArray(32) { (it * 3 + 1).toByte() }
        val handshake = QuicWireFixtures.clientHello(random, "peek.example.com", "h2")
        val record = tlsRecord(handshake)
        // Extra bytes JSSE would read afterwards (e.g. more of the flight) must survive the peek.
        val trailing = byteArrayOf(9, 9, 9, 9)
        val socket = FakeSocket(ByteArrayInputStream(record + trailing))

        val result = ClientHelloCapture.peekClientHello(socket)

        val expected = requireNotNull(TlsDecoder.parseClientHello(record))
        assertEquals(expected.ja3Hash(), result.clientHello?.ja3Hash())
        assertEquals("peek.example.com", result.clientHello?.sni)
        assertEquals("h2", result.clientHello?.alpn)
        assertArrayEquals(random, result.clientHello?.clientRandom)
    }

    @Test
    fun `replayed socket reproduces the exact peeked bytes followed by the rest of the real stream`() {
        val handshake = QuicWireFixtures.clientHello(ByteArray(32) { 0x42 }, "replay.example.com", "http/1.1")
        val record = tlsRecord(handshake)
        val trailing = "rest-of-flight".toByteArray()
        val socket = FakeSocket(ByteArrayInputStream(record + trailing))

        val result = ClientHelloCapture.peekClientHello(socket)

        val replayed = result.socket.getInputStream().readBytes()
        assertArrayEquals(record + trailing, replayed)
    }

    @Test
    fun `non-TLS garbage input returns a null ClientHello without throwing and still replays bytes`() {
        val garbage = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val socket = FakeSocket(ByteArrayInputStream(garbage))

        val result = ClientHelloCapture.peekClientHello(socket)

        assertNull(result.clientHello)
        val replayed = result.socket.getInputStream().readBytes()
        assertArrayEquals(garbage, replayed)
    }

    @Test
    fun `empty stream (immediate EOF) does not throw and yields a null ClientHello`() {
        val socket = FakeSocket(ByteArrayInputStream(ByteArray(0)))
        val result = ClientHelloCapture.peekClientHello(socket)
        assertNull(result.clientHello)
        assertArrayEquals(ByteArray(0), result.socket.getInputStream().readBytes())
    }

    @Test
    fun `a record header claiming an absurdly large body is not peeked past the guard`() {
        // content-type=handshake, version, then a declared length far larger than any real
        // ClientHello -- must not cause an unbounded/blocking read.
        val header = byteArrayOf(22, 0x03, 0x03, 0x7F.toByte(), 0xFF.toByte()) // len = 0x7FFF
        val socket = FakeSocket(ByteArrayInputStream(header))
        val result = ClientHelloCapture.peekClientHello(socket)
        assertNull(result.clientHello)
        // Only the 5 header bytes were consumed/replayed -- the (nonexistent) oversized body was
        // never attempted.
        assertArrayEquals(header, result.socket.getInputStream().readBytes())
    }

    @Test
    fun `getOutputStream on the peeked socket still delegates to the real socket`() {
        val out = ByteArrayOutputStream()
        val socket = FakeSocket(ByteArrayInputStream(ByteArray(0)), out)
        val result = ClientHelloCapture.peekClientHello(socket)
        result.socket.getOutputStream().write(byteArrayOf(1, 2, 3))
        assertTrue(out.toByteArray().contentEquals(byteArrayOf(1, 2, 3)))
    }
}
