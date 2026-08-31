package com.example.ui.screens

import android.os.Build
import com.example.data.local.RuleAction
import com.example.interception.RuleTemplateExpander
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed (not plain JUnit), same reasoning as [CapturedTrafficHelpersTest]'s own
 * header comment: `mergeVariablesIntoConfigJson`/`mergeScriptIntoConfigJson`'s `org.json.JSONObject`
 * calls need a real JSON implementation, not Android's unit-test stub jar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleTemplateUiHelpersTest {

    // ── ruleActionUsesTemplating ─────────────────────────────────────────────────────────────

    @Test
    fun `templating affordances show only for the four templatable actions`() {
        assertTrue(ruleActionUsesTemplating(RuleAction.MODIFY_REQUEST))
        assertTrue(ruleActionUsesTemplating(RuleAction.MODIFY_RESPONSE))
        assertTrue(ruleActionUsesTemplating(RuleAction.MOCK_RESPONSE))
        assertTrue(ruleActionUsesTemplating(RuleAction.SCRIPT_REWRITE))
        assertFalse(ruleActionUsesTemplating(RuleAction.BLOCK))
        assertFalse(ruleActionUsesTemplating(RuleAction.REDIRECT))
        assertFalse(ruleActionUsesTemplating(RuleAction.LOG_ONLY))
        assertFalse(ruleActionUsesTemplating(RuleAction.FORCE_MITM))
        assertFalse(ruleActionUsesTemplating(RuleAction.CHAOS))
        assertFalse(ruleActionUsesTemplating(RuleAction.BODY_ENCRYPT_AES))
        assertFalse(ruleActionUsesTemplating(RuleAction.BODY_DECRYPT_AES))
    }

    // ── insertTextAtCursor ───────────────────────────────────────────────────────────────────

    @Test
    fun `inserts at the middle of a string and advances the cursor past the insertion`() {
        val (text, cursor) = insertTextAtCursor("Hello world", 5, "{{name}}")
        assertEquals("Hello{{name}} world", text)
        assertEquals(13, cursor)
    }

    @Test
    fun `inserts at the start when cursor is zero`() {
        val (text, cursor) = insertTextAtCursor("world", 0, "hi ")
        assertEquals("hi world", text)
        assertEquals(3, cursor)
    }

    @Test
    fun `inserts at the end when cursor equals text length`() {
        val (text, cursor) = insertTextAtCursor("hi", 2, "!")
        assertEquals("hi!", text)
        assertEquals(3, cursor)
    }

    @Test
    fun `out-of-range cursor is clamped instead of throwing`() {
        val (text, cursor) = insertTextAtCursor("abc", 999, "X")
        assertEquals("abcX", text)
        assertEquals(4, cursor)

        val (text2, cursor2) = insertTextAtCursor("abc", -5, "X")
        assertEquals("Xabc", text2)
        assertEquals(1, cursor2)
    }

    @Test
    fun `tapping the same token twice appends both copies in order`() {
        val (once, cursorAfterOnce) = insertTextAtCursor("", 0, "{{\$guid}}")
        val (twice, _) = insertTextAtCursor(once, cursorAfterOnce, "{{\$guid}}")
        assertEquals("{{\$guid}}{{\$guid}}", twice)
    }

    // ── variableRowsToMap ────────────────────────────────────────────────────────────────────

    @Test
    fun `blank keys are dropped from the variables map`() {
        val map = variableRowsToMap(listOf("token" to "abc123", "" to "ignored", "  " to "ignored2"))
        assertEquals(mapOf("token" to "abc123"), map)
    }

    @Test
    fun `duplicate keys keep the last row's value`() {
        val map = variableRowsToMap(listOf("k" to "first", "k" to "second"))
        assertEquals(mapOf("k" to "second"), map)
    }

    @Test
    fun `keys are trimmed but values are kept exactly`() {
        val map = variableRowsToMap(listOf("  spacedKey  " to "  spaced value  "))
        assertEquals(mapOf("spacedKey" to "  spaced value  "), map)
    }

    @Test
    fun `empty row list yields an empty map`() {
        assertTrue(variableRowsToMap(emptyList()).isEmpty())
    }

    // ── mergeVariablesIntoConfigJson ─────────────────────────────────────────────────────────

    @Test
    fun `merging variables into a blank base config produces just the variables key`() {
        val result = mergeVariablesIntoConfigJson(null, mapOf("traceId" to "abc"))
        val json = JSONObject(result)
        assertEquals(1, json.length())
        assertEquals("abc", json.getJSONObject("variables").getString("traceId"))
    }

    @Test
    fun `merging variables preserves unrelated existing config keys`() {
        val base = """{"addHeaders":{"X-Custom":"1"},"bodyReplace":"{}","statusCode":200}"""
        val result = mergeVariablesIntoConfigJson(base, mapOf("env" to "staging"))
        val json = JSONObject(result)

        assertEquals("1", json.getJSONObject("addHeaders").getString("X-Custom"))
        assertEquals("{}", json.getString("bodyReplace"))
        assertEquals(200, json.getInt("statusCode"))
        assertEquals("staging", json.getJSONObject("variables").getString("env"))
    }

    @Test
    fun `variables round-trip through merge and back out unchanged`() {
        val original = mapOf("a" to "1", "b" to "two", "c" to "")
        val merged = mergeVariablesIntoConfigJson("""{"bodyReplace":"x"}""", original)

        val parsedBack = JSONObject(merged).getJSONObject("variables")
        val roundTripped = LinkedHashMap<String, String>()
        parsedBack.keys().forEach { key -> roundTripped[key] = parsedBack.getString(key) }

        assertEquals(original, roundTripped)
    }

    @Test
    fun `an empty variables map removes the variables key entirely`() {
        val base = """{"bodyReplace":"x","variables":{"stale":"value"}}"""
        val result = mergeVariablesIntoConfigJson(base, emptyMap())
        val json = JSONObject(result)

        assertEquals("x", json.getString("bodyReplace"))
        assertFalse(json.has("variables"))
    }

    @Test
    fun `malformed base config falls back to a fresh object instead of throwing`() {
        val result = mergeVariablesIntoConfigJson("not valid json at all {{{", mapOf("k" to "v"))
        val json = JSONObject(result)
        assertEquals("v", json.getJSONObject("variables").getString("k"))
    }

    @Test
    fun `blank keys inside the variables map itself are skipped defensively`() {
        val result = mergeVariablesIntoConfigJson(null, mapOf("" to "ignored", "real" to "kept"))
        val json = JSONObject(result).getJSONObject("variables")
        assertFalse(json.has(""))
        assertEquals("kept", json.getString("real"))
    }

    // ── mergeScriptIntoConfigJson ────────────────────────────────────────────────────────────

    @Test
    fun `sets the script key and preserves other keys`() {
        val base = """{"variables":{"k":"v"}}"""
        val result = mergeScriptIntoConfigJson(base, "pm.request.headers.set('X', '1');")
        val json = JSONObject(result)
        assertEquals("pm.request.headers.set('X', '1');", json.getString("script"))
        assertEquals("v", json.getJSONObject("variables").getString("k"))
    }

    @Test
    fun `blank script removes the script key rather than storing an empty string`() {
        val base = """{"script":"old script","variables":{"k":"v"}}"""
        val result = mergeScriptIntoConfigJson(base, "")
        val json = JSONObject(result)
        assertFalse(json.has("script"))
        assertEquals("v", json.getJSONObject("variables").getString("k"))
    }

    @Test
    fun `script and variables compose together via sequential merges`() {
        val withScript = mergeScriptIntoConfigJson(null, "pm.test('ok', function(){});")
        val withBoth = mergeVariablesIntoConfigJson(withScript, mapOf("env" to "prod"))
        val json = JSONObject(withBoth)
        assertEquals("pm.test('ok', function(){});", json.getString("script"))
        assertEquals("prod", json.getJSONObject("variables").getString("env"))
    }

    // ── templateCheatSheet / dynamicGeneratorTemplateTokens ─────────────────────────────────

    @Test
    fun `cheat sheet includes the built-in request tokens`() {
        val tokens = templateCheatSheet(emptyList()).map { it.token }
        assertTrue(tokens.contains("{{request.host}}"))
        assertTrue(tokens.contains("{{request.path}}"))
        assertTrue(tokens.contains("{{request.method}}"))
        assertTrue(tokens.contains("{{request.url}}"))
        assertTrue(tokens.contains("{{request.header.NAME}}"))
        assertTrue(tokens.contains("{{request.query.NAME}}"))
    }

    @Test
    fun `cheat sheet includes the legacy tokens`() {
        val tokens = templateCheatSheet(emptyList()).map { it.token }
        assertTrue(tokens.contains("{{TIMESTAMP}}"))
        assertTrue(tokens.contains("{{RANDOM}}"))
        assertTrue(tokens.contains("{{UUID}}"))
    }

    @Test
    fun `cheat sheet includes every dynamic generator from DynamicVariables`() {
        val tokens = templateCheatSheet(emptyList()).map { it.token }.toSet()
        com.example.scripting.DynamicVariables.supportedNames.forEach { name ->
            assertTrue("expected {{\$$name}} in cheat sheet", tokens.contains("{{\$$name}}"))
        }
    }

    @Test
    fun `cheat sheet appends the rule's own non-blank variable keys last`() {
        val tokens = templateCheatSheet(listOf("traceId", "", "  ", "env"))
        val userTokens = tokens.filter { it.description == "This rule's own variable" }
        assertEquals(listOf("{{traceId}}", "{{env}}"), userTokens.map { it.token })
    }

    @Test
    fun `cheat sheet de-duplicates repeated user variable keys`() {
        val tokens = templateCheatSheet(listOf("dup", "dup"))
        val userTokens = tokens.filter { it.description == "This rule's own variable" }
        assertEquals(1, userTokens.size)
    }

    // ── templatePreviewContext -- must actually resolve the built-ins it advertises ─────────

    @Test
    fun `preview context resolves every built-in request token via the real expander`() {
        val context = templatePreviewContext()
        assertEquals("api.example.com", RuleTemplateExpander.expand("{{request.host}}", context = context))
        assertEquals("/v1/users/42", RuleTemplateExpander.expand("{{request.path}}", context = context))
        assertEquals("POST", RuleTemplateExpander.expand("{{request.method}}", context = context))
        assertEquals("10", RuleTemplateExpander.expand("{{request.query.limit}}", context = context))
        assertEquals("Bearer abc123", RuleTemplateExpander.expand("{{request.header.Authorization}}", context = context))
    }

    @Test
    fun `preview correctly leaves an unknown token intact`() {
        val context = templatePreviewContext()
        assertEquals("{{totallyUnknownToken}}", RuleTemplateExpander.expand("{{totallyUnknownToken}}", context = context))
    }

    @Test
    fun `preview resolves this rule's own variables map`() {
        val context = templatePreviewContext()
        val expanded = RuleTemplateExpander.expand("hello {{name}}", mapOf("name" to "world"), context)
        assertEquals("hello world", expanded)
    }
}
