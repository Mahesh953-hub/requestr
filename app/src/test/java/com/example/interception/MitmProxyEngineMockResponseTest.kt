package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §1.5 regression coverage: [MitmProxyEngine.handleParsedRequest]'s `MOCK_RESPONSE` branch must
 * short-circuit exactly like the pre-existing `BLOCK` branch -- write a synthesized response
 * straight to the client, never touch `upstreamOut`, and never enqueue into `pending` (there is
 * no real upstream response coming to pair with it) -- while still surfacing the mocked exchange
 * to the UI via [MitmProxyEngine.emitTransaction], unlike `BLOCK`.
 *
 * Drives [MitmProxyEngine.handleParsedRequest] directly (made `internal` for exactly this
 * reason, same as [MitmProxyEngine.emitTransaction]) with fake streams instead of a real
 * TLS/socket pair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineMockResponseTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertMockRule(domain: String, replacementValue: String?) = runBlocking {
        db.interceptRuleDao().insert(
            InterceptRuleEntity(
                name = "mock",
                domain = domain,
                action = RuleAction.MOCK_RESPONSE,
                replacementValue = replacementValue,
            )
        )
    }

    private fun request(url: String = "https://mock.example/api", host: String = "mock.example") = ParsedHttpMessage(
        isRequest = true,
        method = HttpMethod.GET,
        url = url,
        host = host,
    )

    /**
     * §1.5 MUST-FIX: [MitmProxyEngine.handleParsedRequest]'s 4th parameter is now a
     * [LazyUpstreamConnection] rather than a raw [OutputStream]. A `connectionFactory` that
     * throws if ever invoked doubles as a hard assertion that MOCK_RESPONSE never dials
     * upstream -- stronger than the old "check the buffer stayed empty" assertion, which could
     * never fail even if the production code regressed to dialing (nothing was ever written to
     * a socket that was never given production code access to write to).
     */
    private fun neverConnectUpstream(host: String = "mock.example", port: Int = 443) =
        LazyUpstreamConnection(
            host, port,
            connectionFactory = { _, _ -> throw AssertionError("MOCK_RESPONSE must never dial upstream") },
        )

    @Test
    fun `MOCK_RESPONSE writes the synthesized response to the client and never touches upstream`() {
        insertMockRule("mock.example", """{"statusCode":201,"bodyReplace":"{\"ok\":true}"}""")

        val engine = MitmProxyEngine()
        val clientOut = ByteArrayOutputStream()
        val upstream = neverConnectUpstream()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()

        engine.handleParsedRequest(
            db, request(), ByteArray(0), upstream, clientOut, Any(), pending,
            "mock.example", 443, true, true,
        )

        val responseText = clientOut.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("client must receive a 201 status line", responseText.startsWith("HTTP/1.1 201"))
        assertTrue("client must receive the mocked body", responseText.endsWith("{\"ok\":true}"))
        assertTrue("upstream must never be dialed for MOCK_RESPONSE", !upstream.isConnected)
    }

    @Test
    fun `MOCK_RESPONSE never enqueues into pending`() {
        insertMockRule("mock.example", """{"bodyReplace":"mocked"}""")

        val engine = MitmProxyEngine()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()

        engine.handleParsedRequest(
            db, request(), ByteArray(0), neverConnectUpstream(), ByteArrayOutputStream(), Any(), pending,
            "mock.example", 443, true, true,
        )

        assertTrue("MOCK_RESPONSE must not enqueue a request awaiting a real response", pending.isEmpty())
    }

    @Test
    fun `MOCK_RESPONSE still emits a captured transaction so it is visible in the traffic list`() {
        insertMockRule("mock.example", """{"statusCode":404,"bodyReplace":"not found"}""")

        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.handleParsedRequest(
            db, request(), ByteArray(0), neverConnectUpstream(), ByteArrayOutputStream(), Any(),
            ConcurrentLinkedQueue(), "mock.example", 443, true, true,
        )

        val entity = captured ?: throw AssertionError("MOCK_RESPONSE must still emit a captured transaction")
        assertEquals(404, entity.statusCode)
        assertEquals("not found", entity.responseBody)
        assertTrue("mocked transactions are still marked as decrypted", entity.wasDecrypted)
    }

    @Test
    fun `MOCK_RESPONSE with no replacementValue falls back to a plain 200`() {
        insertMockRule("mock.example", null)

        val engine = MitmProxyEngine()
        val clientOut = ByteArrayOutputStream()

        engine.handleParsedRequest(
            db, request(), ByteArray(0), neverConnectUpstream(), clientOut, Any(),
            ConcurrentLinkedQueue(), "mock.example", 443, true, true,
        )

        val responseText = clientOut.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(responseText.startsWith("HTTP/1.1 200"))
    }
}
