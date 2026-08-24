package com.example.interception.quic

import com.example.data.local.QuicDecodeStatus
import com.example.interception.tls.KeyLogLabel
import com.example.interception.tls.TlsKeyLogRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end coverage of [QuicConnectionDecoder] — the production QUIC/HTTP-3 decode path — driven
 * from **real** protected packets built by [QuicWireFixtures] and routed through a real
 * [QuicDatagramRouter].
 *
 * Together these cases prove the whole runtime chain the v6.0 gap closure is about: the key-log
 * identity really comes out of the ClientHello; the negotiated cipher suite really comes out of the
 * server's ServerHello; Handshake and 1-RTT payloads really decrypt with header Associated Data;
 * STREAM frames really reassemble by ID and byte offset across reordering and retransmission; HTTP/3
 * request/response frames really decode into exchanges; and every failure mode (no keys, no
 * ServerHello, malformed bytes) surfaces as an explicit, honest state instead of a fabricated row.
 */
class QuicConnectionDecoderTest {

    @Before
    fun setUp() = TlsKeyLogRegistry.clear()

    @After
    fun tearDown() = TlsKeyLogRegistry.clear()

    // ── Identity and cipher-suite recovery ───────────────────────────────────────────────

    @Test
    fun `the client initial yields the key-log identity and the recovered SNI`() {
        val scenario = QuicConnectionScenario()

        val routed = scenario.router.ingest(
            scenario.clientInitial(),
            QuicConnectionScenario.CLIENT_IP,
            QuicConnectionScenario.CLIENT_PORT,
            QuicConnectionScenario.SERVER_IP,
            QuicConnectionScenario.QUIC_PORT,
        ).single()
        val result = scenario.decoder.ingest(routed)

        assertEquals("quic.test", routed.sniHostname)
        assertEquals("h3", routed.alpn)
        assertTrue("the ClientHello was really AEAD-decrypted", routed.decrypted)
        assertTrue(result.decrypted)
        assertEquals(
            "the ClientHello random becomes this connection's key-log identity",
            scenario.clientRandom.joinToString("") { "%02x".format(it) },
            scenario.decoder.clientRandomHex,
        )
        assertEquals(QuicDecodeStatus.PARTIALLY_DECODED, scenario.decoder.decodeStatus)
        assertEquals(QuicState.HANDSHAKE_IN_PROGRESS, scenario.decoder.lifecycle)
    }

    @Test
    fun `the server initial's real ServerHello locks the negotiated cipher suite`() {
        val scenario = QuicConnectionScenario()
        scenario.feedOutbound(scenario.clientInitial())
        assertNull("no suite is known before the ServerHello", scenario.decoder.negotiatedCipherSuite)

        scenario.feedInbound(scenario.serverInitial())

        assertEquals(
            QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256,
            scenario.decoder.negotiatedCipherSuite,
        )
    }

    @Test
    fun `a ChaCha20-Poly1305 ServerHello locks the ChaCha suite`() {
        val scenario = QuicConnectionScenario(suite = QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256)
        scenario.feedOutbound(scenario.clientInitial())
        scenario.feedInbound(scenario.serverInitial())

        assertEquals(
            QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            scenario.decoder.negotiatedCipherSuite,
        )
    }

    @Test
    fun `an unsupported ServerHello suite locks nothing and leaves the connection undecryptable`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        // 0xc02f = TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, a TLS 1.2 suite QUIC cannot negotiate.
        scenario.feedInbound(scenario.serverInitial(cipherSuiteId = 0xc02f))

        assertNull(scenario.decoder.negotiatedCipherSuite)
        scenario.feedInbound(scenario.serverHandshake())
        assertTrue("no AEAD can be selected without a recognized suite", scenario.decoder.cipherSuiteUnavailable)
    }

    // ── Key-missing states ───────────────────────────────────────────────────────────────

    @Test
    fun `a handshake packet with no registry entry records the exact missing label and decodes nothing`() {
        val scenario = QuicConnectionScenario()
        scenario.feedOutbound(scenario.clientInitial())
        scenario.feedInbound(scenario.serverInitial())

        val results = scenario.feedInbound(scenario.serverHandshake())

        assertFalse("nothing can decrypt without the handshake traffic secret", results.single().decrypted)
        assertTrue(
            "the exact missing label is recorded, not a generic failure",
            KeyLogLabel.SERVER_HANDSHAKE_TRAFFIC_SECRET in scenario.decoder.missingKeyLabels,
        )
        assertTrue(scenario.decoder.keyMaterialMissing)
        assertTrue("no exchange may be invented", scenario.decoder.exchanges.isEmpty())
    }

    @Test
    fun `a connection observed only in 1-RTT with no keys is OBSERVED_NO_KEYS`() {
        // No Initial packets captured at all (mid-connection capture start): there is no ServerHello to
        // select an AEAD and no ClientHello random to look keys up by, so the honest status is
        // "observed, no keys" -- never "detected only", which would understate what was seen.
        val scenario = QuicConnectionScenario()

        val results = scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.dataFrame("x".toByteArray()))),
        )

        assertFalse(results.single().decrypted)
        assertEquals(QuicDecodeStatus.OBSERVED_NO_KEYS, scenario.decoder.decodeStatus)
        assertTrue(scenario.decoder.cipherSuiteUnavailable)
        assertTrue(scenario.decoder.exchanges.isEmpty())
    }

    @Test
    fun `an unknown-version long header leaves the connection detected-only`() {
        val scenario = QuicConnectionScenario()
        // A gQUIC/unknown version: classified to no packet type, so nothing is even attempted.
        val unknown = QuicWireFixtures.bytes(0xC3, 0x51, 0x30, 0x34, 0x33, 0x00, 0x00) + ByteArray(32)

        val results = scenario.feedOutbound(unknown)

        assertTrue(results.none { it.decrypted })
        assertEquals(QuicDecodeStatus.DETECTED_ONLY, scenario.decoder.decodeStatus)
    }

    // ── Handshake-level decryption ───────────────────────────────────────────────────────

    @Test
    fun `handshake packets decrypt from a registry-resolved traffic secret`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        scenario.feedInbound(scenario.serverInitial())

        val results = scenario.feedInbound(scenario.serverHandshake())

        assertTrue("the Handshake payload really decrypted", results.single().decrypted)
        assertTrue(
            KeyLogLabel.SERVER_HANDSHAKE_TRAFFIC_SECRET in scenario.decoder.resolvedKeyLabels,
        )
        assertFalse(scenario.decoder.keyMaterialMissing)
        assertEquals(QuicDecodeStatus.PARTIALLY_DECODED, scenario.decoder.decodeStatus)
        assertEquals(
            "SERVER_HANDSHAKE_TRAFFIC_SECRET",
            scenario.decoder.resolvedKeyLabelsCsv(),
        )
    }

    @Test
    fun `a handshake packet protected with the wrong secret fails AEAD and is counted`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        scenario.feedInbound(scenario.serverInitial())
        val bogusKeys = requireNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(scenario.version, scenario.suite, ByteArray(32) { 0x7f }),
        )

        val results = scenario.feedInbound(scenario.serverHandshake(keys = bogusKeys))

        assertFalse(results.single().decrypted)
        assertEquals(1, scenario.decoder.decryptFailures)
        assertTrue("a failed AEAD must not invent an exchange", scenario.decoder.exchanges.isEmpty())
    }

    // ── Coalesced datagrams ──────────────────────────────────────────────────────────────

    @Test
    fun `a coalesced server Initial plus Handshake datagram locks the suite and decrypts both packets`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())

        // One datagram, two QUIC packets — the common server response shape (RFC 9000 §12.2). The
        // Handshake packet can only decrypt because the Initial packet ahead of it, in the *same*
        // ingest call, locked the negotiated suite first.
        val results = scenario.feedInbound(scenario.serverInitial() + scenario.serverHandshake())

        assertEquals("both coalesced packets were routed", 2, results.size)
        assertEquals(QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, scenario.decoder.negotiatedCipherSuite)
        assertTrue("the Initial decrypted", results[0].decrypted)
        assertTrue("the coalesced Handshake decrypted", results[1].decrypted)
    }

    // ── 1-RTT HTTP/3 decode ──────────────────────────────────────────────────────────────

    @Test
    fun `a 1-RTT request and response decode into one HTTP-3 exchange`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        scenario.feedOutbound(
            scenario.clientOneRtt(
                packetNumber = 0,
                frames = QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.requestHeadersFrame("GET", "/v1/items", "quic.test"),
                ),
            ),
        )
        scenario.feedInbound(
            scenario.serverOneRtt(
                packetNumber = 0,
                frames = QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.responseHeadersFrame("200", mapOf("content-type" to "application/json")) +
                        QuicWireFixtures.dataFrame("""{"ok":true}""".toByteArray()),
                    fin = true,
                ),
            ),
        )

        val exchange = scenario.decoder.exchanges.single()
        assertEquals("GET", exchange.method)
        assertEquals("/v1/items", exchange.path)
        assertEquals("quic.test", exchange.authority)
        assertEquals("https", exchange.scheme)
        assertEquals(200, exchange.statusCode)
        assertEquals("application/json", exchange.responseHeaders["content-type"])
        assertEquals("""{"ok":true}""", String(exchange.responseBody()))
        assertTrue(exchange.responseComplete)
        assertEquals(QuicDecodeStatus.FULLY_DECODED, scenario.decoder.decodeStatus)
        assertEquals(QuicState.HANDSHAKE_COMPLETE, scenario.decoder.lifecycle)
    }

    @Test
    fun `request body DATA frames accumulate on the request side only`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        val requestBytes = QuicWireFixtures.requestHeadersFrame("POST", "/upload", "quic.test") +
            QuicWireFixtures.dataFrame("payload-one".toByteArray()) +
            QuicWireFixtures.dataFrame("-two".toByteArray())
        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, requestBytes, fin = true)),
        )

        val exchange = scenario.decoder.exchanges.single()
        assertEquals("POST", exchange.method)
        assertEquals("payload-one-two", String(exchange.requestBody()))
        assertEquals(15L, exchange.requestBodyBytes)
        assertTrue("no response was observed, so nothing may claim one", exchange.responseBody().isEmpty())
        assertNull(exchange.statusCode)
        assertTrue(exchange.requestComplete)
        assertFalse(exchange.responseComplete)
    }

    // ── Stream reassembly: out of order, retransmitted, split ────────────────────────────

    @Test
    fun `out-of-order and retransmitted STREAM frames reassemble into one correct exchange`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/split", "quic.test"))),
        )

        val responseBytes = QuicWireFixtures.responseHeadersFrame("204") +
            QuicWireFixtures.dataFrame("chunked-body-bytes".toByteArray())
        val cut1 = 4
        val cut2 = responseBytes.size / 2
        val part1 = responseBytes.copyOfRange(0, cut1)
        val part2 = responseBytes.copyOfRange(cut1, cut2)
        val part3 = responseBytes.copyOfRange(cut2, responseBytes.size)

        // Deliver the middle segment first, then the tail (still not drainable), then a duplicate of
        // the middle segment (must dedupe), and only then the head that closes the gap.
        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, cut1.toLong(), part2)))
        assertTrue(
            "a gap at offset 0 must not decode any response yet",
            scenario.decoder.exchanges.none { it.hasResponseHeaders },
        )
        scenario.feedInbound(scenario.serverOneRtt(1, QuicWireFixtures.streamFrame(0, cut2.toLong(), part3, fin = true)))
        scenario.feedInbound(scenario.serverOneRtt(2, QuicWireFixtures.streamFrame(0, cut1.toLong(), part2)))
        scenario.feedInbound(scenario.serverOneRtt(3, QuicWireFixtures.streamFrame(0, 0, part1)))

        val exchange = scenario.decoder.exchanges.single()
        assertEquals(204, exchange.statusCode)
        assertEquals("chunked-body-bytes", String(exchange.responseBody()))
        assertEquals("the retransmitted segment must not be counted twice", 18L, exchange.responseBodyBytes)
    }

    @Test
    fun `an HTTP-3 frame split across two 1-RTT packets decodes once complete`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        val requestBytes = QuicWireFixtures.requestHeadersFrame("GET", "/two-packets", "quic.test")
        val split = requestBytes.size / 2
        scenario.feedOutbound(scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, requestBytes.copyOfRange(0, split))))
        assertTrue("a partial HEADERS frame decodes nothing", scenario.decoder.exchanges.none { it.hasRequestHeaders })

        scenario.feedOutbound(
            scenario.clientOneRtt(1, QuicWireFixtures.streamFrame(0, split.toLong(), requestBytes.copyOfRange(split, requestBytes.size))),
        )

        assertEquals("/two-packets", scenario.decoder.exchanges.single().path)
    }

    @Test
    fun `two request streams decode as two independent exchanges`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/a", "quic.test"))),
        )
        scenario.feedOutbound(
            scenario.clientOneRtt(1, QuicWireFixtures.streamFrame(4, 0, QuicWireFixtures.requestHeadersFrame("GET", "/b", "quic.test"))),
        )

        assertEquals(setOf("/a", "/b"), scenario.decoder.exchanges.map { it.path }.toSet())
        assertEquals(setOf(0L, 4L), scenario.decoder.exchanges.map { it.streamId }.toSet())
    }

    // ── Packet-number spaces ─────────────────────────────────────────────────────────────

    @Test
    fun `packet numbers reconstruct per space and per direction`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        val ping = QuicWireFixtures.ping()

        // Inbound application-data space advances to 200, then a 1-byte truncated 0x04 must
        // reconstruct to 260 against it (RFC 9000 §17.1 / Appendix A.3) -- not to 4.
        scenario.feedInbound(scenario.serverOneRtt(200, ping))
        scenario.feedInbound(scenario.serverOneRtt(260, ping))
        assertEquals(
            260L,
            scenario.decoder.largestPacketNumber(
                QuicConnectionDecoder.PacketNumberSpace.APPLICATION_DATA,
                QuicDatagramRouter.Direction.INBOUND,
            ),
        )

        // The outbound direction has its own space: a packet numbered 0 must still decode, which it
        // only can if its packet number was reconstructed against 'no prior outbound packet'.
        val outbound = scenario.feedOutbound(scenario.clientOneRtt(0, ping))
        assertTrue("each direction numbers its own packets", outbound.single().decrypted)
        assertEquals(
            0L,
            scenario.decoder.largestPacketNumber(
                QuicConnectionDecoder.PacketNumberSpace.APPLICATION_DATA,
                QuicDatagramRouter.Direction.OUTBOUND,
            ),
        )
        // The Handshake space is tracked separately from application data.
        assertEquals(
            0L,
            scenario.decoder.largestPacketNumber(
                QuicConnectionDecoder.PacketNumberSpace.HANDSHAKE,
                QuicDatagramRouter.Direction.INBOUND,
            ),
        )
    }

    @Test
    fun `a multi-byte packet number decrypts`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        val results = scenario.feedInbound(
            scenario.serverOneRtt(0x1234, QuicWireFixtures.ping(), pnLength = 2),
        )

        assertTrue(results.single().decrypted)
        assertEquals(
            0x1234L,
            scenario.decoder.largestPacketNumber(
                QuicConnectionDecoder.PacketNumberSpace.APPLICATION_DATA,
                QuicDatagramRouter.Direction.INBOUND,
            ),
        )
    }

    // ── Lifecycle and malformed input ────────────────────────────────────────────────────

    @Test
    fun `a CONNECTION_CLOSE frame in a decrypted 1-RTT packet closes the connection`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.connectionCloseApp(0x0100, "shutting down")),
        )

        assertEquals(QuicState.CLOSED, scenario.decoder.lifecycle)
        assertEquals("shutting down", scenario.decoder.closeReason)
    }

    @Test
    fun `a NEW_CONNECTION_ID frame in a decrypted packet registers the CID for routing`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        val newCid = QuicWireFixtures.hex("aabbccdd11223344")

        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.newConnectionId(1, 0, newCid)))

        assertEquals(
            "a mid-connection CID routes to the same connection",
            QuicConnectionScenario.CONNECTION_ID,
            scenario.cidTable.lookup(newCid),
        )
    }

    @Test
    fun `malformed and truncated packets never throw and never decode`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        // Random bytes with the short-header form bit, a truncated 1-RTT packet, and an empty datagram.
        scenario.feedInbound(QuicWireFixtures.bytes(0x41, 0x02, 0x03))
        scenario.feedInbound(ByteArray(24) { 0x5a })
        scenario.feedInbound(ByteArray(0))
        scenario.feedOutbound(QuicWireFixtures.bytes(0xC0, 0x00, 0x00, 0x00, 0x01, 0xFF))

        assertTrue("garbage must not produce exchanges", scenario.decoder.exchanges.isEmpty())
        assertEquals(QuicDecodeStatus.PARTIALLY_DECODED, scenario.decoder.decodeStatus)
    }

    @Test
    fun `a malformed HTTP-3 frame inside a decrypted stream decodes nothing and does not throw`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        // A HEADERS frame whose declared length far exceeds the bytes present: the parser must buffer
        // and wait rather than decode a partial field section.
        val bogusHeaders = QuicWireFixtures.varint(0x01) + QuicWireFixtures.varint(4096) + ByteArray(8)
        val results = scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, bogusHeaders)))

        assertTrue("the packet itself still decrypted", results.single().decrypted)
        assertTrue(scenario.decoder.exchanges.none { it.hasResponseHeaders })
    }

    @Test
    fun `release clears live decode state`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/x", "quic.test"))),
        )
        assertEquals(1, scenario.decoder.exchanges.size)
        assertTrue(scenario.decoder.trackedStreamCount > 0)

        scenario.decoder.release()

        assertEquals(0, scenario.decoder.exchanges.size)
        assertEquals(0, scenario.decoder.trackedStreamCount)
        assertEquals(0, scenario.decoder.bufferedByteCount())
    }

    @Test
    fun `an externally locked cipher suite unlocks the decrypt path without a ServerHello`() {
        // The MITM/SDK planes can know the negotiated suite without this observer seeing a ServerHello
        // (e.g. capture started mid-connection); an external lock must unlock the same decrypt path.
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())
        assertNotNull(scenario.decoder.clientRandomHex)

        assertTrue(scenario.decoder.lockCipherSuiteFromIana(scenario.ianaCipherSuite))
        val results = scenario.feedInbound(scenario.serverHandshake())

        assertTrue(results.single().decrypted)
        assertFalse(scenario.decoder.cipherSuiteUnavailable)
    }
}
