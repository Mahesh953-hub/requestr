package com.example.interception.rules

import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import com.example.interception.rules.LegacyRuleConverter.ConversionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S4 task 24 -- one case per legacy [RuleAction], plus the parity notes the converter's KDoc
 * documents: legacy fail-open on malformed JSON, `file://` fixtures surviving as [BodySource.File],
 * MODIFY_RESPONSE's never-applied `removeHeaders`, evaluation order → `sortOrder`.
 *
 * Expected shapes come straight from reading `RuleEnforcer` on `main` @ 4cf1226 -- this suite is
 * the spec the parity gate G16 (task 26) later executes end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyRuleConverterTest {

    private fun legacy(
        id: Long,
        action: RuleAction,
        replacement: String? = null,
        name: String = "r$id",
        domain: String = "",
        pattern: String = "",
        enabled: Boolean = true,
        createdAt: Long = id * 1_000,
        alias: String = "",
        direction: String = "ENCRYPT",
        scope: String = "BOTH",
        algorithm: String = "AES/GCM/NoPadding",
    ) = InterceptRuleEntity(
        id = id, name = name, pattern = pattern, domain = domain, action = action, enabled = enabled,
        replacementValue = replacement, createdAt = createdAt, cryptoKeyAlias = alias,
        cryptoAlgorithm = algorithm, cryptoDirection = direction, cryptoScope = scope,
    )

    private fun converted(e: InterceptRuleEntity, sortOrder: Int = 0): ConversionResult.Converted {
        val r = LegacyRuleConverter.convert(e, sortOrder)
        assertTrue("expected Converted, got $r", r is ConversionResult.Converted)
        return r as ConversionResult.Converted
    }

    private inline fun <reified T : RuleConfig> configOf(e: InterceptRuleEntity): T {
        val cfg = converted(e).rule.config
        assertTrue("expected ${T::class.simpleName}, got $cfg", cfg is T)
        return cfg as T
    }

    // ── matcher ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `matcher maps domain substring to a wrapped glob and pattern to urlRegex, OR-combined`() {
        val m = LegacyRuleConverter.matcherOf(legacy(1, RuleAction.LOG_ONLY, domain = "api.example.com", pattern = "/v1/.*"))
        assertEquals(listOf("*api.example.com*"), m.hostGlobs)
        assertEquals("/v1/.*", m.urlRegex)
        assertEquals(MatchCombine.ANY, m.combine)

        val hostOnly = LegacyRuleConverter.matcherOf(legacy(2, RuleAction.LOG_ONLY, domain = "cdn"))
        assertEquals(listOf("*cdn*"), hostOnly.hostGlobs)
        assertNull(hostOnly.urlRegex)

        val patternOnly = LegacyRuleConverter.matcherOf(legacy(3, RuleAction.LOG_ONLY, pattern = "^https://x/"))
        assertTrue(patternOnly.hostGlobs.isEmpty())
        assertEquals("^https://x/", patternOnly.urlRegex)

        // Both blank: ANY with no constraints matches nothing, exactly like the legacy matcher.
        val blank = LegacyRuleConverter.matcherOf(legacy(4, RuleAction.LOG_ONLY))
        assertTrue(blank.hostGlobs.isEmpty())
        assertNull(blank.urlRegex)
        assertEquals(MatchCombine.ANY, blank.combine)
    }

    // ── identity / bookkeeping ────────────────────────────────────────────────────────────────

    @Test
    fun `rule identity fields carry over and legacyRuleId links back`() {
        val e = legacy(42, RuleAction.LOG_ONLY, name = "keep me", enabled = false, createdAt = 777)
        val r = converted(e, sortOrder = 9).rule
        assertEquals(0L, r.id)
        assertEquals("keep me", r.name)
        assertFalse(r.enabled)
        assertEquals(9, r.sortOrder)
        assertNull(r.folderId)
        assertEquals(777L, r.createdAt)
        assertEquals(777L, r.updatedAt)
        assertEquals(42L, r.legacyRuleId)
        assertEquals(RuleStage.OBSERVE, r.stage)

        val unnamed = converted(legacy(7, RuleAction.LOG_ONLY, name = "  ")).rule
        assertEquals("Rule 7", unnamed.name)
    }

    @Test
    fun `convertAll orders by createdAt then id and assigns consecutive sortOrder`() {
        val rows = listOf(
            legacy(30, RuleAction.LOG_ONLY, createdAt = 2_000),
            legacy(10, RuleAction.LOG_ONLY, createdAt = 3_000),
            legacy(20, RuleAction.LOG_ONLY, createdAt = 2_000),
            legacy(5, RuleAction.LOG_ONLY, createdAt = 1_000),
        )
        val out = LegacyRuleConverter.convertAll(rows, firstSortOrder = 100).map { it as ConversionResult.Converted }
        assertEquals(listOf(5L, 20L, 30L, 10L), out.map { it.rule.legacyRuleId })
        assertEquals(listOf(100, 101, 102, 103), out.map { it.rule.sortOrder })
    }

    // ── one case per action ───────────────────────────────────────────────────────────────────

    @Test
    fun `BLOCK becomes a gateway block that hides blocked flows from the list`() {
        val cfg = configOf<GatewayConfig>(legacy(1, RuleAction.BLOCK, domain = "ads.example"))
        assertEquals(GatewayMode.BLOCK, cfg.mode)
        assertFalse(cfg.showBlockedInList)
    }

    @Test
    fun `FORCE_MITM becomes gateway force-decrypt`() {
        assertEquals(GatewayMode.FORCE_DECRYPT, configOf<GatewayConfig>(legacy(1, RuleAction.FORCE_MITM)).mode)
    }

    @Test
    fun `LOG_ONLY becomes observe`() {
        assertEquals(ObserveConfig, converted(legacy(1, RuleAction.LOG_ONLY)).rule.config)
    }

    @Test
    fun `REDIRECT takes the bare URL string, not JSON`() {
        val cfg = configOf<RewriteConfig>(legacy(1, RuleAction.REDIRECT, replacement = "  https://staging.example/api  "))
        assertEquals(RedirectSpec("https://staging.example/api", preserveHost = false, directoryMatch = false), cfg.redirection)
        assertTrue(cfg.requestModifications.isEmpty())

        val blank = converted(legacy(2, RuleAction.REDIRECT, replacement = "   "))
        assertTrue((blank.rule.config as RewriteConfig).isNoOp)
        assertTrue(blank.warnings.single().contains("no target URL"))
    }

    @Test
    fun `MODIFY_REQUEST keeps the legacy apply order addHeaders, removeHeaders, bodyReplace`() {
        val json = """{"bodyReplace":"file:///sdcard/fixtures/login.json","removeHeaders":["cookie","x-old"],
            "addHeaders":{"x-debug":"1","authorization":"Bearer {{token}}"},"variables":{"token":"abc"}}"""
        val cfg = configOf<RewriteConfig>(legacy(1, RuleAction.MODIFY_REQUEST, replacement = json))
        assertEquals(
            listOf(
                Modification.SetHeader("x-debug", "1"),
                Modification.SetHeader("authorization", "Bearer {{token}}"),
                Modification.RemoveHeader("cookie"),
                Modification.RemoveHeader("x-old"),
                Modification.ReplaceBody(BodySource.File("file:///sdcard/fixtures/login.json")),
            ),
            cfg.requestModifications,
        )
        assertTrue(cfg.responseModifications.isEmpty())
        assertEquals(mapOf("token" to "abc"), cfg.variables)
        assertNull(cfg.redirection)
    }

    @Test
    fun `MODIFY_REQUEST with an empty bodyReplace replaces with an empty body`() {
        val cfg = configOf<RewriteConfig>(legacy(1, RuleAction.MODIFY_REQUEST, replacement = """{"bodyReplace":""}"""))
        assertEquals(listOf<Modification>(Modification.ReplaceBody(BodySource.Empty)), cfg.requestModifications)
    }

    @Test
    fun `MODIFY_RESPONSE keeps statusCode, addHeaders, bodyReplace and warns about removeHeaders`() {
        val json = """{"statusCode":503,"addHeaders":{"Retry-After":"5"},"bodyReplace":"down","removeHeaders":["set-cookie"]}"""
        val r = converted(legacy(1, RuleAction.MODIFY_RESPONSE, replacement = json))
        val cfg = r.rule.config as RewriteConfig
        assertEquals(
            listOf(
                Modification.SetStatus(503),
                Modification.SetHeader("Retry-After", "5"),
                Modification.ReplaceBody(BodySource.Text("down")),
            ),
            cfg.responseModifications,
        )
        assertTrue(cfg.requestModifications.isEmpty())
        assertTrue(r.warnings.any { it.contains("removeHeaders") })

        val noWarn = converted(legacy(2, RuleAction.MODIFY_RESPONSE, replacement = """{"statusCode":204}"""))
        assertTrue(noWarn.warnings.isEmpty())
        assertEquals(listOf<Modification>(Modification.SetStatus(204)), (noWarn.rule.config as RewriteConfig).responseModifications)
    }

    @Test
    fun `MOCK_RESPONSE lowercases header names and applies legacy defaults`() {
        val json = """{"statusCode":201,"statusMessage":"Created","addHeaders":{"Content-Type":"application/json","X-Mock":"1"},
            "bodyReplace":"{\"id\":1}","variables":{"a":"b"}}"""
        val cfg = configOf<MockConfig>(legacy(1, RuleAction.MOCK_RESPONSE, replacement = json))
        assertEquals(201, cfg.statusCode)
        assertEquals("Created", cfg.statusMessage)
        assertEquals(mapOf("content-type" to "application/json", "x-mock" to "1"), cfg.headers)
        assertEquals(BodySource.Text("{\"id\":1}"), cfg.body)
        assertEquals(mapOf("a" to "b"), cfg.variables)

        val defaults = configOf<MockConfig>(legacy(2, RuleAction.MOCK_RESPONSE, replacement = "{}"))
        assertEquals(MockConfig(), defaults)
        assertEquals(MockConfig(), configOf<MockConfig>(legacy(3, RuleAction.MOCK_RESPONSE, replacement = null)))
    }

    @Test
    fun `SCRIPT_REWRITE carries the script source and engine defaults`() {
        val cfg = configOf<ScriptConfig>(legacy(1, RuleAction.SCRIPT_REWRITE, replacement = """{"script":"function onRequest(r){}","variables":{"k":"v"}}"""))
        assertEquals("function onRequest(r){}", cfg.source)
        assertEquals(mapOf("k" to "v"), cfg.variables)
        assertEquals(ScriptConfig(source = "x").timeoutMs, cfg.timeoutMs)
        assertEquals(ScriptConfig(source = "x").maxInstructions, cfg.maxInstructions)

        val blank = converted(legacy(2, RuleAction.SCRIPT_REWRITE, replacement = """{"script":""}"""))
        assertTrue(blank.warnings.single().contains("blank script"))
    }

    @Test
    fun `CHAOS clamps rates and delays exactly like parseChaosConfig`() {
        val json = """{"failRate":1.7,"corruptBodyRate":-0.2,"delayMs":[500,90000],"failStatusCode":502,"failStatusMessage":"Bad","failBody":"x"}"""
        val cfg = configOf<ChaosConfig>(legacy(1, RuleAction.CHAOS, replacement = json))
        assertEquals(1.0, cfg.failRate, 0.0)
        assertEquals(0.0, cfg.corruptBodyRate, 0.0)
        assertEquals(500L, cfg.delayMinMs)
        assertEquals(ChaosConfig.MAX_DELAY_MS, cfg.delayMaxMs)
        assertEquals(502, cfg.failStatusCode)
        assertEquals("Bad", cfg.failStatusMessage)
        assertEquals("x", cfg.failBody)

        // One-element array -> lo..lo; hi below lo is pulled up to lo; defaults otherwise.
        val one = configOf<ChaosConfig>(legacy(2, RuleAction.CHAOS, replacement = """{"delayMs":[250]}"""))
        assertEquals(250L..250L, one.delayRange)
        val inverted = configOf<ChaosConfig>(legacy(3, RuleAction.CHAOS, replacement = """{"delayMs":[900,100]}"""))
        assertEquals(900L..900L, inverted.delayRange)
        assertEquals(ChaosConfig.DEFAULT_FAIL_STATUS_CODE, inverted.failStatusCode)
        assertEquals(ChaosConfig.DEFAULT_FAIL_STATUS_MESSAGE, inverted.failStatusMessage)
        assertEquals(ChaosConfig.DEFAULT_FAIL_BODY, inverted.failBody)

        val nonArray = converted(legacy(4, RuleAction.CHAOS, replacement = """{"delayMs":"slow"}"""))
        assertEquals(0L..0L, (nonArray.rule.config as ChaosConfig).delayRange)
        assertTrue(nonArray.warnings.single().contains("non-array delayMs"))
    }

    @Test
    fun `BODY_ENCRYPT_AES and BODY_DECRYPT_AES take direction from the action`() {
        val enc = configOf<BodyCryptoConfig>(legacy(1, RuleAction.BODY_ENCRYPT_AES, alias = "k1", scope = "REQUEST", direction = "ENCRYPT"))
        assertEquals(BodyCryptoConfig(CryptoDirection.ENCRYPT, CryptoScope.REQUEST, "k1"), enc)

        // Column disagrees with the action -> action wins, warning recorded.
        val dec = converted(legacy(2, RuleAction.BODY_DECRYPT_AES, alias = "k2", scope = "response", direction = "ENCRYPT"))
        val decCfg = dec.rule.config as BodyCryptoConfig
        assertEquals(CryptoDirection.DECRYPT, decCfg.direction)
        assertEquals(CryptoScope.RESPONSE, decCfg.scope)
        assertTrue(dec.warnings.any { it.contains("disagrees") })

        // Garbage scope -> BOTH (legacy `else` branch); blank alias warns but converts (fails closed at apply time).
        val odd = converted(legacy(3, RuleAction.BODY_ENCRYPT_AES, alias = "", scope = "SIDEWAYS", algorithm = ""))
        val oddCfg = odd.rule.config as BodyCryptoConfig
        assertEquals(CryptoScope.BOTH, oddCfg.scope)
        assertEquals(BodyCryptoConfig.DEFAULT_ALGORITHM, oddCfg.algorithm)
        assertTrue(odd.warnings.any { it.contains("unrecognised cryptoScope") })
        assertTrue(odd.warnings.any { it.contains("blank key alias") })
    }

    // ── malformed input: legacy fail-open ─────────────────────────────────────────────────────

    @Test
    fun `malformed replacementValue converts to the stage's empty config with a warning, never Unconvertible`() {
        val cases = mapOf(
            RuleAction.MODIFY_REQUEST to RewriteConfig(),
            RuleAction.MODIFY_RESPONSE to RewriteConfig(),
            RuleAction.MOCK_RESPONSE to MockConfig(),
            RuleAction.SCRIPT_REWRITE to ScriptConfig(source = ""),
            RuleAction.CHAOS to ChaosConfig(),
        )
        cases.forEach { (action, expected) ->
            val r = converted(legacy(1, action, replacement = "{not json"))
            assertEquals("$action", expected, r.rule.config)
            assertTrue("$action should warn", r.warnings.any { it.contains("malformed replacementValue") })
        }
        // Blank config is legitimate, not malformed: no warning.
        assertTrue(converted(legacy(2, RuleAction.MODIFY_REQUEST, replacement = null)).warnings.isEmpty())
    }

    @Test
    fun `every legacy action converts and its config round-trips through the JSON codec`() {
        RuleAction.entries.forEachIndexed { i, action ->
            val r = converted(legacy(i + 1L, action, replacement = if (action == RuleAction.REDIRECT) "https://x/" else null, alias = "a"))
            val json = RuleConfigJson.encodeConfig(r.rule.config)
            assertEquals("$action", r.rule.config, RuleConfigJson.decodeConfig(json))
            assertEquals("$action", r.rule.stage, r.rule.config.stage)
        }
    }
}
