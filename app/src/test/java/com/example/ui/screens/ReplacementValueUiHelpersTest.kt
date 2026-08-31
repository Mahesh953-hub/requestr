package com.example.ui.screens

import com.example.data.local.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Build

/**
 * Followup to §3 (C10): [replacementValueSpecFor]/[replacementValueError]/[isAbsoluteUrlWithHost]/
 * [effectiveReplacementValueForSave] are the pure functions that stop the Add-Rule form from
 * saving a rule whose `replacementValue` would be a silent no-op on the engine side (see
 * ReplacementValueUiHelpers.kt's file KDoc). Robolectric is required here purely because
 * [replacementValueError] constructs real `org.json.JSONObject`s -- see
 * RuleTemplateUiHelpersTest.kt's file KDoc for why the plain Android unit-test stub jar isn't
 * enough for that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ReplacementValueUiHelpersTest {

    // ── replacementValueSpecFor ─────────────────────────────────────────────────────────

    @Test
    fun `replacementValueSpecFor is null for actions that never read replacementValue`() {
        assertNull(replacementValueSpecFor(RuleAction.BLOCK))
        assertNull(replacementValueSpecFor(RuleAction.LOG_ONLY))
        assertNull(replacementValueSpecFor(RuleAction.FORCE_MITM))
        assertNull(replacementValueSpecFor(RuleAction.BODY_ENCRYPT_AES))
        assertNull(replacementValueSpecFor(RuleAction.BODY_DECRYPT_AES))
    }

    @Test
    fun `replacementValueSpecFor is null for SCRIPT_REWRITE which has its own dedicated field`() {
        assertNull(replacementValueSpecFor(RuleAction.SCRIPT_REWRITE))
    }

    @Test
    fun `replacementValueSpecFor marks REDIRECT as a required URL`() {
        val spec = replacementValueSpecFor(RuleAction.REDIRECT)
        assertNotNull(spec)
        assertEquals(ReplacementValueShape.URL, spec!!.shape)
        assertTrue(spec.required)
    }

    @Test
    fun `replacementValueSpecFor marks the JSON-shaped actions as optional JSON objects with the right effective keys`() {
        val modifyRequest = replacementValueSpecFor(RuleAction.MODIFY_REQUEST)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, modifyRequest.shape)
        assertFalse(modifyRequest.required)
        assertEquals(setOf("addHeaders", "removeHeaders", "bodyReplace"), modifyRequest.effectiveJsonKeys)

        val modifyResponse = replacementValueSpecFor(RuleAction.MODIFY_RESPONSE)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, modifyResponse.shape)
        assertFalse(modifyResponse.required)
        assertEquals(setOf("statusCode", "addHeaders", "bodyReplace"), modifyResponse.effectiveJsonKeys)

        val mockResponse = replacementValueSpecFor(RuleAction.MOCK_RESPONSE)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, mockResponse.shape)
        assertFalse(mockResponse.required)
        assertEquals(setOf("statusCode", "statusMessage", "addHeaders", "bodyReplace"), mockResponse.effectiveJsonKeys)

        val chaos = replacementValueSpecFor(RuleAction.CHAOS)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, chaos.shape)
        assertFalse(chaos.required)
        assertEquals(setOf("failRate", "delayMs", "corruptBodyRate"), chaos.effectiveJsonKeys)
    }

    @Test
    fun `every JSON-shaped spec's effectiveJsonKeys never includes variables`() {
        listOf(RuleAction.MODIFY_REQUEST, RuleAction.MODIFY_RESPONSE, RuleAction.MOCK_RESPONSE, RuleAction.CHAOS)
            .forEach { action ->
                val spec = replacementValueSpecFor(action)!!
                assertFalse("variables" in spec.effectiveJsonKeys)
            }
    }

    // ── isAbsoluteUrlWithHost ────────────────────────────────────────────────────────────

    @Test
    fun `isAbsoluteUrlWithHost accepts an absolute URL with a scheme and host`() {
        assertTrue(isAbsoluteUrlWithHost("https://api.example.com/v2/users"))
        assertTrue(isAbsoluteUrlWithHost("http://localhost:8080/path?x=1"))
    }

    @Test
    fun `isAbsoluteUrlWithHost rejects blank, relative, scheme-less, and host-less values`() {
        assertFalse(isAbsoluteUrlWithHost(""))
        assertFalse(isAbsoluteUrlWithHost("/v2/users"))
        assertFalse(isAbsoluteUrlWithHost("example.com/v2/users"))
        assertFalse(isAbsoluteUrlWithHost("mailto:a@b.com"))
        assertFalse(isAbsoluteUrlWithHost("http:///path"))
    }

    @Test
    fun `isAbsoluteUrlWithHost rejects an unparseable URI rather than throwing`() {
        assertFalse(isAbsoluteUrlWithHost("http://[invalid"))
    }

    // ── replacementValueError -- actions with no spec ───────────────────────────────────

    @Test
    fun `replacementValueError is always null when the action has no spec, regardless of text`() {
        assertNull(replacementValueError(RuleAction.BLOCK, ""))
        assertNull(replacementValueError(RuleAction.BLOCK, "anything at all"))
        assertNull(replacementValueError(RuleAction.SCRIPT_REWRITE, "not json either"))
    }

    // ── replacementValueError -- REDIRECT (URL shape, required) ────────────────────────

    @Test
    fun `replacementValueError rejects a blank REDIRECT value as required`() {
        val error = replacementValueError(RuleAction.REDIRECT, "")
        assertNotNull(error)
        assertTrue(error!!.contains("required"))
    }

    @Test
    fun `replacementValueError rejects a relative or scheme-less REDIRECT value`() {
        assertNotNull(replacementValueError(RuleAction.REDIRECT, "/v2/users"))
        assertNotNull(replacementValueError(RuleAction.REDIRECT, "api.example.com/v2/users"))
    }

    @Test
    fun `replacementValueError accepts a valid absolute REDIRECT URL`() {
        assertNull(replacementValueError(RuleAction.REDIRECT, "https://api.example.com/v2/users"))
    }

    // ── replacementValueError -- JSON-shaped actions ────────────────────────────────────

    @Test
    fun `replacementValueError allows a blank value for every non-required JSON-shaped action`() {
        assertNull(replacementValueError(RuleAction.MODIFY_REQUEST, ""))
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, "  "))
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, ""))
        assertNull(replacementValueError(RuleAction.CHAOS, ""))
    }

    @Test
    fun `replacementValueError rejects non-JSON text for a JSON-shaped action`() {
        val error = replacementValueError(RuleAction.MODIFY_REQUEST, "literal body text")
        assertNotNull(error)
        assertTrue(error!!.contains("JSON object"))
    }

    @Test
    fun `replacementValueError rejects a JSON array where an object is expected`() {
        assertNotNull(replacementValueError(RuleAction.MODIFY_REQUEST, "[1,2,3]"))
    }

    @Test
    fun `replacementValueError rejects a config with only variables and no effective key -- MODIFY_REQUEST`() {
        val error = replacementValueError(RuleAction.MODIFY_REQUEST, """{"variables":{"traceId":"abc"}}""")
        assertNotNull(error)
        assertTrue(error!!.contains("no effect"))
    }

    @Test
    fun `replacementValueError accepts MODIFY_REQUEST configs with any single effective key`() {
        assertNull(replacementValueError(RuleAction.MODIFY_REQUEST, """{"addHeaders":{"X-Trace":"1"}}"""))
        assertNull(replacementValueError(RuleAction.MODIFY_REQUEST, """{"removeHeaders":["Authorization"]}"""))
        assertNull(replacementValueError(RuleAction.MODIFY_REQUEST, """{"bodyReplace":"{}"}"""))
    }

    @Test
    fun `replacementValueError accepts MODIFY_RESPONSE configs with any single effective key`() {
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, """{"statusCode":204}"""))
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, """{"addHeaders":{"X-Mock":"true"}}"""))
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, """{"bodyReplace":"{}"}"""))
    }

    @Test
    fun `replacementValueError rejects a MODIFY_RESPONSE config with an unrelated key only`() {
        val error = replacementValueError(RuleAction.MODIFY_RESPONSE, """{"foo":"bar"}""")
        assertNotNull(error)
        assertTrue(error!!.contains("no effect"))
    }

    @Test
    fun `replacementValueError accepts MOCK_RESPONSE configs with statusMessage alone`() {
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, """{"statusMessage":"Teapot"}"""))
    }

    @Test
    fun `replacementValueError rejects a MOCK_RESPONSE config with an unrelated key only`() {
        assertNotNull(replacementValueError(RuleAction.MOCK_RESPONSE, """{"unused":true}"""))
    }

    @Test
    fun `replacementValueError accepts CHAOS configs with any single effective key`() {
        assertNull(replacementValueError(RuleAction.CHAOS, """{"failRate":0.1}"""))
        assertNull(replacementValueError(RuleAction.CHAOS, """{"delayMs":[200,800]}"""))
        assertNull(replacementValueError(RuleAction.CHAOS, """{"corruptBodyRate":0.05}"""))
    }

    @Test
    fun `replacementValueError rejects a CHAOS config with only the failure-shaping keys`() {
        // failStatusCode/failStatusMessage/failBody only shape a Fail outcome -- they can never
        // by themselves cause one, so a config with only these is still a no-op.
        val error = replacementValueError(RuleAction.CHAOS, """{"failStatusCode":503,"failBody":"oops"}""")
        assertNotNull(error)
        assertTrue(error!!.contains("no effect"))
    }

    @Test
    fun `replacementValueError rejects malformed JSON for CHAOS`() {
        assertNotNull(replacementValueError(RuleAction.CHAOS, "{not valid json"))
    }

    // ── effectiveReplacementValueForSave ─────────────────────────────────────────────────

    @Test
    fun `effectiveReplacementValueForSave returns raw text unchanged when there are no variables`() {
        assertEquals(
            """{"addHeaders":{"X":"1"}}""",
            effectiveReplacementValueForSave(RuleAction.MODIFY_REQUEST, """{"addHeaders":{"X":"1"}}""", emptyMap()),
        )
    }

    @Test
    fun `effectiveReplacementValueForSave returns raw text unchanged for actions that do not template`() {
        val variables = mapOf("traceId" to "abc")
        assertEquals("https://api.example.com", effectiveReplacementValueForSave(RuleAction.REDIRECT, "https://api.example.com", variables))
        assertEquals("""{"failRate":0.1}""", effectiveReplacementValueForSave(RuleAction.CHAOS, """{"failRate":0.1}""", variables))
    }

    @Test
    fun `effectiveReplacementValueForSave leaves SCRIPT_REWRITE's raw text alone even with variables`() {
        val variables = mapOf("traceId" to "abc")
        assertEquals("pm.request.headers.set('X','1');", effectiveReplacementValueForSave(RuleAction.SCRIPT_REWRITE, "pm.request.headers.set('X','1');", variables))
    }

    @Test
    fun `effectiveReplacementValueForSave merging a blank base with variables still fails validation`() {
        val merged = effectiveReplacementValueForSave(RuleAction.MODIFY_REQUEST, "", mapOf("traceId" to "abc"))
        val error = replacementValueError(RuleAction.MODIFY_REQUEST, merged)
        assertNotNull("a variables-only merged config must still be rejected as a no-op", error)
    }

    @Test
    fun `effectiveReplacementValueForSave merging variables into a config that already has an effective key passes validation`() {
        val merged = effectiveReplacementValueForSave(
            RuleAction.MODIFY_REQUEST,
            """{"addHeaders":{"X-Trace":"{{traceId}}"}}""",
            mapOf("traceId" to "abc"),
        )
        assertNull(replacementValueError(RuleAction.MODIFY_REQUEST, merged))
        // and the merge itself must be a real round trip, not a lossy rebuild
        val json = org.json.JSONObject(merged)
        assertTrue(json.has("addHeaders"))
        assertTrue(json.has("variables"))
        assertEquals("abc", json.getJSONObject("variables").getString("traceId"))
    }
}
