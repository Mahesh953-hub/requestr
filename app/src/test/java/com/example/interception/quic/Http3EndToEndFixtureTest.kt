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
 * The end-to-end HTTP/3 fixture gate (v6.0 plan Task 17): drives a real [PacketPump] with real
 * protected QUIC packets, through the real [QuicDatagramRouter] → [QuicConnectionDecoder] →
 * [Http3StreamParser] pipeline, into a **real Room database**, and asserts on what was persisted.
 *
 * This is the test that distinguishes a working runtime from a set of working components: every earlier
 * QUIC test drives the decoder directly, while this one only ever calls
 * [PacketPump.ingestQuicDatagram] — the same entry point `parseIpv4`/`parseIpv6` use for a live UDP/443
 * datagram — and then reads the `quic_connections` and `captured_traffic` tables.
 *
 * It also pins the honesty rules: with key material registered, a decoded exchange becomes a
 * `protocol = "HTTP/3"`, `wasDecrypted = true` traffic row with a real status code and body; without key
 * material, the connection row records `OBSERVED_NO_KEYS`/`PARTIALLY_DECODED` with no key-log provenance
 * and the traffic table stays **empty** rather than gaining a placeholder.
 *
 * Deterministic and offline by construction: all bytes come from [QuicWireFixtures], so nothing depends
 * on emulator network egress (which the release plan records as unavailable).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class Http3EndToEndFixtureTest {

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
        val inFile = File.createTempFile("h3-pump-in", ".bin")
        val outFile = File.createTempFile("h3-pump-out", ".bin")
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

    /** Drives a full handshake + one HTTP/3 request/response through [pump] using [scenario]'s packets. */
    private fun driveDecodedExchange(pump: PacketPump, scenario: QuicConnectionScenario, path: String, body: String) {
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
                    data = QuicWireFixtures.responseHeadersFrame("200", mapOf("content-type" to "application/json")) +
                        QuicWireFixtures.dataFrame(body.toByteArray()),
                    fin = true,
                ),
            ),
        )
    }

    @Test
    fun `a decoded HTTP-3 exchange is persisted as a decrypted traffic row with real provenance`() {
        val sessionId = 9001L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        driveDecodedExchange(pump, scenario, "/v1/status", """{"state":"ok"}""")

        val connection = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single()
        assertEquals("quic.test", connection.sniHost)
        assertEquals("h3", connection.alpn)
        assertEquals(QuicDecodeStatus.FULLY_DECODED, connection.decodeStatus)
        assertEquals("HANDSHAKE_COMPLETE", connection.lifecycleState)
        assertEquals(
            "the ClientHello random is persisted as the decode provenance",
            scenario.clientRandom.joinToString("") { "%02x".format(it) },
            connection.clientRandomHex,
        )
        val labels = requireNotNull(connection.keylogLabels).split(",").toSet()
        assertTrue("the resolved handshake secret is recorded", "SERVER_HANDSHAKE_TRAFFIC_SECRET" in labels)
        assertTrue("the resolved 1-RTT secrets are recorded", "SERVER_TRAFFIC_SECRET_0" in labels)
        assertTrue("CLIENT_TRAFFIC_SECRET_0" in labels)

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        val row = rows.single()
        assertEquals(PacketPump.HTTP3_PROTOCOL, row.protocol)
        assertEquals("GET", row.method)
        assertEquals("https://quic.test/v1/status", row.url)
        assertEquals("quic.test", row.host)
        assertEquals("/v1/status", row.path)
        assertEquals(200, row.statusCode)
        assertEquals("""{"state":"ok"}""", row.responseBody)
        assertEquals("application/json", row.responseHeaders["content-type"])
        assertTrue("these bytes came out of an authenticated AEAD payload", row.wasDecrypted)
        assertFalse(row.isUndecoded)
        assertEquals(
            "the row joins the QUIC connection by exact key",
            connection.connectionId,
            row.connectionId,
        )
        assertEquals(14L, row.responseSizeBytes)
    }

    @Test
    fun `further DATA frames update the same traffic row instead of inserting a duplicate`() {
        val sessionId = 9002L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        pump.outbound(scenario.clientInitial())
        pump.inbound(scenario.serverInitial() + scenario.serverHandshake())
        pump.outbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/stream", scenario.sni), fin = true)),
        )

        // The request row exists as soon as the request HEADERS decoded, with no status yet.
        val afterRequest = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.single()
        assertNull("no :status has decoded yet, so none may be claimed", afterRequest.statusCode)

        val headerBytes = QuicWireFixtures.responseHeadersFrame("206")
        pump.inbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, headerBytes)))
        pump.inbound(
            scenario.serverOneRtt(
                1,
                QuicWireFixtures.streamFrame(0, headerBytes.size.toLong(), QuicWireFixtures.dataFrame("part-one".toByteArray())),
            ),
        )
        val secondChunk = QuicWireFixtures.dataFrame("part-two".toByteArray())
        pump.inbound(
            scenario.serverOneRtt(
                2,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = (headerBytes.size + QuicWireFixtures.dataFrame("part-one".toByteArray()).size).toLong(),
                    data = secondChunk,
                    fin = true,
                ),
            ),
        )

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        assertEquals("one exchange must persist exactly one row", 1, rows.size)
        assertEquals(afterRequest.id, rows.single().id)
        assertEquals(206, rows.single().statusCode)
        assertEquals("part-onepart-two", rows.single().responseBody)
        assertEquals(16L, rows.single().responseSizeBytes)
    }

    @Test
    fun `without key material no traffic row is written and the connection records the missing keys`() {
        val sessionId = 9003L
        val scenario = QuicConnectionScenario() // secrets deliberately NOT registered
        val pump = newPump(sessionId)

        driveDecodedExchange(pump, scenario, "/never-decoded", "unreadable")

        val connection = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single()
        assertEquals(
            "the client Initial really decrypted, so the status is partial -- not fully, not detected-only",
            QuicDecodeStatus.PARTIALLY_DECODED,
            connection.decodeStatus,
        )
        assertNull("no traffic secret resolved, so no key-log provenance", connection.keylogLabels)
        assertNotNull("identity was still recovered from the ClientHello", connection.clientRandomHex)
        assertEquals("quic.test", connection.sniHost)

        val rows = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }
        assertTrue("an undecryptable connection must leave the traffic table empty", rows.isEmpty())
    }

    @Test
    fun `a connection observed only in 1-RTT with no keys persists OBSERVED_NO_KEYS and no rows`() {
        val sessionId = 9004L
        val scenario = QuicConnectionScenario()
        val pump = newPump(sessionId)

        // Capture started mid-connection: no Initial, so no ServerHello and no ClientHello identity.
        pump.inbound(scenario.serverOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.responseHeadersFrame("200"))))

        val connection = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single()
        assertEquals(QuicDecodeStatus.OBSERVED_NO_KEYS, connection.decodeStatus)
        assertNull(connection.clientRandomHex)
        assertNull(connection.keylogLabels)
        assertTrue(runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.isEmpty())
    }

    @Test
    fun `a ChaCha20 connection decodes end to end through the pump`() {
        val sessionId = 9005L
        val scenario = QuicConnectionScenario(suite = QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256)
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        driveDecodedExchange(pump, scenario, "/chacha", "chacha-body")

        val connection = runBlocking { db.quicConnectionDao().getBySession(sessionId) }.single()
        assertEquals(QuicDecodeStatus.FULLY_DECODED, connection.decodeStatus)
        val row = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.single()
        assertEquals("chacha-body", row.responseBody)
        assertEquals("/chacha", row.path)
    }

    @Test
    fun `an IPv6 datagram decodes identically to IPv4`() {
        val sessionId = 9006L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        pump.ingestQuicDatagram(scenario.clientInitial(), CLIENT_IP_V6, CLIENT_PORT, SERVER_IP_V6, 443, isIpv6 = true)
        pump.ingestQuicDatagram(scenario.serverInitial() + scenario.serverHandshake(), SERVER_IP_V6, 443, CLIENT_IP_V6, CLIENT_PORT, isIpv6 = true)
        pump.ingestQuicDatagram(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/v6", scenario.sni), fin = true)),
            CLIENT_IP_V6, CLIENT_PORT, SERVER_IP_V6, 443, isIpv6 = true,
        )

        val row = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.single()
        assertEquals("/v6", row.path)
        assertEquals(PacketPump.HTTP3_PROTOCOL, row.protocol)
    }

    @Test
    fun `a binary response body is recorded as a byte-count marker rather than mojibake`() {
        val sessionId = 9007L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)
        val binary = byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x01, 0x80.toByte(), 0x7F)

        pump.outbound(scenario.clientInitial())
        pump.inbound(scenario.serverInitial() + scenario.serverHandshake())
        pump.outbound(
            scenario.clientOneRtt(0, QuicWireFixtures.streamFrame(0, 0, QuicWireFixtures.requestHeadersFrame("GET", "/binary", scenario.sni), fin = true)),
        )
        pump.inbound(
            scenario.serverOneRtt(
                0,
                QuicWireFixtures.streamFrame(
                    streamId = 0,
                    offset = 0,
                    data = QuicWireFixtures.responseHeadersFrame("200") + QuicWireFixtures.dataFrame(binary),
                    fin = true,
                ),
            ),
        )

        val row = runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.single()
        assertEquals("<binary ${binary.size} bytes>", row.responseBody)
        assertEquals("the real observed size is still recorded", binary.size.toLong(), row.responseSizeBytes)
    }

    @Test
    fun `idle QUIC decode state is released by the bounded cleanup`() {
        val sessionId = 9008L
        val scenario = QuicConnectionScenario()
        scenario.registerSecrets()
        val pump = newPump(sessionId)

        driveDecodedExchange(pump, scenario, "/cleanup", "bye")
        assertEquals("one live decoder for one connection", 1, pump.quicDecoderCount())

        // A cutoff in the future makes every decoder idle, the same condition the flush loop applies
        // after FLOW_TTL_MS with no packets.
        pump.pruneQuicDecodersForTest(System.currentTimeMillis() + 60_000L)

        assertEquals("idle decode state must be released", 0, pump.quicDecoderCount())
        assertEquals(
            "the persisted row survives the cleanup",
            1,
            runBlocking { db.capturedTrafficDao().getBySession(sessionId) }.size,
        )
    }

    private companion object {
        const val CLIENT_IP = "10.0.0.7"
        const val SERVER_IP = "93.184.216.34"
        const val CLIENT_IP_V6 = "2001:db8::7"
        const val SERVER_IP_V6 = "2606:2800:220:1:248:1893:25c8:1946"
        const val CLIENT_PORT = 54321
    }
}
