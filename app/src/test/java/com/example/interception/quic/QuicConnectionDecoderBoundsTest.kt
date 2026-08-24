package com.example.interception.quic

import com.example.interception.tls.TlsKeyLogRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bounded-lifecycle coverage for [QuicConnectionDecoder]. A passive decoder sits in front of traffic it
 * does not control, so every per-connection structure that grows with peer behaviour — stream
 * reassemblers, HTTP/3 exchanges, request/response bodies, crypto-stream buffers — must have a hard
 * ceiling. These tests drive real packets past each ceiling and assert the ceiling holds while the
 * *counted* totals stay truthful (a clipped body still reports its real observed size).
 */
class QuicConnectionDecoderBoundsTest {

    @Before
    fun setUp() = TlsKeyLogRegistry.clear()

    @After
    fun tearDown() = TlsKeyLogRegistry.clear()

    @Test
    fun `tracked stream reassemblers are capped`() {
        val scenario = QuicConnectionScenario(maxTrackedStreams = 2)
        scenario.registerSecrets()
        scenario.completeHandshake()

        for ((index, streamId) in listOf(0L, 4L, 8L, 12L).withIndex()) {
            scenario.feedOutbound(
                scenario.clientOneRtt(
                    index.toLong(),
                    QuicWireFixtures.streamFrame(streamId, 0, QuicWireFixtures.requestHeadersFrame("GET", "/s$streamId", "quic.test")),
                ),
            )
        }

        assertTrue(
            "stream reassemblers must stay bounded, got ${scenario.decoder.trackedStreamCount}",
            scenario.decoder.trackedStreamCount <= 2,
        )
    }

    @Test
    fun `tracked HTTP-3 exchanges are capped and complete ones are evicted first`() {
        val scenario = QuicConnectionScenario(maxTrackedExchanges = 2)
        scenario.registerSecrets()
        scenario.completeHandshake()

        // Stream 0 completes fully (request FIN + response FIN); streams 4, 8 and 12 stay open. The
        // completed exchange is the one that may be dropped first when the cap is reached.
        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/done", "quic.test"), fin = true)),
        )
        scenario.feedInbound(
            scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("200"), fin = true)),
        )
        for ((index, streamId) in listOf(4L, 8L, 12L).withIndex()) {
            scenario.feedOutbound(
                scenario.clientOneRtt(
                    (index + 1).toLong(),
                    QuicWireFixtures.streamFrame(streamId, 0, QuicWireFixtures.requestHeadersFrame("GET", "/open$streamId", "quic.test")),
                ),
            )
        }

        assertTrue(
            "exchanges must stay bounded, got ${scenario.decoder.exchanges.size}",
            scenario.decoder.exchanges.size <= 2,
        )
        assertTrue(
            "the most recent open exchange is retained",
            scenario.decoder.exchanges.any { it.path == "/open12" },
        )
    }

    @Test
    fun `an oversized response body is clipped but its real observed size is still reported`() {
        val scenario = QuicConnectionScenario(maxBodyBytes = 8)
        scenario.registerSecrets()
        scenario.completeHandshake()
        val body = ByteArray(40) { 'z'.code.toByte() }

        scenario.feedInbound(
            scenario.serverOneRtt(
                0,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame(body),
                    fin = true,
                ),
            ),
        )

        val exchange = scenario.decoder.exchanges.single()
        assertEquals("the stored body is clipped to the bound", 8, exchange.responseBody().size)
        assertTrue("the clip is disclosed, not silent", exchange.responseBodyTruncated)
        assertEquals("the real observed size is still counted", 40L, exchange.responseBodyBytes)
    }

    @Test
    fun `buffered bytes stay bounded across many large bodies`() {
        val scenario = QuicConnectionScenario(maxTrackedExchanges = 4, maxBodyBytes = 1024)
        scenario.registerSecrets()
        scenario.completeHandshake()
        val body = ByteArray(4096) { 'q'.code.toByte() }

        for (i in 0 until 12) {
            val streamId = (i * 4).toLong()
            scenario.feedInbound(
                scenario.serverOneRtt(
                    i.toLong(),
                    QuicWireFixtures.streamFrame(
                        streamId = streamId,
                        offset = 0,
                        data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame(body),
                        fin = true,
                    ),
                ),
            )
        }

        val buffered = scenario.decoder.bufferedByteCount()
        assertTrue("buffered bytes must stay bounded, got $buffered", buffered <= 4 * 1024 + QuicConnectionDecoder.MAX_CRYPTO_BYTES)
        assertTrue(scenario.decoder.exchanges.size <= 4)
    }

    @Test
    fun `a retransmitted crypto frame is deduplicated instead of re-appended`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.feedOutbound(scenario.clientInitial())

        // The same server Initial delivered twice (a routine retransmission): the ServerHello must lock
        // the suite once and the crypto stream must not double up, which would corrupt any later parse.
        scenario.feedInbound(scenario.serverInitial())
        val bufferedAfterFirst = scenario.decoder.bufferedByteCount()
        scenario.feedInbound(scenario.serverInitial())

        assertEquals(
            "a duplicate CRYPTO frame adds no bytes",
            bufferedAfterFirst,
            scenario.decoder.bufferedByteCount(),
        )
        assertEquals(QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, scenario.decoder.negotiatedCipherSuite)
    }

    @Test
    fun `release makes a closed connection reclaim all decode memory`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        scenario.feedInbound(
            scenario.serverOneRtt(
                0,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame(ByteArray(2048)),
                ),
            ),
        )
        assertTrue(scenario.decoder.bufferedByteCount() > 0)

        scenario.decoder.release()

        assertEquals(0, scenario.decoder.bufferedByteCount())
        assertFalse(scenario.decoder.exchanges.any { it.hasResponseHeaders })
    }
}
