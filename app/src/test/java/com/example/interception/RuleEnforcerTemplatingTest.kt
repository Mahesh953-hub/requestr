package com.example.interception

import android.os.Build
import com.example.interception.http.ParsedHttpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Master plan §3 (C10) — integration coverage confirming [RuleEnforcer.applyRequestModification]/
 * [RuleEnforcer.applyResponseModification]/[RuleEnforcer.buildMockResponse] actually route their
 * `addHeaders`/`bodyReplace` config values through [RuleTemplateExpander] (unit-level coverage of
 * the expansion logic itself lives in `RuleTemplateExpanderTest`, which needs no Robolectric).
 *
 * Uses Robolectric for the same reason [RuleEnforcerBodyReplacedFlagTest] does: `RuleEnforcer`
 * goes through `org.json.JSONObject`, unmocked under a plain JVM unit test in this project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerTemplatingTest {

    private fun request(url: String = "https://api.example.com/v1/users?id=42") = ParsedHttpMessage(
        isRequest = true,
        method = com.example.data.models.HttpMethod.GET,
        url = url,
        path = "/v1/users",
        host = "api.example.com",
        body = "original",
    )

    private fun response() = ParsedHttpMessage(
        isRequest = false,
        method = null,
        body = "original",
    )

    @Test
    fun `applyRequestModification expands a user variable inside an addHeaders value`() {
        val req = request()
        RuleEnforcer.applyRequestModification(
            req,
            """{"addHeaders":{"X-Api-Key":"{{apiKey}}"},"variables":{"apiKey":"secret-123"}}""",
        )
        assertEquals("secret-123", req.headers["X-Api-Key"])
    }

    @Test
    fun `applyRequestModification expands request-context built-ins from the request itself`() {
        val req = request()
        RuleEnforcer.applyRequestModification(
            req,
            """{"addHeaders":{"X-Original-Host":"{{request.host}}","X-Original-Path":"{{request.path}}"}}""",
        )
        assertEquals("api.example.com", req.headers["X-Original-Host"])
        assertEquals("/v1/users", req.headers["X-Original-Path"])
    }

    @Test
    fun `applyRequestModification expands placeholders inside a literal bodyReplace value`() {
        val req = request()
        RuleEnforcer.applyRequestModification(
            req,
            """{"bodyReplace":"user-id={{request.query.id}}","variables":{}}""",
        )
        assertEquals("user-id=42", req.body)
    }

    @Test
    fun `applyRequestModification leaves an unrecognized placeholder in bodyReplace intact`() {
        val req = request()
        RuleEnforcer.applyRequestModification(req, """{"bodyReplace":"{{totallyUnknownVar}}"}""")
        assertEquals("{{totallyUnknownVar}}", req.body)
    }

    @Test
    fun `applyResponseModification expands a user variable inside an addHeaders value`() {
        val resp = response()
        RuleEnforcer.applyResponseModification(
            resp,
            """{"addHeaders":{"X-Mock-Env":"{{env}}"},"variables":{"env":"staging"}}""",
        )
        assertEquals("staging", resp.headers["X-Mock-Env"])
    }

    @Test
    fun `applyResponseModification expands placeholders inside bodyReplace`() {
        val resp = response()
        RuleEnforcer.applyResponseModification(
            resp,
            """{"bodyReplace":"{\"status\":\"{{status}}\"}","variables":{"status":"ok"}}""",
        )
        assertEquals("""{"status":"ok"}""", resp.body)
    }

    @Test
    fun `buildMockResponse expands a user variable inside bodyReplace and addHeaders`() {
        val mock = RuleEnforcer.buildMockResponse(
            """{"bodyReplace":"hello {{who}}","addHeaders":{"X-Who":"{{who}}"},"variables":{"who":"world"}}""",
            requestUrl = "https://api.example.com/v1/greet",
        )
        assertEquals("hello world", mock.body)
        assertEquals("world", mock.headers["x-who"])
    }

    @Test
    fun `buildMockResponse expands request-context built-ins from the bare request URL`() {
        val mock = RuleEnforcer.buildMockResponse(
            """{"bodyReplace":"echo {{request.query.name}} on {{request.host}}"}""",
            requestUrl = "https://api.example.com/v1/echo?name=alice",
        )
        assertEquals("echo alice on api.example.com", mock.body)
    }

    @Test
    fun `buildMockResponse leaves request-method untouched since no method is available at this call site`() {
        val mock = RuleEnforcer.buildMockResponse(
            """{"bodyReplace":"method={{request.method}}"}""",
            requestUrl = "https://api.example.com/v1/x",
        )
        assertEquals("method={{request.method}}", mock.body)
    }

    @Test
    fun `an escaped literal double-brace in bodyReplace survives untouched with the backslash stripped`() {
        val req = request()
        RuleEnforcer.applyRequestModification(req, """{"bodyReplace":"literal \\{{not-a-var}} here"}""")
        assertEquals("literal {{not-a-var}} here", req.body)
    }

    @Test
    fun `a rule with no variables key at all still resolves request-context built-ins`() {
        val req = request(url = "https://api.example.com/v1/orders")
        RuleEnforcer.applyRequestModification(req, """{"addHeaders":{"X-Host":"{{request.host}}"}}""")
        assertEquals("api.example.com", req.headers["X-Host"])
        assertNull(req.headers["undeclared"])
    }
}
