package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Task 13 / CAT-2d: [KeyLogProducer.postLines] is the PRIMARY, authenticated cross-process
 * producer path for TLS/QUIC key material -- see [KeyLogProducer]'s kdoc for why the previous
 * sole mechanism ([KeyLogRegistryBridge]'s in-process reflection) is a permanent no-op in a real
 * third-party host app. This is the SDK-side half of the end-to-end proof: drives a real
 * [MockWebServer] (already a `:sdk` test dependency, same technique as
 * [RequestrObserverFlushTest]) and confirms the exact request shape + header
 * [com.example.api.LocalApiServer]'s `/api/keylog` route (in the `:app` module, not visible from
 * here) expects, plus graceful handling of non-2xx responses and a missing pairing token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class KeyLogProducerHttpTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        KeyLogProducer.resetForTests()
    }

    @After
    fun tearDown() {
        server.shutdown()
        KeyLogProducer.resetForTests()
    }

    @Test
    fun `postLines POSTs to api-keylog with the Bearer header and the exact NSS line body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}"""))
        KeyLogProducer.init(server.url("/").toString().trimEnd('/'), "the-pairing-token")

        val cr = "aa".repeat(32)
        val line = "CLIENT_HANDSHAKE_TRAFFIC_SECRET $cr aabbccdd"
        KeyLogProducer.postLines(listOf(line))

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue("expected a request to reach the mock server", recorded != null)
        assertEquals("POST", recorded!!.method)
        assertEquals("/api/keylog", recorded.path)
        assertEquals("Bearer the-pairing-token", recorded.getHeader("Authorization"))

        val body = JSONObject(recorded.body.readUtf8())
        val lines = body.getJSONArray("lines")
        assertEquals(1, lines.length())
        assertEquals(line, lines.getString(0))
    }

    @Test
    fun `postLines sends every line in one request body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":2}"""))
        KeyLogProducer.init(server.url("/").toString().trimEnd('/'), "tok")

        val cr1 = "bb".repeat(32)
        val cr2 = "cc".repeat(32)
        val lines = listOf(
            "CLIENT_TRAFFIC_SECRET_0 $cr1 11223344",
            "SERVER_TRAFFIC_SECRET_0 $cr2 55667788",
        )
        KeyLogProducer.postLines(lines)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        val body = JSONObject(recorded!!.body.readUtf8())
        val jsonLines = body.getJSONArray("lines")
        assertEquals(2, jsonLines.length())
        assertEquals(lines[0], jsonLines.getString(0))
        assertEquals(lines[1], jsonLines.getString(1))
    }

    @Test
    fun `postLines handles a non-2xx response without throwing`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad token"}"""))
        KeyLogProducer.init(server.url("/").toString().trimEnd('/'), "stale-token")

        // Must not throw -- keylog production is a bonus signal, never allowed to crash the
        // host app's real traffic path.
        KeyLogProducer.postLines(listOf("CLIENT_TRAFFIC_SECRET_0 ${"dd".repeat(32)} aabb"))

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue("expected the request to still have been attempted", recorded != null)
    }

    @Test
    fun `postLines is a no-op that never throws when init was never called with a pairing token`() {
        // KeyLogProducer.resetForTests() in setUp() left pairingToken null -- postLines must
        // skip the network call entirely rather than POST with a null/blank credential.
        KeyLogProducer.postLines(listOf("CLIENT_TRAFFIC_SECRET_0 ${"ee".repeat(32)} aabb"))

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)
        assertFalse("expected no request when no pairing token was configured", recorded != null)
    }

    @Test
    fun `postLines is a no-op that never throws for an empty line list`() {
        KeyLogProducer.init(server.url("/").toString().trimEnd('/'), "tok")

        KeyLogProducer.postLines(emptyList())

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)
        assertFalse("expected no request for an empty line list", recorded != null)
    }

    @Test
    fun `recordSecrets builds one NSS line per secret and posts them all in one request`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":2}"""))
        KeyLogProducer.init(server.url("/").toString().trimEnd('/'), "tok")

        val clientRandom = ByteArray(32) { 0x42 }
        KeyLogProducer.recordSecrets(
            clientRandom,
            listOf(
                "CLIENT_HANDSHAKE_TRAFFIC_SECRET" to byteArrayOf(0x01, 0x02),
                "SERVER_HANDSHAKE_TRAFFIC_SECRET" to byteArrayOf(0x03, 0x04),
            ),
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        val body = JSONObject(recorded!!.body.readUtf8())
        val lines = body.getJSONArray("lines")
        assertEquals(2, lines.length())
        val crHex = "42".repeat(32)
        assertEquals("CLIENT_HANDSHAKE_TRAFFIC_SECRET $crHex 0102", lines.getString(0))
        assertEquals("SERVER_HANDSHAKE_TRAFFIC_SECRET $crHex 0304", lines.getString(1))
    }
}
