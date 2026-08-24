package com.example.interception.tls

import com.example.interception.quic.QuicWireFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the two TLS-side pieces the QUIC 1-RTT decode path depends on.
 *
 * 1. **Key-log identity.** [TlsDecoder.parseClientHello] now retains the ClientHello `random`, which is
 *    the key an NSS-format TLS key log is indexed by. Without it, a registry full of genuinely correct
 *    traffic secrets cannot be matched to the connection observing them, so every 1-RTT packet would be
 *    reported as undecryptable — the exact gap this closes.
 * 2. **ServerHello discovery in a reassembled crypto stream.** QUIC carries the TLS handshake in CRYPTO
 *    frames with no TLS record headers (RFC 9001 §4.1.3), so the negotiated cipher suite has to be found
 *    by walking `type || length || body` handshake messages in a partially-reassembled byte stream —
 *    which [TlsDecoder.findServerHello] must do without throwing on an incomplete tail.
 */
class TlsHandshakeIdentityTest {

    @Test
    fun `parseClientHello retains the ClientHello random as the key-log identity`() {
        val random = ByteArray(32) { (it + 1).toByte() }
        val handshake = QuicWireFixtures.clientHello(random, "identity.test", "h3")

        val hello = requireNotNull(TlsDecoder.parseClientHello(tlsRecord(handshake)))

        assertArrayEquals(random, hello.clientRandom)
        assertEquals("identity.test", hello.sni)
        assertEquals("h3", hello.alpn)
        assertEquals(listOf(0x1301), hello.cipherSuites)
    }

    @Test
    fun `the retained random resolves real registry secrets`() {
        TlsKeyLogRegistry.clear()
        val random = QuicWireFixtures.clientRandom(0x5e)
        val secret = ByteArray(32) { 0x7d }
        TlsKeyLogRegistry.record(KeyLogLabel.SERVER_TRAFFIC_SECRET_0, random, secret)
        val hello = requireNotNull(TlsDecoder.parseClientHello(tlsRecord(QuicWireFixtures.clientHello(random, "lookup.test", "h3"))))

        val resolved = TlsKeyLogRegistry.lookup(KeyLogLabel.SERVER_TRAFFIC_SECRET_0, requireNotNull(hello.clientRandom))

        assertArrayEquals("the ClientHello random is the registry key", secret, resolved)
        assertNull(
            "a different connection's random must not resolve this secret",
            TlsKeyLogRegistry.lookup(KeyLogLabel.SERVER_TRAFFIC_SECRET_0, QuicWireFixtures.clientRandom(0x11)),
        )
        TlsKeyLogRegistry.clear()
    }

    @Test
    fun `the JA3 fingerprint is unchanged by retaining the random`() {
        // JA3 fingerprints offered capabilities, which are stable per client build; the per-connection
        // random must not leak into it or every connection would fingerprint differently.
        val a = requireNotNull(TlsDecoder.parseClientHello(tlsRecord(QuicWireFixtures.clientHello(ByteArray(32) { 0x01 }, "ja3.test", "h3"))))
        val b = requireNotNull(TlsDecoder.parseClientHello(tlsRecord(QuicWireFixtures.clientHello(ByteArray(32) { 0x02 }, "ja3.test", "h3"))))

        assertEquals(a.ja3Hash(), b.ja3Hash())
    }

    @Test
    fun `findServerHello recovers the negotiated suite from a multi-message crypto stream`() {
        // A realistic Initial/Handshake crypto stream: an unrelated message first, then the ServerHello.
        val stream = QuicWireFixtures.encryptedExtensions() + QuicWireFixtures.serverHello(0x1303)

        val serverHello = requireNotNull(TlsDecoder.findServerHello(stream))

        assertEquals(0x1303, serverHello.cipherSuiteId)
        assertEquals("TLS_CHACHA20_POLY1305_SHA256", serverHello.cipherSuiteName)
        assertEquals(0x0304, serverHello.version)
    }

    @Test
    fun `findServerHello returns null for an incomplete stream and succeeds once complete`() {
        val full = QuicWireFixtures.serverHello(0x1301)
        val partial = full.copyOfRange(0, full.size - 6)

        assertNull("a partially reassembled crypto stream is a normal state, not an error", TlsDecoder.findServerHello(partial))
        assertNotNull(TlsDecoder.findServerHello(full))
    }

    @Test
    fun `findServerHello returns null when the stream holds no ServerHello`() {
        assertNull(TlsDecoder.findServerHello(QuicWireFixtures.encryptedExtensions()))
        assertNull(TlsDecoder.findServerHello(ByteArray(0)))
        assertNull(TlsDecoder.findServerHello(QuicWireFixtures.bytes(0x02, 0xFF, 0xFF, 0xFF)))
    }

    @Test
    fun `each negotiable QUIC suite maps to its standard name and others do not`() {
        assertEquals("TLS_AES_128_GCM_SHA256", TlsDecoder.findServerHello(QuicWireFixtures.serverHello(0x1301))?.cipherSuiteName)
        assertEquals("TLS_AES_256_GCM_SHA384", TlsDecoder.findServerHello(QuicWireFixtures.serverHello(0x1302))?.cipherSuiteName)
        assertEquals("TLS_CHACHA20_POLY1305_SHA256", TlsDecoder.findServerHello(QuicWireFixtures.serverHello(0x1303))?.cipherSuiteName)
        assertNull(
            "a TLS 1.2 suite QUIC cannot negotiate must not resolve to a name",
            TlsDecoder.findServerHello(QuicWireFixtures.serverHello(0xc02f))?.cipherSuiteName,
        )
    }

    /** Wraps a raw handshake message in the 5-byte TLS record header [TlsDecoder.parseClientHello] expects. */
    private fun tlsRecord(handshake: ByteArray): ByteArray =
        QuicWireFixtures.bytes(22, 0x03, 0x03, (handshake.size shr 8) and 0xFF, handshake.size and 0xFF) + handshake
}
