package com.example.interception.tls

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies [TlsDecoder.parseServerHello] recovers the negotiated TLS 1.3 cipher suite from a
 * server Handshake CRYPTO frame for each of the three AEAD suites QUIC can negotiate (RFC 9001
 * §5.3), and that [TlsDecoder.mapCipherSuiteId] maps those suite IDs to their standard names while
 * rejecting unknown/GREASE values. A malformed or truncated ServerHello must return null without
 * throwing, matching [TlsDecoder.parseClientHello]'s defensive contract.
 */
class TlsServerHelloTest {

    /**
     * Builds a minimal TLS 1.3 ServerHello handshake message (type 0x02) selecting [cipherSuiteId].
     * Layout (RFC 8446 §4.1.3): type(1) + 3-byte length + version(2, 0x0304) + random(32) +
     * session_id_len(1) + session_id + cipher_suite(2) + compression(1, legacy 0).
     */
    private fun serverHello(cipherSuiteId: Int, sessionId: ByteArray = ByteArray(0)): ByteArray {
        val random = ByteArray(32) { 0xAA.toByte() }
        val body = ByteArray(2 + 32 + 1 + sessionId.size + 2 + 1)
        var p = 0
        body[p++] = 0x03; body[p++] = 0x04 // version 0x0304 (TLS 1.3, per this parser's contract)
        System.arraycopy(random, 0, body, p, 32); p += 32
        body[p++] = sessionId.size.toByte()
        System.arraycopy(sessionId, 0, body, p, sessionId.size); p += sessionId.size
        body[p++] = ((cipherSuiteId shr 8) and 0xFF).toByte()
        body[p++] = (cipherSuiteId and 0xFF).toByte()
        body[p] = 0 // legacy_compression_methods, must be a single zero byte
        val header = byteArrayOf(
            0x02,
            ((body.size shr 16) and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        )
        return header + body
    }

    @Test
    fun `parseServerHello extracts TLS_AES_128_GCM_SHA256`() {
        val sh = serverHello(0x1301)
        val result = TlsDecoder.parseServerHello(sh, 0, sh.size)
        assertNotNull(result)
        assertEquals(0x0304, result!!.version)
        assertEquals(0x1301, result.cipherSuiteId)
        assertEquals("TLS_AES_128_GCM_SHA256", result.cipherSuiteName)
    }

    @Test
    fun `parseServerHello extracts TLS_AES_256_GCM_SHA384`() {
        val sh = serverHello(0x1302)
        val result = TlsDecoder.parseServerHello(sh, 0, sh.size)
        assertNotNull(result)
        assertEquals(0x1302, result!!.cipherSuiteId)
        assertEquals("TLS_AES_256_GCM_SHA384", result.cipherSuiteName)
    }

    @Test
    fun `parseServerHello extracts TLS_CHACHA20_POLY1305_SHA256`() {
        val sh = serverHello(0x1303)
        val result = TlsDecoder.parseServerHello(sh, 0, sh.size)
        assertNotNull(result)
        assertEquals(0x1303, result!!.cipherSuiteId)
        assertEquals("TLS_CHACHA20_POLY1305_SHA256", result.cipherSuiteName)
    }

    @Test
    fun `parseServerHello honours offset and length for a ServerHello embedded in larger crypto data`() {
        val sh = serverHello(0x1301)
        val crypto = ByteArray(7) { 0x00 } + sh + ByteArray(5) { 0x00 }
        val result = TlsDecoder.parseServerHello(crypto, 7, sh.size)
        assertNotNull(result)
        assertEquals(0x1301, result!!.cipherSuiteId)
        assertEquals("TLS_AES_128_GCM_SHA256", result.cipherSuiteName)
    }

    @Test
    fun `parseServerHello skips a non-empty legacy_session_id echo to reach the cipher suite`() {
        val sessionId = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val sh = serverHello(0x1302, sessionId)
        val result = TlsDecoder.parseServerHello(sh, 0, sh.size)
        assertNotNull(result)
        assertEquals(0x1302, result!!.cipherSuiteId)
        assertEquals("TLS_AES_256_GCM_SHA384", result.cipherSuiteName)
    }

    @Test
    fun `parseServerHello reports null cipherSuiteName for an unrecognized suite without failing`() {
        val sh = serverHello(0x1304) // no standard TLS 1.3 AEAD suite has this id
        val result = TlsDecoder.parseServerHello(sh, 0, sh.size)
        assertNotNull(result)
        assertEquals(0x1304, result!!.cipherSuiteId)
        assertNull("an unrecognized suite must yield a null name, not a throw", result.cipherSuiteName)
    }

    @Test
    fun `parseServerHello returns null for a non-ServerHello handshake type`() {
        val sh = serverHello(0x1301)
        sh[0] = 0x01 // ClientHello type
        assertNull(TlsDecoder.parseServerHello(sh, 0, sh.size))
    }

    @Test
    fun `parseServerHello returns null for a non-TLS-1_3 version`() {
        val sh = serverHello(0x1301)
        sh[5] = 0x03 // version low byte 0x04 -> 0x03, making 0x0303 -- rejected by the 0x0304 contract
        assertNull(TlsDecoder.parseServerHello(sh, 0, sh.size))
    }

    @Test
    fun `parseServerHello returns null for a truncated buffer without throwing`() {
        val sh = serverHello(0x1301)
        assertNull(TlsDecoder.parseServerHello(sh, 0, 10)) // too short to hold the body
        assertNull(TlsDecoder.parseServerHello(ByteArray(0), 0, 0))
        // length shorter than the session_id echo implies: cipher suite would read past `end`.
        assertNull(TlsDecoder.parseServerHello(sh, 0, 39))
    }

    @Test
    fun `mapCipherSuiteId maps the three TLS 1_3 AEAD suites and nulls the rest`() {
        assertEquals("TLS_AES_128_GCM_SHA256", TlsDecoder.mapCipherSuiteId(0x1301))
        assertEquals("TLS_AES_256_GCM_SHA384", TlsDecoder.mapCipherSuiteId(0x1302))
        assertEquals("TLS_CHACHA20_POLY1305_SHA256", TlsDecoder.mapCipherSuiteId(0x1303))
        assertNull(TlsDecoder.mapCipherSuiteId(0x00FF)) // a TLS 1.2 suite id
        assertNull(TlsDecoder.mapCipherSuiteId(0x0A0A)) // a GREASE value
    }
}
