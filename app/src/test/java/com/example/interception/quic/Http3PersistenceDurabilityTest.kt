package com.example.interception.quic

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.QuicDecodeStatus
import com.example.data.local.RequestrDatabase
import com.example.interception.PacketPump
import com.example.interception.tls.TlsKeyLogRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Persistence-durability coverage for the QUIC decode path: what survives when the *in-memory* decode
 * state for an idle connection is released and traffic then resumes.
 *
 * This is the case the first implementation got wrong in three separate ways, each of which silently
 * destroyed already-recorded evidence: a resumed connection looked like a first observation, so it was
 * re-announced with a whole-row REPLACE that wiped the SNI, ALPN, client random and resolved key labels
 * and reset the decode status to `DETECTED_ONLY`; the server's half of the connection could resolve to a
 * reversed-5-tuple key and mint a *second* row for one connection; and a new decoder could inherit the
 * previous one's HTTP/3 row IDs and update an unrelated older row.
 *
 * Everything here therefore asserts on Room after a prune, not on in-memory state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class Http3PersistenceDurabilityTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = RequestrDatabase.get(context)

    private var inputFile: File? = null
    private var outputFile: File? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null

    @Before
    fun setUp() = TlsKeyLogRegistry.clear()

    @After
    fun tearDown() {
        TlsKeyLogRegistry.clear()
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { inputFile?.delete() }
        runCatching { outputFile?.delete() }
    }

    private fun newPump(sessionId: Long): PacketPump {
        val inFile = File.createTempFile("h3-durability-in", ".bin")
        val outFile = File.createTempFile("h3-durability-out", ".bin")
        inputFile = inFile
        outputFile = outFile
        val ins = FileInputStream(inFile)
        val outs = FileOutputStream(outFile)
        input = ins
        output = outs
        return PacketPump(context = context, input = ins, output = outs, sessionId = sessionId, db = db)
    }

    private fun PacketPump.outbound(datagram: ByteArray) =
        ingestQuicDatagram(datagram, CLIENT_IP, CLIENT_PORT, SERVER_IP, 443, isIpv6 = false)

    private fun PacketPump.inbound(datagram: ByteArray) =
        ingestQuicDatagram(datagram, SERVER_IP, 443, CLIENT_IP, CLIENT_PORT, isIpv6 = false)

    /** Releases all in-memory decode state, exactly as the flush loop does after the idle TTL. */
    private fun PacketPump.pruneEverything() = pruneQuicDecodersForTest(System.currentTimeMillis() + 60_000L)

    private fun fullyDecodedExchange(pump: PacketPump, scenario: QuicConnectionScenario, path: String) {
        pump.outbound(scenario.clientInitial())
        pump.inbound(scenario.serverInitial() + scenario.serverHandshake())
        pump.outbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", path, scenario.sni), fin = true)),
        )
        pump.inbound(
            scenario.serverOneRtt(
                0,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame("body".toByteArray()),
                    fin = true,
                ),
            ),
        )
        pump.flushHttp3WritesForTest()
    }

    @Test
    fun `resuming after a prune keeps the connection row's status identity and provenance`() {
        val sessionId = 9101L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        fullyDecodedExchange(pump, scenario, "/before-prune")
        val before = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single()
        assertEquals(QuicDecodeStatus.FULLY_DECODED, before.decodeStatus)
        assertEquals("HANDSHAKE_COMPLETE", before.lifecycleState)
        assertNotNull(before.clientRandomHex)
        assertNotNull(before.keylogLabels)

        pump.pruneEverything()
        assertEquals("all decode state was released", 0, pump.quicDecoderCount())

        // Traffic resumes. The fresh decoder has decoded nothing yet, so if the persisted row were
        // re-announced (or written non-monotonically) every column below would regress.
        pump.inbound(scenario.serverOneRtt(9, QuicWireFixtures.ping()))
        pump.flushHttp3WritesForTest()

        val rows = runBlocking { db.quicConnectionDao().getBySession(sessionId) }
        assertEquals("a resumed connection must not create a second row", 1, rows.size)
        val after = rows.single()
        assertEquals("connection identity is stable across a prune", before.connectionId, after.connectionId)
        assertEquals("status must not regress", QuicDecodeStatus.FULLY_DECODED, after.decodeStatus)
        assertEquals("lifecycle must not regress", "HANDSHAKE_COMPLETE", after.lifecycleState)
        assertEquals("SNI must not be wiped", before.sniHost, after.sniHost)
        assertEquals("ALPN must not be wiped", before.alpn, after.alpn)
        assertEquals("the key-log identity must not be wiped", before.clientRandomHex, after.clientRandomHex)
        assertEquals("resolved key provenance must not be wiped", before.keylogLabels, after.keylogLabels)
        assertEquals("the version must not be wiped", before.quicVersion, after.quicVersion)
        assertEquals("establishedAt must not be reset", before.establishedAt, after.establishedAt)
    }

    @Test
    fun `an inbound-only resume after a prune does not create a reversed-tuple duplicate row`() {
        val sessionId = 9102L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        fullyDecodedExchange(pump, scenario, "/duplicate-check")
        pump.pruneEverything()

        // Only the server side speaks after the prune. Its 5-tuple is the reverse of the client's, and
        // its CID aliases were released with the decode state, so a direction-specific fallback key would
        // mint a second connection here.
        pump.inbound(scenario.serverOneRtt(10, QuicWireFixtures.ping()))
        pump.inbound(scenario.serverOneRtt(11, QuicWireFixtures.ping()))
        pump.flushHttp3WritesForTest()

        val rows = runBlocking { db.quicConnectionDao().getBySession(sessionId) }
        assertEquals("both directions of one connection share one routing key", 1, rows.size)
        assertEquals(
            "the stored 5-tuple stays in client->server orientation",
            CLIENT_IP,
            rows.single().srcIp,
        )
        assertEquals(SERVER_IP, rows.single().destIp)
    }

    @Test
    fun `a new exchange after a prune inserts its own row instead of updating the previous one`() {
        val sessionId = 9103L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        fullyDecodedExchange(pump, scenario, "/first-generation")
        val firstRow = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.single()
        assertEquals("/first-generation", firstRow.path)

        pump.pruneEverything()

        // A brand-new handshake and a brand-new exchange on the SAME stream id (0) and the same 5-tuple.
        // Without a per-decoder generation in the row key, this would update the row above instead of
        // inserting its own, silently rewriting a completed transaction's path and status.
        val resumed = QuicConnectionScenario(clientRandom = QuicWireFixtures.clientRandom(0x3d))
        resumed.registerSecrets()
        fullyDecodedExchange(pump, resumed, "/second-generation")

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        assertEquals("each generation's exchange is its own row", 2, rows.size)
        assertEquals(
            setOf("/first-generation", "/second-generation"),
            rows.map { it.path }.toSet(),
        )
        assertTrue(
            "the earlier row is untouched",
            rows.any { it.id == firstRow.id && it.path == "/first-generation" },
        )
    }

    @Test
    fun `HTTP-3 rows are written by the flush, not by the packet path`() {
        val sessionId = 9104L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        pump.outbound(scenario.clientInitial())
        pump.inbound(scenario.serverInitial() + scenario.serverHandshake())
        // Several DATA frames on one stream: the packet path must not write once per packet.
        val headerBytes = QuicWireFixtures.responseHeadersFrame("200")
        pump.outbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/batched", scenario.sni), fin = true)),
        )
        pump.inbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, headerBytes)))
        var offset = headerBytes.size.toLong()
        for (i in 0 until 5) {
            val chunk = QuicWireFixtures.dataFrame("chunk$i".toByteArray())
            pump.inbound(scenario.serverOneRtt((i + 1).toLong(), QuicWireFixtures.streamFrame(0, offset, chunk)))
            offset += chunk.size
        }

        assertTrue("the exchange is queued, not yet written", pump.pendingHttp3WriteCount() > 0)
        assertTrue(
            "nothing was written from the packet path",
            runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.isEmpty(),
        )

        pump.flushHttp3WritesForTest()

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        assertEquals("one batched write per exchange, not one per packet", 1, rows.size)
        assertEquals("chunk0chunk1chunk2chunk3chunk4", rows.single().responseBody)
        assertEquals("/batched", rows.single().path)

        // A second flush with nothing new must not rewrite: the exchange's revision has not moved.
        pump.flushHttp3WritesForTest()
        assertEquals(1, runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.size)
    }

    @Test
    fun `request metadata is filled in when the response decodes before the request`() {
        val sessionId = 9105L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        pump.outbound(scenario.clientInitial())
        pump.inbound(scenario.serverInitial() + scenario.serverHandshake())

        // The response HEADERS decode first (reordering, or a capture that caught the response leg
        // first). The row is inserted with no request metadata yet.
        pump.inbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("201"))))
        pump.flushHttp3WritesForTest()
        val responseOnly = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.single()
        assertEquals(201, responseOnly.statusCode)
        assertEquals("", responseOnly.method)

        // The request HEADERS arrive afterwards and must fill the blanks in the same row.
        pump.outbound(
            scenario.clientOneRtt(
                0,
                QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("PATCH", "/late-request", scenario.sni)),
            ),
        )
        pump.flushHttp3WritesForTest()

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        assertEquals("still one row", 1, rows.size)
        val row = rows.single()
        assertEquals(responseOnly.id, row.id)
        assertEquals("PATCH", row.method)
        assertEquals("/late-request", row.path)
        assertEquals("https://quic.test/late-request", row.url)
        assertEquals("quic.test", row.host)
        assertEquals("the response status is not lost by the request update", 201, row.statusCode)
    }

    @Test
    fun `a connection first seen through a short header has its version corrected`() {
        val sessionId = 9106L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        // Capture starts mid-connection: the first packet is 1-RTT, which carries no version at all.
        pump.inbound(scenario.serverOneRtt(0, QuicWireFixtures.ping()))
        val initial = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single()
        assertEquals("a short header reports no version", 0, initial.quicVersion)

        // A long header then reveals the real version, which must be written into the existing row.
        pump.outbound(scenario.clientInitial())
        pump.flushHttp3WritesForTest()

        val rows = runBlocking { db.quicConnectionDao().getBySession(sessionId) }
        assertEquals(1, rows.size)
        assertEquals(QuicCrypto.VERSION_1, rows.single().quicVersion)
        assertNotNull("the ClientHello identity is recorded too", rows.single().clientRandomHex)
    }

    @Test
    fun `a corrected version does not overwrite an already-known version`() {
        val sessionId = 9107L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        pump.outbound(scenario.clientInitial())
        assertEquals(
            QuicCrypto.VERSION_1,
            runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single().quicVersion,
        )

        pump.inbound(scenario.serverOneRtt(0, QuicWireFixtures.ping()))
        pump.flushHttp3WritesForTest()

        assertEquals(
            "a versionless short header must not blank a known version",
            QuicCrypto.VERSION_1,
            runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single().quicVersion,
        )
    }

    @Test
    fun `eviction releases every connection-scoped index together`() {
        val sessionId = 9108L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        fullyDecodedExchange(pump, scenario, "/evict")
        val connectionId = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single().connectionId
        assertEquals(1, pump.quicDecoderCount())
        assertEquals(1, pump.quicConnectionStateCount())
        assertTrue(pump.hasQuicCipherSuite(connectionId))

        pump.pruneEverything()

        assertEquals(0, pump.quicDecoderCount())
        assertEquals(0, pump.quicConnectionStateCount())
        assertFalse(pump.hasQuicCipherSuite(connectionId))
        assertEquals("queued writes were flushed before release", 0, pump.pendingHttp3WriteCount())
        assertEquals(
            "persisted rows survive eviction",
            1,
            runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.size,
        )
    }

    @Test
    fun `an undecryptable resumed connection cannot lower a recorded decoded status`() {
        val sessionId = 9109L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        fullyDecodedExchange(pump, scenario, "/high-water")
        pump.pruneEverything()

        // The key material is gone (capture stop clears the registry), so the resumed connection can
        // decode nothing at all. The stored status is evidence of what was really decoded earlier and
        // must stand.
        TlsKeyLogRegistry.clear()
        pump.inbound(scenario.serverOneRtt(20, QuicWireFixtures.ping()))
        pump.flushHttp3WritesForTest()

        val row = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single()
        assertEquals(QuicDecodeStatus.FULLY_DECODED, row.decodeStatus)
        assertNull(
            "no new traffic row is invented for an undecryptable resume",
            runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.singleOrNull()?.takeIf { it.path.isEmpty() },
        )
    }

    @Test
    fun `more in-flight exchanges than the bound never shrink a persisted body or size`() {
        // 70 concurrent request streams against a 64-exchange in-flight bound, so the decoder evicts and
        // later recreates exchanges. A recreated exchange holds fewer buffered body bytes than its row
        // already contains, which is exactly the case that could rewrite a long body with a short one (or
        // leave a large size next to a short body). Persistence must be monotonic and idempotent instead.
        val sessionId = 9110L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)
        val streamCount = 70
        val body = "0123456789ABCDEF" // 16 bytes per stream

        pump.outbound(scenario.clientInitial())
        pump.inbound(scenario.serverInitial() + scenario.serverHandshake())

        var packetNumber = 0L
        for (i in 0 until streamCount) {
            val streamId = (i * 4).toLong()
            pump.outbound(
                scenario.clientOneRtt(
                    packetNumber++,
                    QuicWireFixtures.streamFrame(streamId, 0, QuicWireFixtures.requestHeadersFrame("GET", "/s$i", scenario.sni), fin = true),
                ),
            )
            pump.inbound(
                scenario.serverOneRtt(
                    packetNumber,
                    QuicWireFixtures.streamFrame(
                        streamId = streamId,
                        offset = 0,
                        data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame(body.toByteArray()),
                    ),
                ),
            )
            packetNumber++
        }
        pump.flushHttp3WritesForTest()

        val afterFirstPass = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        assertEquals("every decoded exchange has exactly one row", streamCount, afterFirstPass.size)
        val stream0Before = afterFirstPass.single { it.path == "/s0" }
        assertEquals(body, stream0Before.responseBody)
        assertEquals(body.length.toLong(), stream0Before.responseSizeBytes)

        // Stream 0's exchange was evicted long ago. It speaks again: the recreated exchange carries the
        // real cumulative observed size but only the newly buffered bytes.
        val tail = "-TAIL"
        pump.inbound(
            scenario.serverOneRtt(
                packetNumber++,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = (QuicWireFixtures.responseHeadersFrame("200").size + QuicWireFixtures.dataFrame(body.toByteArray()).size).toLong(),
                    data = QuicWireFixtures.dataFrame(tail.toByteArray()),
                    fin = true,
                ),
            ),
        )
        pump.flushHttp3WritesForTest()
        // Flushing twice proves idempotence: re-applying the same state must change nothing.
        pump.flushHttp3WritesForTest()

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        assertEquals("a recreated exchange must not add a duplicate row", streamCount, rows.size)
        val stream0After = rows.single { it.id == stream0Before.id }
        assertTrue(
            "the recorded size must never shrink: was ${stream0Before.responseSizeBytes}, now ${stream0After.responseSizeBytes}",
            stream0After.responseSizeBytes >= stream0Before.responseSizeBytes,
        )
        assertTrue(
            "the stored body must never be replaced by a shorter one",
            (stream0After.responseBody?.length ?: 0) >= body.length,
        )
        assertEquals("the earlier decoded body is still intact", body, stream0After.responseBody)

        // Every row in the whole set stays internally consistent: no row claims a size smaller than the
        // body bytes it actually holds.
        for (row in rows) {
            val stored = row.responseBody ?: ""
            val storedBytes = stored.substringBefore("\n<truncated>").toByteArray().size.toLong()
            assertTrue(
                "row ${row.path} has body $storedBytes bytes but size ${row.responseSizeBytes}",
                row.responseSizeBytes >= storedBytes,
            )
        }
    }

    @Test
    fun `a recreated exchange's later decode still reaches its row`() {
        // The queue holds a reference to the exchange it was created for. When the in-flight bound evicts
        // and recreates an exchange, the queue must follow the live object, or everything the recreated
        // exchange decodes would silently never be written.
        val sessionId = 9111L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        pump.outbound(scenario.clientInitial())
        pump.inbound(scenario.serverInitial() + scenario.serverHandshake())

        // Fill past the in-flight bound without flushing, so the queue holds now-evicted exchanges.
        var packetNumber = 0L
        for (i in 0 until 70) {
            pump.outbound(
                scenario.clientOneRtt(
                    packetNumber++,
                    QuicWireFixtures.streamFrame((i * 4).toLong(), 0, QuicWireFixtures.requestHeadersFrame("GET", "/q$i", scenario.sni)),
                ),
            )
        }
        // Stream 0's exchange is gone from the decoder; a response on it recreates the exchange.
        pump.inbound(
            scenario.serverOneRtt(
                packetNumber++,
                QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("418")),
            ),
        )
        pump.flushHttp3WritesForTest()

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        val stream0 = rows.filter { it.path == "/q0" || it.statusCode == 418 }
        assertEquals("stream 0 owns exactly one row", 1, stream0.size)
        assertEquals(
            "the recreated exchange's decoded status reached the row",
            418,
            stream0.single().statusCode,
        )
    }

    private companion object {
        const val CLIENT_IP = "10.0.0.7"
        const val SERVER_IP = "93.184.216.34"
        const val CLIENT_PORT = 54321
    }
}
