package com.example.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [CdpFetchAdapter]'s W9 response-stage handling, matching the plan's test spec
 * for work item 9 (response-stage Fetch with real bodies and enforced MODIFY_RESPONSE).
 *
 * Drives real `Fetch.requestPaused` JSON through [CdpFetchAdapter.handleRequestPaused] against a
 * real (Robolectric in-memory-backed) [RequestrDatabase] with a genuine [InterceptRuleEntity], and
 * observes the exact CDP commands [CdpClient] would have sent via [CdpClient.sendHookForTest] --
 * the same "no real socket" seam [CdpClientRoutingTest] uses on the receive side.
 */
@RunWith(RobolectricTestRunner::class)
class CdpFetchAdapterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        com.example.interception.rules.LegacyRuleTestSupport.clear()
        runBlocking {
            val dao = RequestrDatabase.get(context).interceptRuleDao()
            dao.getEnabledRules().forEach { dao.deleteById(it.id) }
        }
    }

    /** Records every `client.send(method, params, ...)` call and lets a test await one by method name. */
    private class RecordingClient(context: Context) {
        val cdpClient = CdpClient("localhost", 9222)
        val sentCommands = mutableListOf<Pair<String, JSONObject>>()
        private val latches = mutableMapOf<String, CountDownLatch>()

        /** Canned callback results keyed by method, consumed once each (e.g. Fetch.getResponseBody). */
        val cannedResults = mutableMapOf<String, JSONObject?>()

        init {
            cdpClient.sendHookForTest = { method, params, _, callback ->
                synchronized(sentCommands) { sentCommands.add(method to params) }
                latches[method]?.countDown()
                callback(cannedResults[method], null)
            }
        }

        fun awaitCommand(method: String, timeoutMs: Long = 2000): Boolean {
            synchronized(sentCommands) {
                if (sentCommands.any { it.first == method }) return true
            }
            val latch = CountDownLatch(1)
            latches[method] = latch
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS) ||
                synchronized(sentCommands) { sentCommands.any { it.first == method } }
        }

        fun paramsFor(method: String): JSONObject? =
            synchronized(sentCommands) { sentCommands.lastOrNull { it.first == method }?.second }

        fun wasCalled(method: String): Boolean =
            synchronized(sentCommands) { sentCommands.any { it.first == method } }
    }

    private fun insertRule(domain: String, action: RuleAction, replacementValue: String? = null) = runBlocking {
        RequestrDatabase.get(context).interceptRuleDao().insert(
            InterceptRuleEntity(name = "test-rule", domain = domain, action = action, replacementValue = replacementValue),
        )
        com.example.interception.rules.LegacyRuleTestSupport.syncSnapshotFromLegacy(RequestrDatabase.get(context))
    }

    private fun requestPausedEvent(
        requestId: String,
        url: String,
        responseStatusCode: Int? = null,
    ): JSONObject {
        val request = JSONObject().apply {
            put("url", url)
            put("method", "GET")
            put("headers", JSONObject())
        }
        return JSONObject().apply {
            put("requestId", requestId)
            put("request", request)
            if (responseStatusCode != null) {
                put("responseStatusCode", responseStatusCode)
                put("responseHeaders", org.json.JSONArray().put(JSONObject().apply { put("name", "Content-Type"); put("value", "application/json") }))
            }
        }
    }

    // ---- isResponseStage / isRedirectStatus pure-function tests ----

    @Test
    fun `params without responseStatusCode or responseErrorReason classify as request stage`() {
        assertFalse(CdpFetchAdapter.isResponseStage(requestPausedEvent("r1", "https://example.com/")))
    }

    @Test
    fun `params with responseStatusCode classify as response stage`() {
        assertTrue(CdpFetchAdapter.isResponseStage(requestPausedEvent("r1", "https://example.com/", responseStatusCode = 200)))
    }

    @Test
    fun `params with responseErrorReason classify as response stage`() {
        val event = JSONObject().apply { put("requestId", "r1"); put("responseErrorReason", "Failed") }
        assertTrue(CdpFetchAdapter.isResponseStage(event))
    }

    @Test
    fun `redirect status codes are recognized 300 through 399`() {
        assertTrue(CdpFetchAdapter.isRedirectStatus(301))
        assertTrue(CdpFetchAdapter.isRedirectStatus(302))
        assertTrue(CdpFetchAdapter.isRedirectStatus(399))
        assertFalse(CdpFetchAdapter.isRedirectStatus(200))
        assertFalse(CdpFetchAdapter.isRedirectStatus(404))
        assertFalse(CdpFetchAdapter.isRedirectStatus(400))
    }

    // ---- MODIFY_RESPONSE / LOG_ONLY / redirect behavior ----

    @Test
    fun `MODIFY_RESPONSE rule at response stage produces fulfillRequest with modified body`() {
        insertRule("example.com", RuleAction.MODIFY_RESPONSE, """{"statusCode":201,"addHeaders":{"X-Mock":"true"},"bodyReplace":"{\"mocked\":true}"}""")
        val recorder = RecordingClient(context)
        recorder.cannedResults["Fetch.getResponseBody"] = JSONObject().apply {
            put("body", "{\"real\":true}")
            put("base64Encoded", false)
        }
        val adapter = CdpFetchAdapter(recorder.cdpClient, context)

        adapter.handleRequestPaused(requestPausedEvent("r1", "https://example.com/api", responseStatusCode = 200))
        assertTrue("expected Fetch.fulfillRequest to be sent", recorder.awaitCommand("Fetch.fulfillRequest"))

        val fulfillParams = recorder.paramsFor("Fetch.fulfillRequest")!!
        assertEquals(201, fulfillParams.getInt("responseCode"))
        val decodedBody = String(Base64.getDecoder().decode(fulfillParams.getString("body")))
        assertEquals("{\"mocked\":true}", decodedBody)
        assertFalse("MODIFY_RESPONSE must not fall back to continueResponse", recorder.wasCalled("Fetch.continueResponse"))
        assertEquals(1, adapter.responseModifiedCount)
        assertEquals(1, adapter.bodiesCapturedCount)
    }

    @Test
    fun `LOG_ONLY rule at response stage produces continueResponse not fulfillRequest`() {
        insertRule("example.com", RuleAction.LOG_ONLY)
        val recorder = RecordingClient(context)
        val adapter = CdpFetchAdapter(recorder.cdpClient, context)

        adapter.handleRequestPaused(requestPausedEvent("r2", "https://example.com/api", responseStatusCode = 200))
        assertTrue(recorder.awaitCommand("Fetch.continueResponse"))

        assertFalse(recorder.wasCalled("Fetch.fulfillRequest"))
        assertFalse(recorder.wasCalled("Fetch.getResponseBody"))
        assertEquals(0, adapter.responseModifiedCount)
    }

    @Test
    fun `no matching rule at response stage produces continueResponse`() {
        val recorder = RecordingClient(context)
        val adapter = CdpFetchAdapter(recorder.cdpClient, context)

        adapter.handleRequestPaused(requestPausedEvent("r3", "https://unmatched.example/", responseStatusCode = 200))
        assertTrue(recorder.awaitCommand("Fetch.continueResponse"))
        assertFalse(recorder.wasCalled("Fetch.fulfillRequest"))
    }

    @Test
    fun `a 302 response skips getResponseBody`() {
        insertRule("example.com", RuleAction.MODIFY_RESPONSE, """{"statusCode":301}""")
        val recorder = RecordingClient(context)
        val adapter = CdpFetchAdapter(recorder.cdpClient, context)

        adapter.handleRequestPaused(requestPausedEvent("r4", "https://example.com/redirect", responseStatusCode = 302))
        assertTrue(recorder.awaitCommand("Fetch.fulfillRequest"))

        assertFalse("redirect responses must not call Fetch.getResponseBody", recorder.wasCalled("Fetch.getResponseBody"))
        assertEquals(0, adapter.bodiesCapturedCount)
    }

    @Test
    fun `base64-encoded body decoding round-trips`() {
        // S4 task 26: a header-only rewrite still fulfils the response from the fetched body; an
        // empty `{}` config is now a no-op rule and resumes the response untouched instead.
        insertRule("example.com", RuleAction.MODIFY_RESPONSE, """{"addHeaders":{"x-seen":"1"}}""")
        val recorder = RecordingClient(context)
        val originalBody = "{\"hello\":\"world\"}"
        recorder.cannedResults["Fetch.getResponseBody"] = JSONObject().apply {
            put("body", Base64.getEncoder().encodeToString(originalBody.toByteArray(Charsets.UTF_8)))
            put("base64Encoded", true)
        }
        val adapter = CdpFetchAdapter(recorder.cdpClient, context)

        adapter.handleRequestPaused(requestPausedEvent("r5", "https://example.com/data", responseStatusCode = 200))
        assertTrue(recorder.awaitCommand("Fetch.fulfillRequest"))

        val fulfillParams = recorder.paramsFor("Fetch.fulfillRequest")!!
        val decodedBody = String(Base64.getDecoder().decode(fulfillParams.getString("body")))
        // A header-only config leaves the real captured body untouched -- round-trip check.
        assertEquals(originalBody, decodedBody)
    }

    // ---- Request-stage behaviour is unchanged (regression guard) ----

    @Test
    fun `BLOCK rule at request stage sends failRequest not continueRequest`() {
        insertRule("blocked.example", RuleAction.BLOCK)
        val recorder = RecordingClient(context)
        val adapter = CdpFetchAdapter(recorder.cdpClient, context)

        adapter.handleRequestPaused(requestPausedEvent("r6", "https://blocked.example/x"))
        assertTrue(recorder.awaitCommand("Fetch.failRequest"))
        assertFalse(recorder.wasCalled("Fetch.continueRequest"))
    }

    @Test
    fun `MODIFY_RESPONSE rule at request stage still just continues the request unmodified`() {
        insertRule("example.com", RuleAction.MODIFY_RESPONSE, """{"bodyReplace":"ignored-at-request-stage"}""")
        val recorder = RecordingClient(context)
        val adapter = CdpFetchAdapter(recorder.cdpClient, context)

        adapter.handleRequestPaused(requestPausedEvent("r7", "https://example.com/api"))
        assertTrue(recorder.awaitCommand("Fetch.continueRequest"))
        assertNull(recorder.paramsFor("Fetch.continueRequest")?.opt("postData"))
    }
}
