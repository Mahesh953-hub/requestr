package com.example.interception.quic

import com.example.data.local.QuicDecodeStatus
import com.example.interception.tls.TlsKeyLogRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State-poisoning resistance for the live decode path.
 *
 * A passive observer reads connection IDs, their lengths, and the QUIC version out of **long headers that
 * have not been authenticated yet** — a long header's CIDs and version are in the clear and anyone who can
 * put a datagram on the connection's 5-tuple can choose them. Committing those observations as fact means
 * one injected datagram can permanently stop a real connection from being decoded:
 *
 *  - a bogus Source Connection ID length makes every later 1-RTT packet's packet-number field be read at
 *    the wrong offset, so nothing authenticates;
 *  - a bogus version discards traffic keys and re-derives them under the wrong `quic`/`quicv2` HKDF
 *    labels, so nothing authenticates;
 *  - a bogus Initial DCID becomes the value both directions' Initial secrets derive from, so the
 *    ServerHello never decrypts and the connection never gets a cipher suite.
 *
 * Each case below injects exactly that and asserts the connection still decodes. The rule the decoder
 * follows is: commit a mutation only once something authenticated under it, and until then keep every
 * observation as a recoverable candidate.
 */
class QuicStatePoisoningTest {

    @Before
    fun setUp() = TlsKeyLogRegistry.clear()

    @After
    fun tearDown() = TlsKeyLogRegistry.clear()

    @Test
    fun `an injected long header with a bogus SCID length does not break 1-RTT decryption`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        // Injected inbound Handshake packet advertising a 3-byte SCID and protected with unrelated keys:
        // it cannot decrypt, but its header is what a naive decoder would learn the DCID length from.
        val bogusKeys = requireNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(scenario.version, scenario.suite, ByteArray(32) { 0x6b }),
        )
        scenario.feedInbound(
            QuicWireFixtures.handshakePacket(
                version = scenario.version,
                dcid = scenario.clientScid,
                scid = QuicWireFixtures.hex("aabbcc"), // wrong length: real server SCID is 8 bytes
                packetNumber = 9,
                frames = QuicWireFixtures.padding(16),
                keys = bogusKeys,
            ),
        )

        // The genuine client 1-RTT packet still addresses the server's real 8-byte CID.
        val results = scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/recovered", "quic.test"))),
        )

        assertTrue("an unauthenticated header must not dictate the DCID length", results.single().decrypted)
        assertEquals("/recovered", scenario.decoder.exchanges.single().path)
    }

    @Test
    fun `an injected version does not discard proven key material`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        // Prove the version with a real 1-RTT decrypt first.
        assertTrue(scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.ping())).single().decrypted)
        assertTrue(scenario.decoder.versionConfirmed)

        // Injected Handshake header claiming QUIC v2. Adopting it would re-derive every key under the
        // `quicv2` labels and silently break the connection.
        val bogusKeys = requireNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(QuicCrypto.VERSION_2, scenario.suite, ByteArray(32) { 0x4e }),
        )
        scenario.feedInbound(
            QuicWireFixtures.handshakePacket(
                version = QuicCrypto.VERSION_2,
                dcid = scenario.clientScid,
                scid = scenario.serverScid,
                packetNumber = 3,
                frames = QuicWireFixtures.padding(16),
                keys = bogusKeys,
            ),
        )

        assertEquals("a proven version is immutable", QuicCrypto.VERSION_1, scenario.decoder.version)
        assertEquals(1, scenario.decoder.versionMismatchesIgnored)
        assertEquals("no key material was discarded", 0, scenario.decoder.versionCorrections)

        val results = scenario.feedInbound(
            scenario.serverOneRtt(
                1,
                QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame("still-here".toByteArray())),
            ),
        )
        assertTrue("the connection still decrypts after the injection", results.single().decrypted)
        assertEquals("still-here", String(scenario.decoder.exchanges.single().responseBody()))
    }

    @Test
    fun `a version claimed before anything is proven is still adopted`() {
        // The legitimate correction path must keep working: a connection first seen through a short header
        // has no version at all, and nothing has authenticated yet, so a long header's version is adopted.
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()

        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.ping())) // no version on the wire
        assertFalse(scenario.decoder.versionConfirmed)

        scenario.feedOutbound(scenario.clientInitial())

        assertEquals(QuicCrypto.VERSION_1, scenario.decoder.version)
        assertTrue("a real Initial decrypt proves the version", scenario.decoder.versionConfirmed)
    }

    @Test
    fun `an injected Initial's DCID does not prevent the real ServerHello from decrypting`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()

        // An injected Initial lands first, so its DCID becomes the initial working candidate.
        val injectedDcid = QuicWireFixtures.hex("1234567890abcdef")
        scenario.feedOutbound(
            QuicWireFixtures.initialPacket(
                version = scenario.version,
                dcid = injectedDcid,
                scid = QuicWireFixtures.hex("cafebabe"),
                packetNumber = 0,
                frames = QuicWireFixtures.padding(32),
                keys = requireNotNull(QuicCrypto.deriveClientInitialKeys(scenario.version, injectedDcid)),
            ),
        )
        scenario.feedOutbound(scenario.clientInitial())

        // The server's Initial is keyed from the *real* original DCID; the decoder must try candidates.
        scenario.feedInbound(scenario.serverInitial())

        assertEquals(
            "the ServerHello decoded despite the injected candidate",
            QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256,
            scenario.decoder.negotiatedCipherSuite,
        )
        assertArrayEquals(scenario.originalDcid, scenario.decoder.originalDcid)
        assertTrue("a decrypt under the candidate proves it", scenario.decoder.originalDcidConfirmed)
    }

    @Test
    fun `a proven original DCID is not displaced by a later injected Initial`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        scenario.feedInbound(scenario.serverInitial()) // proves the original DCID
        assertTrue(scenario.decoder.originalDcidConfirmed)

        val injectedDcid = QuicWireFixtures.hex("0f0f0f0f0f0f0f0f")
        scenario.feedOutbound(
            QuicWireFixtures.initialPacket(
                version = scenario.version,
                dcid = injectedDcid,
                scid = QuicWireFixtures.hex("11112222"),
                packetNumber = 5,
                frames = QuicWireFixtures.padding(32),
                keys = requireNotNull(QuicCrypto.deriveClientInitialKeys(scenario.version, injectedDcid)),
            ),
        )

        assertArrayEquals(
            "a proven original DCID is immutable",
            scenario.originalDcid,
            scenario.decoder.originalDcid,
        )

        // And the connection still completes: handshake keys and 1-RTT continue to work.
        scenario.feedInbound(scenario.serverHandshake())
        val results = scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("200"))),
        )
        assertTrue(results.single().decrypted)
        assertEquals(QuicDecodeStatus.FULLY_DECODED, scenario.decoder.decodeStatus)
    }

    @Test
    fun `an injected Handshake packet with an unknown secret cannot lower the decode status`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("200"))),
        )
        assertEquals(QuicDecodeStatus.FULLY_DECODED, scenario.decoder.decodeStatus)

        val bogusKeys = requireNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(scenario.version, scenario.suite, ByteArray(32) { 0x21 }),
        )
        scenario.feedInbound(scenario.serverHandshake(packetNumber = 7, keys = bogusKeys))

        assertEquals(
            "a failed injected packet must not rewrite what was really decoded",
            QuicDecodeStatus.FULLY_DECODED,
            scenario.decoder.decodeStatus,
        )
    }

    @Test
    fun `DCID length candidates stay bounded under repeated injection`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        val bogusKeys = requireNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(scenario.version, scenario.suite, ByteArray(32) { 0x33 }),
        )

        // Twelve injected headers, each advertising a different SCID length.
        for (len in 1..12) {
            scenario.feedInbound(
                QuicWireFixtures.handshakePacket(
                    version = scenario.version,
                    dcid = scenario.clientScid,
                    scid = ByteArray(len) { 0x77 },
                    packetNumber = (20 + len).toLong(),
                    frames = QuicWireFixtures.padding(16),
                    keys = bogusKeys,
                ),
            )
        }

        // The real connection still decodes: the genuine length survived in the bounded candidate set (or
        // in the fallbacks), and the candidate set never grew without limit.
        val results = scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/bounded", "quic.test"))),
        )
        assertTrue(results.single().decrypted)
        assertEquals("/bounded", scenario.decoder.exchanges.single().path)
    }
}
