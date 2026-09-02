package com.example.interception

import android.os.Build
import com.example.interception.http.ParsedHttpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Master plan §1.11 — [RuleEnforcer.applyScriptRewrite]: the live `SCRIPT_REWRITE` rule action.
 * Covers request-side header/body/url rewrite, response-side status/header/body rewrite, that
 * [ParsedHttpMessage.bodyReplaced] is set correctly whenever the body is overwritten (mirrors
 * [RuleEnforcerBodyReplacedFlagTest]'s coverage of the two fixed-schema actions), and
 * malformed-config fail-open behavior.
 *
 * Uses Robolectric for the same reason [RuleEnforcerBodyReplacedFlagTest] does: [RuleEnforcer]
 * (here, via [JsScriptEngine]/[PmPrelude]'s JSON usage in the config parse) goes through
 * `org.json.JSONObject`, which is an unmocked stub under a plain JVM unit test in this project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerScriptRewriteTest {

    private fun request(url: String = "https://example.com/original", body: String = "original") = ParsedHttpMessage(
        isRequest = true,
        method = com.example.data.models.HttpMethod.GET,
        url = url,
        body = body,
        headers = mapOf("accept" to "application/json"),
        rawBodyBytes = body.toByteArray(Charsets.UTF_8),
    )

    private fun response(statusCode: Int = 200, body: String = "original") = ParsedHttpMessage(
        isRequest = false,
        method = null,
        statusCode = statusCode,
        body = body,
        headers = mapOf("content-type" to "application/json"),
        rawBodyBytes = body.toByteArray(Charsets.UTF_8),
    )

    private fun config(script: String, variables: Map<String, String> = emptyMap()): String {
        val varsJson = org.json.JSONObject()
        variables.forEach { (key, value) -> varsJson.put(key, value) }
        return org.json.JSONObject()
            .put("script", script)
            .put("variables", varsJson)
            .toString()
    }

    // ---- request-side rewrite ---------------------------------------------------------------

    @Test
    fun `script rewrite can set the request url`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, config("pm.request.url = 'https://example.com/rewritten';"))
        assertEquals("https://example.com/rewritten", req.url)
    }

    @Test
    fun `script rewrite can add a request header`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, config("pm.request.headers.set('X-Injected', 'yes');"))
        assertEquals("yes", req.headers.entries.firstOrNull { it.key.equals("X-Injected", ignoreCase = true) }?.value)
    }

    @Test
    fun `script rewrite can overwrite the request body and sets bodyReplaced`() {
        val req = request(body = "original")
        RuleEnforcer.applyScriptRewrite(req, config("pm.request.body = 'rewritten body';"))
        assertEquals("rewritten body", req.body)
        assertTrue(req.bodyReplaced)
    }

    @Test
    fun `script rewrite header-only edit leaves bodyReplaced false`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, config("pm.request.headers.set('X-Injected', 'yes');"))
        assertFalse(req.bodyReplaced)
    }

    @Test
    fun `script rewrite does not touch a response when dispatched against a request`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, config("pm.response.body = 'should not apply';"))
        assertEquals("original", req.body)
        assertFalse(req.bodyReplaced)
    }

    @Test
    fun `script rewrite substitutes a config variable via templateString`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(
            req,
            config("pm.request.headers.set('X-Rule-Var', pm.variables.replaceIn('{{ruleVar}}'));", mapOf("ruleVar" to "from-config")),
        )
        assertEquals("from-config", req.headers.entries.firstOrNull { it.key.equals("X-Rule-Var", ignoreCase = true) }?.value)
    }

    // ---- response-side rewrite ---------------------------------------------------------------

    @Test
    fun `script rewrite can set the response status code`() {
        val resp = response(statusCode = 200)
        RuleEnforcer.applyScriptRewrite(resp, config("pm.response.code = 503;"))
        assertEquals(503, resp.statusCode)
    }

    @Test
    fun `script rewrite can add a response header`() {
        val resp = response()
        RuleEnforcer.applyScriptRewrite(resp, config("pm.response.headers.set('X-Mock', 'true');"))
        assertEquals("true", resp.headers.entries.firstOrNull { it.key.equals("X-Mock", ignoreCase = true) }?.value)
    }

    @Test
    fun `script rewrite can overwrite the response body and sets bodyReplaced`() {
        val resp = response(body = "original")
        RuleEnforcer.applyScriptRewrite(resp, config("pm.response.body = '{\\\"mocked\\\":true}';"))
        assertEquals("{\"mocked\":true}", resp.body)
        assertTrue(resp.bodyReplaced)
    }

    @Test
    fun `script rewrite response header-only edit leaves bodyReplaced false`() {
        val resp = response()
        RuleEnforcer.applyScriptRewrite(resp, config("pm.response.headers.set('X-Mock', 'true');"))
        assertFalse(resp.bodyReplaced)
    }

    @Test
    fun `script rewrite can read response json to drive a header rewrite`() {
        val resp = response(body = "{\"status\":\"ready\"}")
        RuleEnforcer.applyScriptRewrite(resp, config("pm.response.headers.set('X-Status', pm.response.json().status);"))
        assertEquals("ready", resp.headers.entries.firstOrNull { it.key.equals("X-Status", ignoreCase = true) }?.value)
    }

    // ---- fail-open behavior -------------------------------------------------------------------

    @Test
    fun `null config is a no-op`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, null)
        assertEquals("original", req.body)
        assertFalse(req.bodyReplaced)
    }

    @Test
    fun `blank config is a no-op`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, "   ")
        assertEquals("original", req.body)
    }

    @Test
    fun `malformed json config is a no-op, not a thrown exception`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, "{not valid json")
        assertEquals("original", req.body)
        assertFalse(req.bodyReplaced)
    }

    @Test
    fun `config with a blank script is a no-op`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, """{"script":""}""")
        assertEquals("original", req.body)
    }

    @Test
    fun `a throwing script is a no-op on the message -- fail-open, not a crash`() {
        val req = request()
        RuleEnforcer.applyScriptRewrite(req, config("throw new Error('boom');"))
        assertEquals("original", req.body)
        assertFalse(req.bodyReplaced)
    }

    @Test
    fun `a throwing script returns its error text so the engine can surface it -- a clean run returns null`() {
        val error = RuleEnforcer.applyScriptRewrite(request(), config("throw new Error('boom');"))
            ?: throw AssertionError("a throwing script must report its error")
        assertTrue(error, error.contains("boom"))
        assertEquals(null, RuleEnforcer.applyScriptRewrite(request(), config("pm.request.body = 'x';")))
        assertEquals(null, RuleEnforcer.applyScriptRewrite(request(), null))
        assertEquals(null, RuleEnforcer.applyScriptRewrite(request(), """{"script":""}"""))
    }

    @Test
    fun `a malformed config is reported, not swallowed`() {
        val error = RuleEnforcer.applyScriptRewrite(request(), "{not valid json")
            ?: throw AssertionError("malformed config must be reported")
        assertTrue(error, error.startsWith("invalid SCRIPT_REWRITE config"))
    }

    @Test
    fun `scriptErrorHeaderValue folds newlines and appends the rule name`() {
        assertEquals(
            "ReferenceError: x is not defined at line 2; rule=\"js\"",
            RuleEnforcer.scriptErrorHeaderValue("ReferenceError: x is not defined\r\nat line 2\n", "js"),
        )
    }
}
