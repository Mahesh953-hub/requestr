package com.example.interception.quic

import com.example.data.local.QuicDecodeStatus
import com.example.interception.tls.TlsKeyLogRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-cipher-suite and key-update coverage of the live 1-RTT decrypt path.
 *
 * QUIC can negotiate three AEAD suites (RFC 9001 §5.3) that differ in HKDF hash, AEAD key size, AEAD
 * algorithm **and** header-protection construction (AES-ECB vs the ChaCha20 keystream, §5.4.3/§5.4.4),
 * so each is exercised end to end against real protected packets rather than assumed to follow from
 * AES-128 working. The key-update cases then prove RFC 9001 §6 behaviour on the production path: a
 * genuine peer-signalled rotation is promoted only after it authenticates, the retired generation keeps
 * decrypting delayed packets, and a spoofed KEY_PHASE flip changes nothing.
 */
class QuicOneRttSuiteTest {

    @Before
    fun setUp() = TlsKeyLogRegistry.clear()

    @After
    fun tearDown() = TlsKeyLogRegistry.clear()

    private fun runRequestResponse(suite: QuicCrypto.CipherSuite, path: String) {
        val scenario = QuicConnectionScenario(suite = suite)
        scenario.registerSecrets()
        scenario.completeHandshake()
        assertEquals("the ServerHello selected this suite", suite, scenario.decoder.negotiatedCipherSuite)

        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", path, "quic.test"))),
        )
        scenario.feedInbound(
            scenario.serverOneRtt(
                0,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame("body-$path".toByteArray()),
                    fin = true,
                ),
            ),
        )

        val exchange = scenario.decoder.exchanges.single()
        assertEquals("GET", exchange.method)
        assertEquals(path, exchange.path)
        assertEquals(200, exchange.statusCode)
        assertEquals("body-$path", String(exchange.responseBody()))
        assertEquals(QuicDecodeStatus.FULLY_DECODED, scenario.decoder.decodeStatus)
    }

    @Test
    fun `AES-128-GCM-SHA256 1-RTT decodes an HTTP-3 exchange`() =
        runRequestResponse(QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, "/aes128")

    @Test
    fun `AES-256-GCM-SHA384 1-RTT decodes an HTTP-3 exchange`() =
        runRequestResponse(QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384, "/aes256")

    @Test
    fun `ChaCha20-Poly1305-SHA256 1-RTT decodes an HTTP-3 exchange`() =
        runRequestResponse(QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, "/chacha")

    @Test
    fun `a genuine key update authenticates promotes and keeps the retired generation decrypting`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        // Generation 0 (KEY_PHASE bit 0) establishes the manager for the inbound direction.
        val first = scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("200"))),
        )
        assertTrue(first.single().decrypted)
        assertEquals(0, scenario.decoder.keyGeneration(QuicDatagramRouter.Direction.INBOUND))

        // Generation 1: the peer's real RFC 9001 §6.1 "quic ku" secret, announced by flipping KEY_PHASE.
        val gen1Secret = QuicWireFixtures.nextKeyUpdateSecret(scenario.serverApplicationSecret, scenario.suite)
        val gen1Keys = requireNotNull(QuicCrypto.deriveKeysFromTrafficSecret(scenario.version, scenario.suite, gen1Secret))
        val rotated = scenario.feedInbound(
            scenario.serverOneRtt(
                packetNumber = 1,
                frames = QuicWireFixtures.streamFrame(0, QuicWireFixtures.responseHeadersFrame("200").size.toLong(), QuicWireFixtures.dataFrame("after-update".toByteArray())),
                keyPhase = 1,
                keys = gen1Keys,
            ),
        )

        assertTrue("the rotated packet authenticated", rotated.single().decrypted)
        assertEquals(1, scenario.decoder.keyUpdatesPromoted)
        assertEquals(1, scenario.decoder.keyGeneration(QuicDatagramRouter.Direction.INBOUND))
        assertEquals("after-update", String(scenario.decoder.exchanges.single().responseBody()))

        // A delayed packet still protected with the retired generation-0 keys must keep decrypting for
        // the bounded retention window (RFC 9001 §6.3), so reordering across a rotation is not data loss.
        val delayed = scenario.feedInbound(scenario.serverOneRtt(2, QuicWireFixtures.ping(), keyPhase = 0))
        assertTrue("a delayed old-phase packet must still decrypt", delayed.single().decrypted)
        assertEquals("no second promotion from an old-phase packet", 1, scenario.decoder.keyUpdatesPromoted)
    }

    @Test
    fun `a spoofed key-phase flip does not promote and leaves the current generation working`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.ping()))
        assertEquals(0, scenario.decoder.keyGeneration(QuicDatagramRouter.Direction.INBOUND))

        // A packet with the KEY_PHASE bit flipped but protected with unrelated keys: auth-before-promotion
        // means it must fail and must not advance the generation.
        val bogusKeys = requireNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(scenario.version, scenario.suite, ByteArray(32) { 0x5c }),
        )
        val spoofed = scenario.feedInbound(scenario.serverOneRtt(1, QuicWireFixtures.ping(), keyPhase = 1, keys = bogusKeys))

        assertFalse(spoofed.single().decrypted)
        assertEquals("no promotion on a failed speculative update", 0, scenario.decoder.keyUpdatesPromoted)
        assertEquals(0, scenario.decoder.keyGeneration(QuicDatagramRouter.Direction.INBOUND))

        // The genuine current generation still decrypts after the rejected flip.
        val genuine = scenario.feedInbound(scenario.serverOneRtt(2, QuicWireFixtures.ping()))
        assertTrue(genuine.single().decrypted)
    }

    @Test
    fun `0-RTT packets decrypt from the early traffic secret`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val earlySecret = ByteArray(scenario.suite.hashLength) { 0x66 }
        TlsKeyLogRegistry.record(
            com.example.interception.tls.KeyLogLabel.CLIENT_EARLY_TRAFFIC_SECRET,
            scenario.clientRandom,
            earlySecret,
        )
        scenario.completeHandshake()
        val earlyKeys = requireNotNull(QuicCrypto.deriveKeysFromTrafficSecret(scenario.version, scenario.suite, earlySecret))

        val results = scenario.feedOutbound(
            QuicWireFixtures.zeroRttPacket(
                version = scenario.version,
                dcid = scenario.originalDcid,
                scid = scenario.clientScid,
                packetNumber = 0,
                frames = QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/early", "quic.test")),
                keys = earlyKeys,
            ),
        )

        assertTrue("0-RTT is keyed by CLIENT_EARLY_TRAFFIC_SECRET", results.single().decrypted)
        assertEquals("/early", scenario.decoder.exchanges.single().path)
    }
}
