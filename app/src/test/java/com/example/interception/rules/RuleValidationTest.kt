package com.example.interception.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4 task 33 (D3): [RuleValidation] and [RuleLimits] -- the save-time gate and the compile-time backstop. */
class RuleValidationTest {

    private fun rule(config: RuleConfig, enabled: Boolean = true, name: String = "r") = InterceptorRule(
        id = 1, name = name, stage = config.stage, enabled = enabled, matcher = RuleMatcherSpec(hostGlobs = listOf("api.example")), config = config,
    )

    private fun fields(problems: List<RuleValidation.Problem>) = problems.map { it.field }

    @Test
    fun `limits are the values D3 pins`() {
        assertEquals(200, RuleLimits.MAX_ENABLED_RULES)
        assertEquals(8, RuleLimits.MAX_PAUSED_FLOWS)
        assertEquals(255, RuleLimits.MAX_REPLAY_CONCURRENCY)
        assertEquals(10_000L, RuleLimits.MAX_SCRIPT_TIMEOUT_MS)
        assertEquals(30_000L, RuleLimits.MAX_CHAOS_DELAY_MS)
        assertEquals(RuleLimits.MAX_CHAOS_DELAY_MS, ChaosConfig.MAX_DELAY_MS)
    }

    @Test
    fun `a well-formed acknowledged rule has no problems`() {
        assertTrue(RuleValidation.validate(rule(ScriptConfig(source = "1")), enabledRules = 10, acknowledged = true).isEmpty())
        assertTrue(RuleValidation.validate(rule(GatewayConfig(GatewayMode.BLOCK)), enabledRules = 199, acknowledged = false).isEmpty())
    }

    @Test
    fun `the 201st enabled rule is rejected but a disabled one is not`() {
        val problems = RuleValidation.validate(rule(GatewayConfig(GatewayMode.BLOCK)), enabledRules = 200)
        assertEquals(listOf("enabled"), fields(problems))
        assertTrue(RuleValidation.validate(rule(GatewayConfig(GatewayMode.BLOCK), enabled = false), enabledRules = 200).isEmpty())
    }

    @Test
    fun `an 11 s script timeout is rejected and clamped`() {
        val cfg = ScriptConfig(source = "1", timeoutMs = 11_000)
        assertEquals(listOf("timeoutMs"), fields(RuleValidation.validate(rule(cfg))))
        val clamped = RuleValidation.clampForSnapshot(cfg)
        assertTrue(clamped.changed)
        assertEquals(10_000L, (clamped.config as ScriptConfig).timeoutMs)
        assertFalse(RuleValidation.clampForSnapshot(ScriptConfig(source = "1", timeoutMs = 10_000)).changed)
    }

    @Test
    fun `a chaos delay above 30 s is rejected and clamped`() {
        val cfg = ChaosConfig(delayMinMs = 1_000, delayMaxMs = 45_000)
        assertEquals(listOf("delayMs"), fields(RuleValidation.validate(rule(cfg))))
        val clamped = RuleValidation.clampForSnapshot(cfg).config as ChaosConfig
        assertEquals(1_000L, clamped.delayMinMs)
        assertEquals(30_000L, clamped.delayMaxMs)
        assertTrue(fields(RuleValidation.validate(rule(ChaosConfig(failRate = 1.5)))).contains("failRate"))
    }

    @Test
    fun `an over-long breakpoint timeout is clamped and a blank name or bad regex is a problem`() {
        val clamped = RuleValidation.clampForSnapshot(BreakpointConfig(timeoutMs = 3 * 60 * 60_000L))
        assertEquals(RuleLimits.MAX_BREAKPOINT_TIMEOUT_MS, (clamped.config as BreakpointConfig).timeoutMs)
        val bad = InterceptorRule(id = 1, name = " ", stage = RuleStage.OBSERVE, matcher = RuleMatcherSpec(urlRegex = "("), config = ObserveConfig)
        assertEquals(setOf("name", "matcher"), fields(RuleValidation.validate(bad)).toSet())
    }

    @Test
    fun `the snapshot compiler excludes the 201st enabled rule and clamps configs with an issue each`() {
        // The DAO hands the compiler rows in (sortOrder, id) order; the cap keeps the first 200.
        val entities = listOf(com.example.data.local.InterceptorRuleEntity.fromDomain(rule(ScriptConfig(source = "1", timeoutMs = 60_000), name = "slow").copy(id = 500, sortOrder = 0))) +
            (1..201).map { i ->
                com.example.data.local.InterceptorRuleEntity.fromDomain(rule(GatewayConfig(GatewayMode.BLOCK), name = "g$i").copy(id = i.toLong(), sortOrder = i))
            }
        val snapshot = RuleSnapshotCompiler.compile(entities, emptyList(), revision = 1)
        assertEquals(RuleLimits.MAX_ENABLED_RULES, snapshot.size)
        // The script rule is inside the cap -- and clamped.
        val script = snapshot.rules.single { it.stage == RuleStage.SCRIPT }
        assertEquals(10_000L, (script.config as ScriptConfig).timeoutMs)
        assertTrue(snapshot.issues.any { it.ruleName == "slow" && it.message.contains("clamped") })
        assertTrue(snapshot.issues.any { it.ruleName == "g201" && it.message.contains("more than 200") })
    }
}
