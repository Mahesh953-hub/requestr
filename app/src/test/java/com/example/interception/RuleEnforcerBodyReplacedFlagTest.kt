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
 * MUST-FIX 1: [com.example.interception.MitmProxyEngine.serializeRequest]/`serializeResponse`
 * decide whether to write [ParsedHttpMessage.body] or [ParsedHttpMessage.rawBodyBytes] purely
 * by checking [ParsedHttpMessage.bodyReplaced]. That flag must be set at exactly the two sites
 * in `RuleEnforcer.kt` where a rule's `bodyReplace` config key actually overwrites the body
 * (`applyRequestModification`/`applyResponseModification`), and must stay false everywhere
 * else (no rule matched, or the rule only touched headers/status).
 *
 * Uses Robolectric (unlike the rest of this package) because [RuleEnforcer] goes through
 * `org.json.JSONObject`, which is an unmocked stub under a plain JVM unit test in this project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerBodyReplacedFlagTest {

    private fun request() = ParsedHttpMessage(
        isRequest = true,
        method = com.example.data.models.HttpMethod.POST,
        body = "original",
        rawBodyBytes = "original".toByteArray(Charsets.UTF_8),
    )

    private fun response() = ParsedHttpMessage(
        isRequest = false,
        method = null,
        body = "original",
        rawBodyBytes = "original".toByteArray(Charsets.UTF_8),
    )

    @Test
    fun `applyRequestModification sets bodyReplaced when config has bodyReplace`() {
        val req = request()
        RuleEnforcer.applyRequestModification(req, """{"bodyReplace":"new body"}""")

        assertTrue(req.bodyReplaced)
        assertEquals("new body", req.body)
    }

    @Test
    fun `applyRequestModification leaves bodyReplaced false for a header-only edit`() {
        val req = request()
        RuleEnforcer.applyRequestModification(req, """{"addHeaders":{"X-Custom":"value"}}""")

        assertFalse(req.bodyReplaced)
        assertEquals("original", req.body)
    }

    @Test
    fun `applyResponseModification sets bodyReplaced when config has bodyReplace`() {
        val resp = response()
        RuleEnforcer.applyResponseModification(resp, """{"bodyReplace":"new body"}""")

        assertTrue(resp.bodyReplaced)
        assertEquals("new body", resp.body)
    }

    @Test
    fun `applyResponseModification leaves bodyReplaced false for a header-only edit`() {
        val resp = response()
        RuleEnforcer.applyResponseModification(resp, """{"addHeaders":{"X-Mock":"true"}}""")

        assertFalse(resp.bodyReplaced)
        assertEquals("original", resp.body)
    }

    @Test
    fun `applyResponseModification leaves bodyReplaced false for a statusCode-only edit`() {
        val resp = response()
        RuleEnforcer.applyResponseModification(resp, """{"statusCode":404}""")

        assertFalse(resp.bodyReplaced)
        assertEquals(404, resp.statusCode)
    }
}
