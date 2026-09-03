package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.ParsedHttpMessage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M4 follow-up: a `CHAOS` rule the user can create, enable and see listed as active must
 * actually do something to live traffic. These tests drive [MitmProxyEngine.handleParsedRequest]
 * / [MitmProxyEngine.handleParsedResponse] directly (same harness as
 * [MitmProxyEngineMockResponseTest] / [MitmProxyEngineCryptoFailClosedTest]) with an injected
 * [Random] and a recording `chaosSleep`, so every roll is deterministic and no test really waits.
 *
 * Contract under test:
 *  - `failRate` is rolled on the REQUEST leg: a hit answers the client with the synthetic failure,
 *    never dials upstream, never enqueues into `pending`, still emits a captured row.
 *  - `corruptBodyRate` / `delayMs` are rolled on the RESPONSE leg against the real response.
 *  - every injected outcome is stamped with [RuleEnforcer.CHAOS_ATTRIBUTION_HEADER], and that
 *    header is persisted even when response-header logging is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineChaosTest {

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

    private fun insertChaosRule(config: String, name: String = "flaky-api") = runBlocking {
        db.interceptRuleDao().insert(
            InterceptRuleEntity(
                name = name,
                domain = "chaos.example",
                action = RuleAction.CHAOS,
                replacementValue = config,
            )
        )
    }

    private fun request() = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://chaos.example/api", host = "chaos.example",
    )

    private fun response(body: String = """{"ok":true}""") = ParsedHttpMessage(
        isRequest = false,
        method = null,
        statusCode = 200,
        statusMessage = "OK",
        headers = mapOf("content-type" to "application/json"),
        body = body,
    )

    private fun neverConnectUpstream() = LazyUpstreamConnection(
        "chaos.example", 443,
        socketConnector = { _, _ -> throw AssertionError("a chaos FAIL must never dial upstream") },
    )

    /** Upstream stand-in that records the dial attempt and aborts -- proves the request WOULD have gone out. */
    private class Dialed : IOException("dialed upstream")
    private fun recordingUpstream() = LazyUpstreamConnection(
        "chaos.example", 443,
        socketConnector = { _, _ -> throw Dialed() },
    )

    /**
     * A [Random] whose `nextDouble()` always returns [value] -- forces or forbids a rate roll.
     * `nextInt(until)` walks distinct indices so byte flips never land twice on the same
     * position and cancel out (x XOR k XOR k == x).
     */
    private fun fixedRandom(value: Double) = object : Random() {
        private var counter = 0
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
        override fun nextInt(until: Int): Int = (counter++) % until
        override fun nextLong(from: Long, until: Long): Long = from
    }

    private fun wsState() = MitmProxyEngine.ConnectionProtocolState()

    // ── request leg: failRate ────────────────────────────────────────────

    @Test
    fun `failRate 1 answers the client with the synthetic 503, never dials upstream, never enqueues`() {
        insertChaosRule("""{"failRate":1.0}""")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it }, chaosRandom = fixedRandom(0.0))
        val clientOut = ByteArrayOutputStream()
        val upstream = neverConnectUpstream()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()

        engine.handleParsedRequest(db, request(), ByteArray(0), upstream, clientOut, Any(), pending, "chaos.example", 443, true, true)

        val wire = clientOut.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("client must receive the chaos 503 status line, got: $wire", wire.startsWith("HTTP/1.1 503 Service Unavailable"))
        assertTrue("client must receive the attribution header", wire.contains("x-requestr-chaos: fail; rule=\"flaky-api\""))
        assertTrue(wire.endsWith("""{"error":"chaos-injected failure"}"""))
        assertFalse("upstream must never be dialed", upstream.isConnected)
        assertTrue("nothing may await a real response", pending.isEmpty())

        val entity = captured ?: throw AssertionError("a chaos failure must still be a captured row")
        assertEquals(503, entity.statusCode)
        assertEquals(0L, entity.latencyMs)
        assertEquals("fail; rule=\"flaky-api\"", entity.responseHeaders[RuleEnforcer.CHAOS_ATTRIBUTION_HEADER])
    }

    @Test
    fun `failStatusCode, failStatusMessage and failBody override the synthetic failure`() {
        insertChaosRule("""{"failRate":1.0,"failStatusCode":429,"failStatusMessage":"Slow Down","failBody":"rate limited"}""")
        val engine = MitmProxyEngine(chaosRandom = fixedRandom(0.0))
        val clientOut = ByteArrayOutputStream()

        engine.handleParsedRequest(db, request(), ByteArray(0), neverConnectUpstream(), clientOut, Any(), ConcurrentLinkedQueue(), "chaos.example", 443, true, true)

        val wire = clientOut.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(wire, wire.startsWith("HTTP/1.1 429 Slow Down"))
        assertTrue(wire, wire.endsWith("rate limited"))
    }

    @Test
    fun `failRate 0 lets the request go upstream untouched`() {
        insertChaosRule("""{"failRate":0.0,"corruptBodyRate":1.0}""")
        val engine = MitmProxyEngine(chaosRandom = fixedRandom(0.0))
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()
        val clientOut = ByteArrayOutputStream()

        try {
            engine.handleParsedRequest(db, request(), "GET /api HTTP/1.1\r\n\r\n".toByteArray(), recordingUpstream(), clientOut, Any(), pending, "chaos.example", 443, true, true)
            fail("the request leg must dial upstream when the fail die did not hit")
        } catch (expected: Dialed) {
            // upstream was dialed: the request leg passed through
        }
        assertEquals("nothing may be written to the client on the request leg", 0, clientOut.size())
    }

    @Test
    fun `the fail die is the only die rolled on the request leg`() {
        // corruptBodyRate 1.0 + delay must NOT short-circuit the request: those roll on the response.
        insertChaosRule("""{"failRate":0.5,"corruptBodyRate":1.0,"delayMs":[500,500]}""")
        // 0.7 >= failRate 0.5 -> no fail; the same value would trigger corruptBodyRate 1.0 if it were rolled here.
        val engine = MitmProxyEngine(chaosRandom = fixedRandom(0.7))
        val clientOut = ByteArrayOutputStream()

        try {
            engine.handleParsedRequest(db, request(), ByteArray(0), recordingUpstream(), clientOut, Any(), ConcurrentLinkedQueue(), "chaos.example", 443, true, true)
            fail("must dial upstream")
        } catch (expected: Dialed) {
        }
        assertEquals(0, clientOut.size())
    }

    // ── response leg: corruptBodyRate / delayMs ──────────────────────────

    @Test
    fun `corruptBodyRate 1 corrupts the real response body and stamps the attribution header`() {
        insertChaosRule("""{"corruptBodyRate":1.0}""")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it }, chaosRandom = fixedRandom(0.0))
        val clientOut = ByteArrayOutputStream()
        val original = """{"ok":true,"payload":"0123456789abcdef0123456789abcdef"}"""
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(db, response(original), ByteArray(0), clientOut, Any(), pending, "chaos.example", 443, true, true, wsState())

        val wire = clientOut.toByteArray().toString(Charsets.UTF_8)
        val wireBody = wire.substringAfter("\r\n\r\n")
        assertNotEquals("body on the wire must be corrupted", original, wireBody)
        assertEquals("corrupted body must keep its length (byte flips, not truncation)", original.length, wireBody.length)
        assertTrue(wire, wire.contains("x-requestr-chaos: corrupt-body; rule=\"flaky-api\""))
        assertTrue("Content-Length must match the corrupted body", wire.contains("content-length: ${wireBody.toByteArray().size}", ignoreCase = true))

        val entity = captured ?: throw AssertionError("must emit a captured row")
        assertEquals(wireBody, entity.responseBody)
        assertEquals("corrupt-body; rule=\"flaky-api\"", entity.responseHeaders[RuleEnforcer.CHAOS_ATTRIBUTION_HEADER])
    }

    @Test
    fun `corruptBodyRate 1 on a binary body persists the corrupted bytes that went on the wire, not the original`() {
        insertChaosRule("""{"corruptBodyRate":1.0}""")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it }, chaosRandom = fixedRandom(0.0))
        val clientOut = ByteArrayOutputStream()
        val original = ByteArray(400) { i -> (0xFF - (i % 200)).toByte() }
        val binaryResponse = ParsedHttpMessage(
            isRequest = false, method = null, statusCode = 200, statusMessage = "OK",
            headers = mapOf("content-type" to "image/png"),
            body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER, isBodyBinary = true,
            rawBodyBytes = original, decodedBodyBytes = original,
        )
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(db, binaryResponse, original, clientOut, Any(), pending, "chaos.example", 443, true, true, wsState())

        val wire = clientOut.toByteArray()
        val headerEnd = wire.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        val wireBody = wire.copyOfRange(headerEnd, wire.size)
        assertEquals(original.size, wireBody.size)
        assertFalse("body on the wire must be corrupted", wireBody.contentEquals(original))

        val entity = captured ?: throw AssertionError("must emit a captured row")
        assertTrue(entity.isResponseBodyBinary)
        assertNull(entity.responseBody)
        assertTrue("row must hold the bytes the client received", entity.responseBodyBytes!!.contentEquals(wireBody))
        assertFalse("row must NOT hold the pre-corruption original", entity.responseBodyBytes!!.contentEquals(original))
        assertEquals(original.size.toLong(), entity.responseSizeBytes)
        assertEquals("corrupt-body; rule=\"flaky-api\"", entity.responseHeaders[RuleEnforcer.CHAOS_ATTRIBUTION_HEADER])
    }

    @Test
    fun `fixed delayMs range sleeps exactly that long, outside the client write lock, and stamps the header`() {
        insertChaosRule("""{"delayMs":[1500,1500]}""")
        val slept = mutableListOf<Long>()
        val lock = Any()
        var heldLockDuringSleep: Boolean? = null
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(
            onDecryptedTransaction = { captured = it },
            chaosRandom = fixedRandom(0.0),
            chaosSleep = { ms ->
                slept += ms
                heldLockDuringSleep = Thread.holdsLock(lock)
            },
        )
        val clientOut = ByteArrayOutputStream()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(db, response(), ByteArray(0), clientOut, lock, pending, "chaos.example", 443, true, true, wsState())

        assertEquals(listOf(1500L), slept)
        assertEquals("delay must not hold the shared client write lock", false, heldLockDuringSleep)
        val wire = clientOut.toByteArray().toString(Charsets.UTF_8)
        assertTrue(wire, wire.startsWith("HTTP/1.1 200 OK"))
        assertTrue(wire, wire.contains("x-requestr-chaos: delay=1500ms; rule=\"flaky-api\""))
        assertTrue(wire.endsWith("""{"ok":true}"""))
        assertEquals("delay=1500ms; rule=\"flaky-api\"", captured!!.responseHeaders[RuleEnforcer.CHAOS_ATTRIBUTION_HEADER])
    }

    @Test
    fun `delayMs range samples within min and max inclusive`() {
        insertChaosRule("""{"delayMs":[100,300]}""")
        val slept = mutableListOf<Long>()
        // Real RNG here on purpose: the bound must hold for every sample, not a fixed one.
        val engine = MitmProxyEngine(chaosRandom = Random(42), chaosSleep = { slept += it })

        repeat(50) {
            val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }
            engine.handleParsedResponse(db, response(), ByteArray(0), ByteArrayOutputStream(), Any(), pending, "chaos.example", 443, true, true, wsState())
        }

        assertEquals(50, slept.size)
        assertTrue("every sampled delay must be within [100, 300], got $slept", slept.all { it in 100L..300L })
    }

    @Test
    fun `a pass-through roll forwards the exact original bytes with no attribution header`() {
        insertChaosRule("""{"failRate":0.1,"corruptBodyRate":0.1}""")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it }, chaosRandom = fixedRandom(0.99))
        val clientOut = ByteArrayOutputStream()
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}".toByteArray()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(db, response(), raw, clientOut, Any(), pending, "chaos.example", 443, true, true, wsState())

        assertTrue("pass-through must forward the original bytes verbatim", raw.contentEquals(clientOut.toByteArray()))
        assertNull(captured!!.responseHeaders[RuleEnforcer.CHAOS_ATTRIBUTION_HEADER])
    }

    @Test
    fun `attribution header is persisted even when response header logging is off`() {
        insertChaosRule("""{"corruptBodyRate":1.0}""")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it }, chaosRandom = fixedRandom(0.0))
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(db, response(), ByteArray(0), ByteArrayOutputStream(), Any(), pending, "chaos.example", 443, logRequests = true, logResponses = false, wsState = wsState())

        val headers = captured!!.responseHeaders
        assertEquals("corrupt-body; rule=\"flaky-api\"", headers[RuleEnforcer.CHAOS_ATTRIBUTION_HEADER])
        assertNull("server headers must still be dropped when logging is off", headers["content-type"])
        assertNull("body must still be dropped when logging is off", captured!!.responseBody)
    }

    @Test
    fun `fail attribution header is persisted even when response header logging is off`() {
        insertChaosRule("""{"failRate":1.0}""")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it }, chaosRandom = fixedRandom(0.0))

        engine.handleParsedRequest(db, request(), ByteArray(0), neverConnectUpstream(), ByteArrayOutputStream(), Any(), ConcurrentLinkedQueue(), "chaos.example", 443, true, false)

        assertEquals("fail; rule=\"flaky-api\"", captured!!.responseHeaders[RuleEnforcer.CHAOS_ATTRIBUTION_HEADER])
        assertNull(captured!!.responseHeaders["content-type"])
    }

    // ── SCRIPT_REWRITE error visibility (same attribution mechanism) ─────

    private fun insertScriptRule(script: String) = runBlocking {
        db.interceptRuleDao().insert(
            InterceptRuleEntity(
                name = "js-rule",
                domain = "chaos.example",
                action = RuleAction.SCRIPT_REWRITE,
                replacementValue = org.json.JSONObject().put("script", script).toString(),
            )
        )
    }

    @Test
    fun `a throwing SCRIPT_REWRITE on the response leg stamps x-requestr-script-error on the wire and the row`() {
        insertScriptRule("throw new Error('boom');")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })
        val clientOut = ByteArrayOutputStream()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(db, response(), ByteArray(0), clientOut, Any(), pending, "chaos.example", 443, true, false, wsState())

        val wire = clientOut.toByteArray().toString(Charsets.UTF_8)
        assertTrue(wire, wire.contains("x-requestr-script-error: "))
        assertTrue(wire, wire.contains("boom"))
        assertTrue(wire, wire.contains("rule=\"js-rule\""))
        val persisted = captured!!.responseHeaders[RuleEnforcer.SCRIPT_ERROR_HEADER]
            ?: throw AssertionError("script error must be persisted even with response logging off")
        assertTrue(persisted, persisted.contains("boom") && persisted.endsWith("; rule=\"js-rule\""))
    }

    @Test
    fun `a throwing SCRIPT_REWRITE on the request leg is persisted on the request headers but never sent upstream`() {
        insertScriptRule("throw new Error('kaboom');")
        val engine = MitmProxyEngine()
        val req = request()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()

        try {
            engine.handleParsedRequest(db, req, ByteArray(0), recordingUpstream(), ByteArrayOutputStream(), Any(), pending, "chaos.example", 443, true, true)
            fail("must dial upstream: a script error is fail-open for the request itself")
        } catch (expected: Dialed) {
        }
        // The header is on the in-memory request, which is what emitTransaction persists once
        // the response arrives (and keeps even with request logging off). The upstream wire bytes
        // are serialized BEFORE the engine adds it (see the SCRIPT_REWRITE branch), and the fake
        // upstream above aborts the dial, so nothing here can reach a server.
        val value = req.headers[RuleEnforcer.SCRIPT_ERROR_HEADER] ?: throw AssertionError("request must carry the script error for persistence")
        assertTrue(value, value.contains("kaboom") && value.endsWith("; rule=\"js-rule\""))
    }

    @Test
    fun `a clean SCRIPT_REWRITE leaves no error header`() {
        insertScriptRule("pm.response.headers.set('X-Rewritten', 'yes');")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(db, response(), ByteArray(0), ByteArrayOutputStream(), Any(), pending, "chaos.example", 443, true, true, wsState())

        assertNull(captured!!.responseHeaders[RuleEnforcer.SCRIPT_ERROR_HEADER])
        assertEquals("yes", captured!!.responseHeaders.entries.firstOrNull { it.key.equals("X-Rewritten", true) }?.value)
    }
}
