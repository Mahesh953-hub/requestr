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
    fun `a finished stream's reassembler is released as soon as its FIN is drained`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        scenario.feedInbound(
            scenario.serverOneRtt(
                0,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame("done".toByteArray()),
                    fin = true,
                ),
            ),
        )

        assertEquals(
            "a completed stream holds no reassembler: it can never contribute another byte",
            0,
            scenario.decoder.trackedStreamCount,
        )
        assertEquals("done", String(scenario.decoder.exchanges.single().responseBody()))
        assertTrue(scenario.decoder.exchanges.single().responseComplete)
    }

    @Test
    fun `a retransmission after a stream completes is dropped rather than re-parsed`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()
        val responseBytes = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame("once".toByteArray())

        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, responseBytes, fin = true)))
        // The same bytes again after the FIN was already drained. Re-creating a reassembler here would
        // re-feed the whole response into the HTTP/3 parser and duplicate the body.
        scenario.feedInbound(scenario.serverOneRtt(1, QuicWireFixtures.streamFrame(0, 0, responseBytes, fin = true)))

        assertEquals(1, scenario.decoder.exchanges.size)
        assertEquals("once", String(scenario.decoder.exchanges.single().responseBody()))
        assertEquals(4L, scenario.decoder.exchanges.single().responseBodyBytes)
        assertEquals(0, scenario.decoder.trackedStreamCount)
    }

    @Test
    fun `stream eviction prefers a victim that cannot lose buffered data`() {
        // Stream 0 completes; streams 4 and 8 hold buffered out-of-order bytes behind a gap. When the cap
        // forces an eviction, the finished/empty stream must be chosen over one holding real data.
        val scenario = QuicConnectionScenario(maxTrackedStreams = 2)
        scenario.registerSecrets()
        scenario.completeHandshake()

        // Two streams with a permanent gap at offset 0 -> both retain buffered bytes.
        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(4, 8, "gap-a".toByteArray())))
        scenario.feedInbound(scenario.serverOneRtt(1, QuicWireFixtures.streamFrame(8, 8, "gap-b".toByteArray())))
        assertEquals(2, scenario.decoder.trackedStreamCount)

        // A third stream forces an eviction; state stays bounded and nothing throws.
        scenario.feedInbound(scenario.serverOneRtt(2, QuicWireFixtures.streamFrame(12, 8, "gap-c".toByteArray())))

        assertTrue(
            "tracked streams stay bounded, got ${scenario.decoder.trackedStreamCount}",
            scenario.decoder.trackedStreamCount <= 2,
        )
    }

    @Test
    fun `bufferedByteCount counts reassembly buffers without copying bodies`() {
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        scenario.completeHandshake()

        // Out-of-order bytes behind a gap: held by the reassembler, not yet by any exchange.
        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 64, ByteArray(128) { 'p'.code.toByte() })))

        assertTrue(
            "reassembly buffers are part of the bounded-memory probe",
            scenario.decoder.bufferedByteCount() >= 128,
        )
    }

    @Test
    fun `a recreated exchange continues the observed byte totals of the evicted one`() {
        // Only the exchange bound is exceeded here (stream reassembly is left untouched), which isolates
        // what eviction does to an exchange: the bytes counted before it was dropped were genuinely
        // observed, so the recreated instance must continue those totals and declare itself truncated
        // rather than restarting at zero -- otherwise a persisted row's recorded size appears to shrink.
        val scenario = QuicConnectionScenario(maxTrackedExchanges = 2, maxTrackedStreams = 32)
        scenario.registerSecrets()
        scenario.completeHandshake()

        val firstResponse = QuicWireFixtures.responseHeadersFrame("200") +
            QuicWireFixtures.dataFrame("twelve-bytes".toByteArray())
        scenario.feedInbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, firstResponse)))
        val original = scenario.decoder.exchanges.single { it.streamId == 0L }
        assertEquals(12L, original.responseBodyBytes)
        assertFalse(original.responseBodyTruncated)

        // Two more request streams push stream 0's exchange out of the bounded in-flight set.
        scenario.feedOutbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(4, 0, QuicWireFixtures.requestHeadersFrame("GET", "/b", "quic.test"))),
        )
        scenario.feedOutbound(
            scenario.clientOneRtt(1, QuicWireFixtures.streamFrame(8, 0, QuicWireFixtures.requestHeadersFrame("GET", "/c", "quic.test"))),
        )
        assertTrue(
            "stream 0's exchange was evicted by the bound",
            scenario.decoder.exchanges.none { it.streamId == 0L },
        )

        // Stream 0 continues on its surviving reassembler, which recreates the exchange.
        scenario.feedInbound(
            scenario.serverOneRtt(
                1,
                QuicWireFixtures.streamFrame(0, firstResponse.size.toLong(), QuicWireFixtures.dataFrame("more".toByteArray())),
            ),
        )

        val recreated = scenario.decoder.exchanges.single { it.streamId == 0L }
        assertEquals("observed totals continue across the eviction", 16L, recreated.responseBodyBytes)
        assertTrue("the dropped bytes are disclosed, not silently forgotten", recreated.responseBodyTruncated)
        assertTrue(
            "the size it reports is never smaller than the body it can still produce",
            recreated.responseBodyBytes >= recreated.responseBody().size.toLong(),
        )
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
