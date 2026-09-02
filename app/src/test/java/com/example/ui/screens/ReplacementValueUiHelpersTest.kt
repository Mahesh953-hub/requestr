package com.example.ui.screens

import android.os.Build
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M4: the pure validity rules in ReplacementValueUiHelpers.kt that make it impossible for the
 * Add-Rule form to save a rule whose engine path would silently do nothing. Robolectric is
 * required purely because these helpers construct real `org.json.JSONObject`s -- the plain
 * Android unit-test stub jar no-ops instead of failing (see RuleTemplateUiHelpersTest's KDoc).
 *
 * Companion file [RuleConfigValidityTest] holds the red/green cycle for the M4 finding itself
 * (each rule type's blank case); this file covers the spec, shapes, boundaries, the draft-level
 * predicate, type-switch re-evaluation, the save blocker, and saved-rule flagging.
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
    fun `replacementValueSpecFor requires MODIFY_REQUEST MODIFY_RESPONSE and CHAOS but not MOCK_RESPONSE`() {
        val modifyRequest = replacementValueSpecFor(RuleAction.MODIFY_REQUEST)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, modifyRequest.shape)
        assertTrue(modifyRequest.required)
        assertEquals(setOf("addHeaders", "removeHeaders", "bodyReplace"), modifyRequest.effectiveJsonKeys)

        val modifyResponse = replacementValueSpecFor(RuleAction.MODIFY_RESPONSE)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, modifyResponse.shape)
        assertTrue(modifyResponse.required)
        assertEquals(setOf("statusCode", "addHeaders", "bodyReplace"), modifyResponse.effectiveJsonKeys)

        val mockResponse = replacementValueSpecFor(RuleAction.MOCK_RESPONSE)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, mockResponse.shape)
        assertFalse("blank MOCK_RESPONSE is the documented empty 200 OK", mockResponse.required)
        assertEquals(setOf("statusCode", "statusMessage", "addHeaders", "bodyReplace"), mockResponse.effectiveJsonKeys)

        val chaos = replacementValueSpecFor(RuleAction.CHAOS)!!
        assertEquals(ReplacementValueShape.JSON_OBJECT, chaos.shape)
        assertTrue(chaos.required)
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

    // ── replacementValueError -- actions with no config ─────────────────────────────────

    @Test
    fun `replacementValueError is always null for BLOCK LOG_ONLY FORCE_MITM regardless of text`() {
        listOf(RuleAction.BLOCK, RuleAction.LOG_ONLY, RuleAction.FORCE_MITM).forEach { action ->
            assertNull(replacementValueError(action, null))
            assertNull(replacementValueError(action, ""))
            assertNull(replacementValueError(action, "anything at all"))
        }
    }

    @Test
    fun `replacementValueError is null for the AES crypto actions which are configured via cryptoKeyAlias instead`() {
        assertNull(replacementValueError(RuleAction.BODY_ENCRYPT_AES, null))
        assertNull(replacementValueError(RuleAction.BODY_DECRYPT_AES, "ignored"))
    }

    // ── replacementValueError -- REDIRECT ───────────────────────────────────────────────

    @Test
    fun `replacementValueError rejects a blank or null REDIRECT value as required`() {
        val error = replacementValueError(RuleAction.REDIRECT, "")
        assertNotNull(error)
        assertTrue(error!!.contains("required"))
        assertNotNull(replacementValueError(RuleAction.REDIRECT, null))
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

    // ── replacementValueError -- MODIFY_REQUEST / MODIFY_RESPONSE ───────────────────────

    @Test
    fun `replacementValueError names the field and action when a required JSON config is blank`() {
        val error = replacementValueError(RuleAction.MODIFY_REQUEST, "")!!
        assertTrue(error, error.contains("Request modification (JSON)"))
        assertTrue(error, error.contains("Modify Request"))
        val responseError = replacementValueError(RuleAction.MODIFY_RESPONSE, null)!!
        assertTrue(responseError, responseError.contains("Response modification (JSON)"))
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
    fun `replacementValueError rejects an empty object and a variables-only object for MODIFY_REQUEST`() {
        assertTrue(replacementValueError(RuleAction.MODIFY_REQUEST, "{}")!!.contains("no effect"))
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
    fun `replacementValueError treats an empty bodyReplace as effective because the engine clears the body`() {
        // MockFixtureLoader.resolve("") -> Resolution.Text("") -> applyTo replaces the body with "".
        assertNull(replacementValueError(RuleAction.MODIFY_REQUEST, """{"bodyReplace":""}"""))
    }

    @Test
    fun `replacementValueError rejects present-but-empty addHeaders and removeHeaders as no-ops`() {
        // applyRequestModification iterates addHeaders' keys / removeHeaders' entries -- zero
        // iterations, request untouched.
        val emptyHeaders = replacementValueError(RuleAction.MODIFY_REQUEST, """{"addHeaders":{}}""")!!
        assertTrue(emptyHeaders, emptyHeaders.contains("addHeaders") && emptyHeaders.contains("empty"))
        val bothEmpty = replacementValueError(RuleAction.MODIFY_REQUEST, """{"addHeaders":{},"removeHeaders":[]}""")!!
        assertTrue(bothEmpty, bothEmpty.contains("addHeaders") && bothEmpty.contains("removeHeaders"))
    }

    @Test
    fun `replacementValueError accepts an empty addHeaders when another key does the work`() {
        assertNull(replacementValueError(RuleAction.MODIFY_REQUEST, """{"addHeaders":{},"bodyReplace":"x"}"""))
    }

    @Test
    fun `replacementValueError rejects an addHeaders that is not an object because getJSONObject throws in the engine`() {
        val error = replacementValueError(RuleAction.MODIFY_REQUEST, """{"addHeaders":"X-Trace: 1"}""")!!
        assertTrue(error, error.contains("addHeaders") && error.contains("JSON object"))
    }

    @Test
    fun `replacementValueError rejects a removeHeaders that is not an array`() {
        val error = replacementValueError(RuleAction.MODIFY_REQUEST, """{"removeHeaders":"Authorization"}""")!!
        assertTrue(error, error.contains("removeHeaders") && error.contains("array"))
    }

    @Test
    fun `replacementValueError rejects a wrong-typed key even when another key is effective`() {
        // The engine's runCatching aborts the whole modification on the first throw, so a
        // broken key breaks the rule regardless of the good keys next to it.
        assertNotNull(replacementValueError(RuleAction.MODIFY_REQUEST, """{"bodyReplace":"x","addHeaders":42}"""))
    }

    @Test
    fun `replacementValueError accepts MODIFY_RESPONSE configs with any single effective key`() {
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, """{"statusCode":204}"""))
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, """{"addHeaders":{"X-Mock":"true"}}"""))
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, """{"bodyReplace":"{}"}"""))
    }

    @Test
    fun `replacementValueError accepts a numeric-string statusCode because getInt coerces it`() {
        assertNull(replacementValueError(RuleAction.MODIFY_RESPONSE, """{"statusCode":"204"}"""))
    }

    @Test
    fun `replacementValueError rejects an out-of-range statusCode for MODIFY_RESPONSE and MOCK_RESPONSE`() {
        for (action in listOf(RuleAction.MODIFY_RESPONSE, RuleAction.MOCK_RESPONSE)) {
            for (code in listOf(0, 99, 600, 1000, -1)) {
                val error = replacementValueError(action, """{"statusCode":$code}""")
                    ?: throw AssertionError("$action must reject statusCode $code")
                assertTrue(error, error.contains("statusCode $code") && error.contains("100 to 599"))
            }
        }
    }

    @Test
    fun `replacementValueError accepts the statusCode range boundaries`() {
        for (action in listOf(RuleAction.MODIFY_RESPONSE, RuleAction.MOCK_RESPONSE)) {
            for (code in listOf(100, 200, 418, 503, 599)) {
                assertNull("$action must accept statusCode $code", replacementValueError(action, """{"statusCode":$code}"""))
            }
        }
    }

    @Test
    fun `an out-of-range statusCode blocks the Add-Rule save even with other effective keys present`() {
        val draft = RuleConfigDraft(RuleAction.MODIFY_RESPONSE, replacementValueText = """{"statusCode":999,"bodyReplace":"x"}""")
        val blocker = addRuleSaveBlocker("r", ".*", draft) ?: throw AssertionError("must block")
        assertTrue(blocker, blocker.contains("statusCode 999"))
    }

    @Test
    fun `replacementValueError rejects a non-integer statusCode`() {
        val error = replacementValueError(RuleAction.MODIFY_RESPONSE, """{"statusCode":"teapot"}""")!!
        assertTrue(error, error.contains("statusCode") && error.contains("integer"))
    }

    @Test
    fun `replacementValueError rejects a MODIFY_RESPONSE config with an unrelated key only`() {
        val error = replacementValueError(RuleAction.MODIFY_RESPONSE, """{"foo":"bar"}""")
        assertNotNull(error)
        assertTrue(error!!.contains("no effect"))
    }

    // ── replacementValueError -- MOCK_RESPONSE ──────────────────────────────────────────

    @Test
    fun `replacementValueError keeps blank null and whitespace MOCK_RESPONSE valid`() {
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, null))
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, ""))
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, "   "))
    }

    @Test
    fun `replacementValueError treats an empty object and a variables-only object as the default 200 OK mock`() {
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, "{}"))
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, """{"variables":{"id":"1"}}"""))
    }

    @Test
    fun `replacementValueError accepts MOCK_RESPONSE configs with statusMessage alone`() {
        assertNull(replacementValueError(RuleAction.MOCK_RESPONSE, """{"statusMessage":"Teapot"}"""))
    }

    @Test
    fun `replacementValueError rejects a MOCK_RESPONSE config with keys the mock never reads`() {
        val error = replacementValueError(RuleAction.MOCK_RESPONSE, """{"status":418}""")!!
        assertTrue(error, error.contains("status") && error.contains("200 OK"))
    }

    @Test
    fun `replacementValueError still rejects malformed MOCK_RESPONSE JSON`() {
        assertNotNull(replacementValueError(RuleAction.MOCK_RESPONSE, "{not json"))
    }

    // ── replacementValueError -- CHAOS ──────────────────────────────────────────────────

    @Test
    fun `replacementValueError accepts CHAOS configs with any single trigger above zero`() {
        assertNull(replacementValueError(RuleAction.CHAOS, """{"failRate":0.1}"""))
        assertNull(replacementValueError(RuleAction.CHAOS, """{"delayMs":[200,800]}"""))
        assertNull(replacementValueError(RuleAction.CHAOS, """{"corruptBodyRate":0.05}"""))
    }

    @Test
    fun `replacementValueError accepts a single-element delayMs above zero and a rate above one because the engine clamps them`() {
        assertNull(replacementValueError(RuleAction.CHAOS, """{"delayMs":[500]}"""))
        assertNull(replacementValueError(RuleAction.CHAOS, """{"failRate":5}"""))
    }

    @Test
    fun `replacementValueError rejects CHAOS configs whose triggers are all zero`() {
        assertTrue(replacementValueError(RuleAction.CHAOS, """{"failRate":0}""")!!.contains("no effect"))
        assertTrue(replacementValueError(RuleAction.CHAOS, """{"delayMs":[0,0]}""")!!.contains("no effect"))
        assertTrue(replacementValueError(RuleAction.CHAOS, """{"failRate":-1,"corruptBodyRate":0}""")!!.contains("no effect"))
        assertTrue(replacementValueError(RuleAction.CHAOS, "{}")!!.contains("no effect"))
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
    fun `replacementValueError rejects a non-numeric CHAOS rate rather than letting optDouble zero it`() {
        val error = replacementValueError(RuleAction.CHAOS, """{"failRate":"often"}""")!!
        assertTrue(error, error.contains("failRate") && error.contains("number"))
    }

    @Test
    fun `replacementValueError rejects a delayMs that is not an array`() {
        val error = replacementValueError(RuleAction.CHAOS, """{"delayMs":500}""")!!
        assertTrue(error, error.contains("delayMs") && error.contains("array"))
    }

    @Test
    fun `replacementValueError rejects malformed JSON for CHAOS`() {
        assertNotNull(replacementValueError(RuleAction.CHAOS, "{not valid json"))
    }

    // ── replacementValueError -- SCRIPT_REWRITE (persisted shape) ───────────────────────

    @Test
    fun `replacementValueError rejects a null blank non-JSON or script-less SCRIPT_REWRITE config`() {
        assertNotNull(replacementValueError(RuleAction.SCRIPT_REWRITE, null))
        assertNotNull(replacementValueError(RuleAction.SCRIPT_REWRITE, ""))
        assertNotNull(replacementValueError(RuleAction.SCRIPT_REWRITE, "not json either"))
        assertNotNull(replacementValueError(RuleAction.SCRIPT_REWRITE, """{"variables":{"a":"b"}}"""))
        assertNotNull(replacementValueError(RuleAction.SCRIPT_REWRITE, """{"script":"   "}"""))
    }

    @Test
    fun `replacementValueError accepts a SCRIPT_REWRITE config with a non-blank script`() {
        assertNull(replacementValueError(RuleAction.SCRIPT_REWRITE, """{"script":"pm.request.headers.set('X','1');"}"""))
    }

    // ── replacementValueToSave ──────────────────────────────────────────────────────────

    @Test
    fun `replacementValueToSave returns null for a blank field and the trimmed text otherwise`() {
        assertNull(replacementValueToSave(RuleConfigDraft(RuleAction.MODIFY_REQUEST, replacementValueText = "  ")))
        assertEquals(
            """{"addHeaders":{"X":"1"}}""",
            replacementValueToSave(RuleConfigDraft(RuleAction.MODIFY_REQUEST, replacementValueText = """ {"addHeaders":{"X":"1"}} """)),
        )
    }

    @Test
    fun `replacementValueToSave leaves non-templating actions alone even with variables`() {
        val variables = mapOf("traceId" to "abc")
        assertEquals(
            "https://api.example.com",
            replacementValueToSave(RuleConfigDraft(RuleAction.REDIRECT, replacementValueText = "https://api.example.com", variables = variables)),
        )
        assertEquals(
            """{"failRate":0.1}""",
            replacementValueToSave(RuleConfigDraft(RuleAction.CHAOS, replacementValueText = """{"failRate":0.1}""", variables = variables)),
        )
    }

    @Test
    fun `replacementValueToSave merges variables into a templating action's JSON without losing keys`() {
        val saved = replacementValueToSave(
            RuleConfigDraft(
                RuleAction.MODIFY_REQUEST,
                replacementValueText = """{"addHeaders":{"X-Trace":"{{traceId}}"}}""",
                variables = mapOf("traceId" to "abc"),
            ),
        )!!
        val json = JSONObject(saved)
        assertTrue(json.has("addHeaders"))
        assertEquals("abc", json.getJSONObject("variables").getString("traceId"))
    }

    @Test
    fun `replacementValueToSave builds SCRIPT_REWRITE's config from the script field and ignores the replacement field`() {
        val saved = replacementValueToSave(
            RuleConfigDraft(
                RuleAction.SCRIPT_REWRITE,
                replacementValueText = "this field is not the script",
                scriptText = "pm.request.headers.set('X','1');",
                variables = mapOf("traceId" to "abc"),
            ),
        )!!
        val json = JSONObject(saved)
        assertEquals("pm.request.headers.set('X','1');", json.getString("script"))
        assertEquals("abc", json.getJSONObject("variables").getString("traceId"))
        assertNull(replacementValueToSave(RuleConfigDraft(RuleAction.SCRIPT_REWRITE, scriptText = "")))
    }

    // ── ruleConfigError -- the form-level predicate ─────────────────────────────────────

    @Test
    fun `ruleConfigError is null for BLOCK LOG_ONLY FORCE_MITM with everything blank`() {
        listOf(RuleAction.BLOCK, RuleAction.LOG_ONLY, RuleAction.FORCE_MITM).forEach { action ->
            assertNull(action.name, ruleConfigError(RuleConfigDraft(action)))
        }
    }

    @Test
    fun `ruleConfigError rejects a blank draft for every action whose engine path needs a config`() {
        listOf(RuleAction.REDIRECT, RuleAction.MODIFY_REQUEST, RuleAction.MODIFY_RESPONSE, RuleAction.CHAOS, RuleAction.SCRIPT_REWRITE)
            .forEach { action -> assertNotNull(action.name, ruleConfigError(RuleConfigDraft(action))) }
    }

    @Test
    fun `ruleConfigError keeps a blank MOCK_RESPONSE draft valid`() {
        assertNull(ruleConfigError(RuleConfigDraft(RuleAction.MOCK_RESPONSE)))
        assertNull(ruleConfigError(RuleConfigDraft(RuleAction.MOCK_RESPONSE, variables = mapOf("id" to "1"))))
    }

    @Test
    fun `ruleConfigError names the Script field for a blank SCRIPT_REWRITE script`() {
        val error = ruleConfigError(RuleConfigDraft(RuleAction.SCRIPT_REWRITE, scriptText = "  "))!!
        assertTrue(error, error.contains("Script (JS)"))
        // Variables alone do not rescue it -- the persisted {"variables":{...}} has no script.
        assertNotNull(ruleConfigError(RuleConfigDraft(RuleAction.SCRIPT_REWRITE, variables = mapOf("a" to "b"))))
        assertNull(ruleConfigError(RuleConfigDraft(RuleAction.SCRIPT_REWRITE, scriptText = "pm.test('x', () => {});")))
    }

    @Test
    fun `ruleConfigError reports non-JSON raw text even when variables would otherwise swallow it`() {
        // mergeVariablesIntoConfigJson discards unparseable base text, so validating only the
        // merged value would mis-report this as "no effective key".
        val error = ruleConfigError(
            RuleConfigDraft(RuleAction.MODIFY_REQUEST, replacementValueText = "garbage", variables = mapOf("a" to "b")),
        )!!
        assertTrue(error, error.contains("JSON object"))
    }

    @Test
    fun `ruleConfigError rejects a blank MODIFY_REQUEST field even when variables are present`() {
        val error = ruleConfigError(RuleConfigDraft(RuleAction.MODIFY_REQUEST, variables = mapOf("traceId" to "abc")))
        assertNotNull("a variables-only merged config must still be rejected as a no-op", error)
    }

    @Test
    fun `ruleConfigError gates the AES actions on a key alias`() {
        assertNotNull(ruleConfigError(RuleConfigDraft(RuleAction.BODY_ENCRYPT_AES)))
        assertNull(ruleConfigError(RuleConfigDraft(RuleAction.BODY_DECRYPT_AES, cryptoKeyAlias = "my-key")))
    }

    @Test
    fun `ruleConfigError re-evaluates when only the action changes on an otherwise identical draft`() {
        // Same typed text, different action chip: what is a valid mock body is not a valid
        // chaos config, and a blank field flips between valid and invalid purely by action.
        val mockJson = RuleConfigDraft(RuleAction.MOCK_RESPONSE, replacementValueText = """{"statusCode":418}""")
        assertNull(ruleConfigError(mockJson))
        assertNotNull(ruleConfigError(mockJson.copy(action = RuleAction.CHAOS)))
        assertNull(ruleConfigError(mockJson.copy(action = RuleAction.MODIFY_RESPONSE)))
        assertNotNull("MODIFY_REQUEST never reads statusCode", ruleConfigError(mockJson.copy(action = RuleAction.MODIFY_REQUEST)))
        assertNotNull(ruleConfigError(mockJson.copy(action = RuleAction.REDIRECT)))
        assertNull(ruleConfigError(mockJson.copy(action = RuleAction.BLOCK)))

        val blank = RuleConfigDraft(RuleAction.BLOCK)
        assertNull(ruleConfigError(blank))
        assertNotNull(ruleConfigError(blank.copy(action = RuleAction.MODIFY_REQUEST)))
        assertNull(ruleConfigError(blank.copy(action = RuleAction.MOCK_RESPONSE)))
        assertNotNull(ruleConfigError(blank.copy(action = RuleAction.SCRIPT_REWRITE)))
        assertNull(ruleConfigError(blank.copy(action = RuleAction.LOG_ONLY)))
    }

    // ── rulePatternError ────────────────────────────────────────────────────────────────

    @Test
    fun `rulePatternError rejects blank and non-compiling patterns and accepts a real regex`() {
        assertNotNull(rulePatternError(""))
        assertNotNull(rulePatternError("   "))
        val broken = rulePatternError(".*(ads|tracker")!!
        assertTrue(broken, broken.contains("regex"))
        assertNull(rulePatternError(".*(ads|tracker|analytics).*"))
        assertNull(rulePatternError("example\\.com"))
    }

    // ── addRuleSaveBlocker ──────────────────────────────────────────────────────────────

    @Test
    fun `addRuleSaveBlocker reports name then pattern then config in form order`() {
        val draft = RuleConfigDraft(RuleAction.MODIFY_REQUEST)
        assertEquals("Rule Name is required.", addRuleSaveBlocker("", ".*", draft))
        assertTrue(addRuleSaveBlocker("Ads", "", draft)!!.contains("URL Pattern"))
        assertTrue(addRuleSaveBlocker("Ads", ".*", draft)!!.contains("Request modification (JSON)"))
    }

    @Test
    fun `addRuleSaveBlocker is null for a complete valid rule and for a blank MOCK_RESPONSE`() {
        assertNull(addRuleSaveBlocker("Ads", ".*ads.*", RuleConfigDraft(RuleAction.BLOCK)))
        assertNull(addRuleSaveBlocker("Mock", ".*api.*", RuleConfigDraft(RuleAction.MOCK_RESPONSE)))
        assertNull(
            addRuleSaveBlocker(
                "Trace",
                ".*",
                RuleConfigDraft(RuleAction.MODIFY_REQUEST, replacementValueText = """{"addHeaders":{"X-Trace":"1"}}"""),
            ),
        )
    }

    // ── savedRuleConfigError -- pre-existing rules ──────────────────────────────────────

    @Test
    fun `savedRuleConfigError flags legacy no-op rules saved before the form refused them`() {
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.MODIFY_REQUEST, replacementValue = null)))
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.MODIFY_RESPONSE, replacementValue = "")))
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.CHAOS, replacementValue = """{"failRate":0}""")))
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.SCRIPT_REWRITE, replacementValue = null)))
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.SCRIPT_REWRITE, replacementValue = """{"variables":{"a":"b"}}""")))
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.REDIRECT, replacementValue = "/relative")))
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.BODY_ENCRYPT_AES, cryptoKeyAlias = "")))
    }

    @Test
    fun `savedRuleConfigError keeps a blank MOCK_RESPONSE and every effective rule clean`() {
        assertNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.MOCK_RESPONSE, replacementValue = null)))
        assertNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.BLOCK)))
        assertNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.LOG_ONLY)))
        assertNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.MODIFY_REQUEST, replacementValue = """{"removeHeaders":["Cookie"]}""")))
        assertNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.SCRIPT_REWRITE, replacementValue = """{"script":"pm.test('a',()=>{})"}""")))
        assertNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = RuleAction.BODY_DECRYPT_AES, cryptoKeyAlias = "k")))
    }

    @Test
    fun `savedRuleConfigError flags a rule that can never match but not one that matches by domain`() {
        // getMatchingRule: domain substring OR compiled pattern -- with a blank domain a broken
        // pattern means the rule is never selected at all.
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = "(", domain = "", action = RuleAction.BLOCK)))
        assertNotNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = "", domain = "", action = RuleAction.BLOCK)))
        assertNull(savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = "(", domain = "ads.example.com", action = RuleAction.BLOCK)))
    }

    @Test
    fun `savedRuleConfigError never throws on garbage persisted values`() {
        RuleAction.values().forEach { action ->
            listOf(null, "", "{", "[]", "\u0000", "{\"addHeaders\":null,\"delayMs\":{}}").forEach { value ->
                // Result may be null or a message; the only failure mode is an exception.
                savedRuleConfigError(InterceptRuleEntity(name = "r", pattern = ".*", action = action, replacementValue = value, cryptoKeyAlias = "k"))
            }
        }
    }

    // ── chaosAttributionSummary / scriptErrorSummary (x-requestr-* badges) ─────────────

    @Test
    fun `chaosAttributionSummary is null without the header`() {
        assertNull(chaosAttributionSummary(emptyMap()))
        assertNull(chaosAttributionSummary(mapOf("content-type" to "text/plain", "x-requestr-crypto-failed" to "true")))
        assertNull(chaosAttributionSummary(mapOf("x-requestr-chaos" to "   ")))
    }

    @Test
    fun `chaosAttributionSummary explains fail, corrupt-body and delay by rule name`() {
        val fail = chaosAttributionSummary(mapOf("x-requestr-chaos" to "fail; rule=\"flaky\""))!!
        assertTrue(fail, fail.contains("\"flaky\"") && fail.contains("never reached the server"))
        val corrupt = chaosAttributionSummary(mapOf("X-Requestr-Chaos" to "corrupt-body; rule=\"flaky\""))!!
        assertTrue(corrupt, corrupt.contains("corrupted this response body"))
        val empty = chaosAttributionSummary(mapOf("x-requestr-chaos" to "corrupt-body; rule=\"flaky\"; body-empty=true"))!!
        assertTrue(empty, empty.contains("empty so nothing changed"))
        val delay = chaosAttributionSummary(mapOf("x-requestr-chaos" to "delay=1500ms; rule=\"flaky\""))!!
        assertTrue(delay, delay.contains("delayed this response by 1500ms"))
    }

    @Test
    fun `chaosAttributionSummary never hides an unrecognised value`() {
        val other = chaosAttributionSummary(mapOf("x-requestr-chaos" to "something-new"))!!
        assertTrue(other, other.contains("something-new"))
    }

    @Test
    fun `scriptErrorSummary reports the leg, rule and error and is null when clean`() {
        assertNull(scriptErrorSummary(emptyMap(), emptyMap()))
        val onResponse = scriptErrorSummary(emptyMap(), mapOf("x-requestr-script-error" to "Error: boom; rule=\"js\""))!!
        assertTrue(onResponse, onResponse.contains("\"js\"") && onResponse.contains("response") && onResponse.endsWith("Error: boom"))
        val onRequest = scriptErrorSummary(mapOf("X-Requestr-Script-Error" to "ReferenceError: x; rule=\"js\""), emptyMap())!!
        assertTrue(onRequest, onRequest.contains("rewriting the request") && onRequest.endsWith("ReferenceError: x"))
    }
}
