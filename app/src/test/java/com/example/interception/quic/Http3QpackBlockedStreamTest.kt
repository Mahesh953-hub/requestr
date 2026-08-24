package com.example.interception.quic

import com.example.interception.tls.TlsKeyLogRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP/3 control-stream, QPACK-blocked-stream, and bounded-memory behaviour of the live decode path —
 * all exercised through real 1-RTT packets rather than by feeding [Http3StreamParser] directly, so the
 * QUIC → reassembly → HTTP/3 hand-off is part of what is under test.
 *
 * The blocked-stream case is the one RFC 9204 §2.2.1 behaviour that cannot be faked: a field section
 * whose Required Insert Count exceeds the decoder's dynamic-table insert count must be *withheld* (no
 * exchange, no partial row) and then surface for real once the QPACK encoder stream delivers the
 * missing insert on a *different* QUIC stream.
 */
class Http3QpackBlockedStreamTest {

    @Before
    fun setUp() = TlsKeyLogRegistry.clear()

    @After
    fun tearDown() = TlsKeyLogRegistry.clear()

    @Test
    fun `a blocked response HEADERS is withheld until the QPACK encoder stream unblocks it`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        // The server's control stream (id 3: server-initiated unidirectional) announces a QPACK
        // dynamic-table capacity, without which a dynamic reference cannot be resolved at all.
        scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(3, 0, QuicWireFixtures.controlStreamWithSettings(4096, 100))),
        )

        // A response HEADERS referencing dynamic absolute index 0 (Required Insert Count 1) arrives
        // BEFORE the encoder stream delivered that insert -> blocked.
        val blockedSection = QuicWireFixtures.bytes(0x02, 0x00, 0x80) // RIC enc=2 (req=1), Base=1, dynamic rel 0
        scenario.feedInbound(
            scenario.serverOneRtt(1, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.h3Frame(QuicWireFixtures.H3_HEADERS, blockedSection))),
        )
        assertTrue(
            "a blocked field section must not produce a decoded exchange",
            scenario.decoder.exchanges.none { it.hasResponseHeaders },
        )

        // The server's QPACK encoder stream (id 7) delivers Insert With Name Reference to static 36
        // (cache-control); that insert satisfies the Required Insert Count and unblocks the section.
        val insert = byteArrayOf((0x80 or 0x40 or 36).toByte()) + QuicWireFixtures.prefixString("no-store", 7)
        scenario.feedInbound(
            scenario.serverOneRtt(2, QuicWireFixtures.streamFrame(7, 0, QuicWireFixtures.bytes(0x02) + insert)),
        )

        val exchange = scenario.decoder.exchanges.single { it.hasResponseHeaders }
        assertEquals(0L, exchange.streamId)
        assertEquals("no-store", exchange.responseHeaders["cache-control"])
        assertNull("this section carried no :status pseudo-header", exchange.statusCode)
    }

    @Test
    fun `each direction keeps its own QPACK dynamic table`() {
        // Client and server run independent encoder streams and independent dynamic tables (RFC 9204
        // §2.1). A client-side insert must not satisfy a server-side dynamic reference, otherwise a
        // response could decode against the wrong table and produce wrong header values.
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        // Client control + encoder streams (ids 2 and 6) insert cache-control into the CLIENT table.
        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(2, 0, QuicWireFixtures.controlStreamWithSettings(4096, 100))),
        )
        val insert = byteArrayOf((0x80 or 0x40 or 36).toByte()) + QuicWireFixtures.prefixString("client-side", 7)
        scenario.feedOutbound(
            scenario.clientOneRtt(1, QuicWireFixtures.streamFrame(6, 0, QuicWireFixtures.bytes(0x02) + insert)),
        )

        // The server's control stream sets a capacity, then a server response references a dynamic
        // entry the SERVER never inserted -> it must stay blocked despite the client's insert.
        scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(3, 0, QuicWireFixtures.controlStreamWithSettings(4096, 100))),
        )
        scenario.feedInbound(
            scenario.serverOneRtt(
                1,
                QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.h3Frame(QuicWireFixtures.H3_HEADERS, QuicWireFixtures.bytes(0x02, 0x00, 0x80))),
            ),
        )

        assertTrue(
            "a client-side insert must not unblock a server-side field section",
            scenario.decoder.exchanges.none { it.hasResponseHeaders },
        )
    }

    @Test
    fun `a control-stream SETTINGS frame decodes without creating an exchange`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        val results = scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(3, 0, QuicWireFixtures.controlStreamWithSettings(8192, 16))),
        )

        assertTrue(results.single().decrypted)
        assertTrue("control-stream traffic is not an HTTP transaction", scenario.decoder.exchanges.isEmpty())
    }
}
