package com.example.interception.quic

import com.example.interception.tls.TlsKeyLogRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Retry handling (RFC 9000 §17.2.5 / RFC 9001 §5.8) on the live decode path.
 *
 * A Retry is the one packet that *changes the connection's key material* without carrying any protected
 * payload: after it, the client's Destination Connection ID becomes the Retry's Source Connection ID, and
 * **both** directions' Initial secrets re-derive from that new CID. A decoder that only notes "a Retry
 * happened" therefore cannot decrypt anything in the retried handshake — including the ServerHello, which
 * means no cipher suite, which means no Handshake or 1-RTT decryption for the rest of the connection.
 *
 * The tag check is not optional either: RFC 9001 §5.8 exists so that "only an entity that observes an
 * Initial packet can send a valid Retry packet". Acting on an unverified Retry would let a spoofed packet
 * re-key the observer's view of the connection to an attacker-chosen CID and permanently break decoding.
 */
class QuicRetryTest {

    @Before
    fun setUp() = TlsKeyLogRegistry.clear()

    @After
    fun tearDown() = TlsKeyLogRegistry.clear()

    @Test
    fun `a valid Retry re-keys the connection and the retried handshake decodes`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()

        // The client's first Initial establishes the original DCID the Retry tag is computed over.
        scenario.feedOutbound(scenario.clientInitial())
        val originalDcid = requireNotNull(scenario.decoder.originalDcid)
        assertArrayEquals(scenario.originalDcid, originalDcid)

        val results = scenario.feedInbound(scenario.serverRetry())

        assertEquals("the Retry authenticated", 1, scenario.decoder.retriesAccepted)
        assertEquals(0, scenario.decoder.retriesRejected)
        assertArrayEquals(
            "the connection's Initial-key CID is now the Retry's SCID",
            scenario.retryScid,
            scenario.decoder.originalDcid,
        )
        assertTrue(results.single().decrypted)
        assertEquals(
            "the CID table routes the new CID to this same connection",
            QuicConnectionScenario.CONNECTION_ID,
            scenario.cidTable.lookup(scenario.retryScid),
        )
        assertNull(
            "the superseded CID is retired rather than left routing",
            scenario.cidTable.lookup(originalDcid),
        )
        assertNull("the previous ClientHello identity is dropped", scenario.decoder.clientRandomHex)

        // The retried exchange: a new ClientHello (new random) under the new CID, then the ServerHello.
        // None of this can decrypt unless the Retry really re-derived the Initial keys.
        scenario.feedOutbound(scenario.retriedClientInitial())
        assertEquals(
            "the retried ClientHello's random becomes the new key-log identity",
            scenario.retriedClientRandom.joinToString("") { "%02x".format(it) },
            scenario.decoder.clientRandomHex,
        )

        scenario.feedInbound(scenario.retriedServerInitial())
        assertEquals(
            "the retried ServerHello decodes and locks the suite",
            QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256,
            scenario.decoder.negotiatedCipherSuite,
        )
    }

    @Test
    fun `a Retry with an invalid integrity tag is rejected and changes nothing`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        val originalDcid = requireNotNull(scenario.decoder.originalDcid)
        val identityBefore = scenario.decoder.clientRandomHex

        val results = scenario.feedInbound(scenario.serverRetry(validTag = false))

        assertFalse(results.single().decrypted)
        assertEquals(0, scenario.decoder.retriesAccepted)
        assertEquals(1, scenario.decoder.retriesRejected)
        assertArrayEquals(
            "a spoofed Retry must not re-key the connection",
            originalDcid,
            scenario.decoder.originalDcid,
        )
        assertEquals("a spoofed Retry must not drop the connection's identity", identityBefore, scenario.decoder.clientRandomHex)
        assertNull(
            "a spoofed Retry's CID must not become routable",
            scenario.cidTable.lookup(scenario.retryScid),
        )
    }

    @Test
    fun `a Retry whose tag was computed for another connection is rejected`() {
        val scenario = QuicConnectionScenario()
        scenario.feedOutbound(scenario.clientInitial())

        // A well-formed Retry, but its tag authenticates a *different* original DCID -- what an attacker
        // replaying another connection's Retry would produce.
        val foreign = scenario.serverRetry(odcid = QuicWireFixtures.hex("dead00beef11cafe"))
        scenario.feedInbound(foreign)

        assertEquals(1, scenario.decoder.retriesRejected)
        assertEquals(0, scenario.decoder.retriesAccepted)
        assertArrayEquals(scenario.originalDcid, scenario.decoder.originalDcid)
    }

    @Test
    fun `a Retry observed with no prior client Initial is not acted on`() {
        // Without the client's Initial there is no original DCID, so the tag cannot be verified at all.
        // The honest outcome is to do nothing rather than trust the packet.
        val scenario = QuicConnectionScenario()

        val results = scenario.feedInbound(scenario.serverRetry(odcid = scenario.originalDcid))

        assertFalse(results.single().decrypted)
        assertEquals(0, scenario.decoder.retriesAccepted)
        assertEquals(0, scenario.decoder.retriesRejected)
        assertNull(scenario.decoder.originalDcid)
        assertEquals(QuicState.HANDSHAKE_IN_PROGRESS, scenario.decoder.lifecycle)
    }

    @Test
    fun `a Retry resets Initial crypto reassembly so the retried ServerHello is not seen as a duplicate`() {
        // The retried handshake restarts its CRYPTO stream at offset 0. If the pre-Retry coverage were
        // kept, those bytes would be deduplicated as a retransmission and the ServerHello would never be
        // parsed -- leaving the connection permanently without a negotiated suite.
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        scenario.feedInbound(scenario.serverInitial()) // a first ServerHello at crypto offset 0
        assertNotEquals(null, scenario.decoder.negotiatedCipherSuite)

        scenario.feedInbound(scenario.serverRetry())
        scenario.feedOutbound(scenario.retriedClientInitial())
        // A different suite in the retried ServerHello proves the new bytes were really parsed.
        scenario.feedInbound(scenario.retriedServerInitial(cipherSuiteId = 0x1303))

        assertEquals(
            QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            scenario.decoder.negotiatedCipherSuite,
        )
    }

    @Test
    fun `packet number spaces are not reset by a Retry`() {
        // RFC 9000 §17.2.5.3: a client must not restart a packet number space after processing a Retry.
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        scenario.feedInbound(scenario.serverInitial(packetNumber = 4))
        assertEquals(
            4L,
            scenario.decoder.largestPacketNumber(
                QuicConnectionDecoder.PacketNumberSpace.INITIAL,
                QuicDatagramRouter.Direction.INBOUND,
            ),
        )

        scenario.feedInbound(scenario.serverRetry())

        assertEquals(
            "the Initial packet-number space survives the Retry",
            4L,
            scenario.decoder.largestPacketNumber(
                QuicConnectionDecoder.PacketNumberSpace.INITIAL,
                QuicDatagramRouter.Direction.INBOUND,
            ),
        )
    }
}
