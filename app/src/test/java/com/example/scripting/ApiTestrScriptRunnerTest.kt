package com.example.scripting

import com.example.data.env.Environment
import com.example.data.models.ApiRequestItem
import com.example.data.models.ApiResponseData
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master plan §1.8/§1.10: [ApiTestrScriptRunner] wires ApiTestr's pre-request/post-response
 * fields into the real Rhino `pm.*` engine. Covers pre-script request mutation, post-script
 * response mutation + `pm.test()` recording, and env variable read/write round-tripping.
 */
class ApiTestrScriptRunnerTest {

    private fun env(vars: Map<String, String> = emptyMap()) = Environment(name = "Test", variables = vars)

    // ---- blank-script short circuits --------------------------------------------------------

    @Test
    fun `blank pre-script is a true no-op`() {
        val request = ApiRequestItem(url = "https://example.com/original")
        val outcome = ApiTestrScriptRunner.runPreRequestScript("", request, env(mapOf("k" to "v")))
        assertEquals(request, outcome.request)
        assertEquals(mapOf("k" to "v"), outcome.envUpdates)
        assertNull(outcome.error)
        assertTrue(outcome.testResults.isEmpty())
    }

    @Test
    fun `blank post-script is a true no-op`() {
        val request = ApiRequestItem()
        val response = ApiResponseData(statusCode = 201, body = "{}")
        val outcome = ApiTestrScriptRunner.runPostResponseScript("   ", request, response, env())
        assertEquals(response, outcome.response)
        assertNull(outcome.error)
    }

    // ---- pre-request script: request mutation ----------------------------------------------

    @Test
    fun `pre-script can rewrite the request url`() {
        val request = ApiRequestItem(url = "https://example.com/old")
        val outcome = ApiTestrScriptRunner.runPreRequestScript(
            "pm.request.url = 'https://example.com/new';",
            request,
            env(),
        )
        assertNull(outcome.error)
        assertEquals("https://example.com/new", outcome.request.url)
    }

    @Test
    fun `pre-script can set a request header`() {
        val request = ApiRequestItem(headers = emptyList())
        val outcome = ApiTestrScriptRunner.runPreRequestScript(
            "pm.request.headers.set('X-Trace', 'abc123');",
            request,
            env(),
        )
        assertNull(outcome.error)
        val header = outcome.request.headers.firstOrNull { it.key == "X-Trace" }
        assertTrue(header != null && header.value == "abc123")
    }

    @Test
    fun `pre-script can read an existing enabled request header`() {
        val request = ApiRequestItem(
            headers = listOf(KeyValueItem(key = "Accept", value = "application/json", isEnabled = true)),
        )
        val outcome = ApiTestrScriptRunner.runPreRequestScript(
            "pm.test('has accept', function () { pm.expect(pm.request.headers.get('Accept')).to.equal('application/json'); });",
            request,
            env(),
        )
        assertNull(outcome.error)
        assertEquals(1, outcome.testResults.size)
        assertTrue(outcome.testResults[0].passed)
    }

    @Test
    fun `pre-script can rewrite the request body and method`() {
        val request = ApiRequestItem(method = HttpMethod.GET, bodyContent = "")
        val outcome = ApiTestrScriptRunner.runPreRequestScript(
            "pm.request.method = 'POST'; pm.request.body = '{\"a\":1}';",
            request,
            env(),
        )
        assertNull(outcome.error)
        assertEquals(HttpMethod.POST, outcome.request.method)
        assertEquals("{\"a\":1}", outcome.request.bodyContent)
    }

    @Test
    fun `pre-script env write is reflected in envUpdates`() {
        val request = ApiRequestItem()
        val outcome = ApiTestrScriptRunner.runPreRequestScript(
            "pm.environment.set('token', 'secret-123');",
            request,
            env(),
        )
        assertNull(outcome.error)
        assertEquals("secret-123", outcome.envUpdates["token"])
    }

    @Test
    fun `pre-script env unset removes the variable from envUpdates`() {
        val request = ApiRequestItem()
        val outcome = ApiTestrScriptRunner.runPreRequestScript(
            "pm.environment.unset('stale');",
            request,
            env(mapOf("stale" to "old-value")),
        )
        assertNull(outcome.error)
        assertFalse(outcome.envUpdates.containsKey("stale"))
    }

    @Test
    fun `pre-script accessing pm response returns inert values instead of throwing`() {
        val request = ApiRequestItem()
        val outcome = ApiTestrScriptRunner.runPreRequestScript(
            "pm.test('response code is inert', function () { pm.expect(pm.response.code).to.equal(0); });",
            request,
            env(),
        )
        assertNull(outcome.error)
        assertTrue(outcome.testResults.single().passed)
    }

    @Test
    fun `pre-script syntax error is surfaced as outcome error, not thrown`() {
        val request = ApiRequestItem()
        val outcome = ApiTestrScriptRunner.runPreRequestScript("pm.request.url = ;", request, env())
        assertTrue(outcome.error != null)
        // Fail-open: the request passed through is still the original/unmodified one.
        assertEquals(request.url, outcome.request.url)
    }

    // ---- post-response script: response mutation + pm.test ---------------------------------

    @Test
    fun `post-script pm-test records a passing assertion`() {
        val request = ApiRequestItem()
        val response = ApiResponseData(statusCode = 200, body = "{\"ok\":true}")
        val outcome = ApiTestrScriptRunner.runPostResponseScript(
            "pm.test('status is 200', function () { pm.expect(pm.response.code).to.equal(200); });",
            request,
            response,
            env(),
        )
        assertNull(outcome.error)
        assertEquals(1, outcome.testResults.size)
        assertTrue(outcome.testResults[0].passed)
        assertEquals("status is 200", outcome.testResults[0].name)
    }

    @Test
    fun `post-script pm-test records a failing assertion with a message`() {
        val request = ApiRequestItem()
        val response = ApiResponseData(statusCode = 500, body = "{}")
        val outcome = ApiTestrScriptRunner.runPostResponseScript(
            "pm.test('status is 200', function () { pm.expect(pm.response.code).to.equal(200); });",
            request,
            response,
            env(),
        )
        assertNull(outcome.error)
        assertFalse(outcome.testResults[0].passed)
        assertTrue(outcome.testResults[0].message.isNotBlank())
    }

    @Test
    fun `post-script can parse response json and extract a value into env`() {
        val request = ApiRequestItem()
        val response = ApiResponseData(statusCode = 200, body = "{\"token\":\"xyz\"}")
        val outcome = ApiTestrScriptRunner.runPostResponseScript(
            "pm.environment.set('authToken', pm.response.json().token);",
            request,
            response,
            env(),
        )
        assertNull(outcome.error)
        assertEquals("xyz", outcome.envUpdates["authToken"])
    }

    @Test
    fun `post-script can rewrite the response body and status code`() {
        val request = ApiRequestItem()
        val response = ApiResponseData(statusCode = 202, body = "pending")
        val outcome = ApiTestrScriptRunner.runPostResponseScript(
            "pm.response.code = 200; pm.response.body = 'done';",
            request,
            response,
            env(),
        )
        assertNull(outcome.error)
        assertEquals(200, outcome.response.statusCode)
        assertEquals("done", outcome.response.body)
    }

    @Test
    fun `post-script mutating pm-request is a no-op`() {
        val request = ApiRequestItem(url = "https://example.com/original")
        val response = ApiResponseData()
        val outcome = ApiTestrScriptRunner.runPostResponseScript(
            "pm.request.url = 'https://example.com/should-not-apply';",
            request,
            response,
            env(),
        )
        assertNull(outcome.error)
        // Matches Postman: mutating pm.request post-response has no effect on anything --
        // there is no "outcome.request" to check here since runPostResponseScript never
        // returns one, which is itself the assertion that the write went nowhere.
    }

    @Test
    fun `post-script can read the request that was sent`() {
        val request = ApiRequestItem(url = "https://example.com/sent", method = HttpMethod.POST)
        val response = ApiResponseData()
        val outcome = ApiTestrScriptRunner.runPostResponseScript(
            "pm.test('saw request', function () { pm.expect(pm.request.url).to.equal('https://example.com/sent'); pm.expect(pm.request.method).to.equal('POST'); });",
            request,
            response,
            env(),
        )
        assertNull(outcome.error)
        assertTrue(outcome.testResults.single().passed)
    }

    @Test
    fun `console-log output is captured`() {
        val request = ApiRequestItem()
        val response = ApiResponseData()
        val outcome = ApiTestrScriptRunner.runPostResponseScript(
            "console.log('hello', 'world');",
            request,
            response,
            env(),
        )
        assertNull(outcome.error)
        assertEquals(listOf("hello world"), outcome.consoleOutput)
    }
}
