package com.example.interception

import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the two bounds a CHAOS config is subject to, both added after review:
 *
 *  - `delayMs` is clamped to [RuleEnforcer.MAX_CHAOS_DELAY_MS]. A chaos delay parks the
 *    server→client thread of one connection, so an unbounded value is indistinguishable from a
 *    hung app: the client has timed out long before, and the only effect is a pinned thread.
 *  - the metadata headers retained when header logging is off are a closed allow-list
 *    ([RuleEnforcer.METADATA_HEADER_KEYS]), not an `x-requestr-` prefix match, so a header the
 *    client app under test happens to send with that prefix is still suppressed.
 *
 * Robolectric because `parseChaosConfig` goes through `org.json` — the plain `android.jar` stub
 * silently no-ops instead of parsing, which would let these assertions pass while proving nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ChaosConfigBoundsTest {

    private fun parse(config: String) = RuleEnforcer.parseChaosConfig(config)

    // ─── delayMs clamp ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a delay within the bound is preserved exactly`() {
        val range = parse("""{"delayMs":[100,2500]}""").delayMsRange
        assertEquals(100L, range.first)
        assertEquals(2500L, range.last)
    }

    @Test
    fun `an hour-long delay is clamped to the maximum rather than parking a thread`() {
        val range = parse("""{"delayMs":[0,3600000]}""").delayMsRange
        assertEquals(0L, range.first)
        assertEquals(RuleEnforcer.MAX_CHAOS_DELAY_MS, range.last)
    }

    @Test
    fun `a lower bound past the maximum clamps both ends and stays a valid range`() {
        val range = parse("""{"delayMs":[600000,900000]}""").delayMsRange
        assertEquals(RuleEnforcer.MAX_CHAOS_DELAY_MS, range.first)
        assertEquals(RuleEnforcer.MAX_CHAOS_DELAY_MS, range.last)
        assertFalse("clamping must not invert the range", range.isEmpty())
    }

    @Test
    fun `a single-element delay array is clamped too`() {
        val range = parse("""{"delayMs":[7200000]}""").delayMsRange
        assertEquals(RuleEnforcer.MAX_CHAOS_DELAY_MS, range.first)
        assertEquals(RuleEnforcer.MAX_CHAOS_DELAY_MS, range.last)
    }

    @Test
    fun `a negative delay still floors at zero`() {
        val range = parse("""{"delayMs":[-5000,-10]}""").delayMsRange
        assertEquals(0L, range.first)
        assertEquals(0L, range.last)
    }

    @Test
    fun `the clamp cannot turn a real delay into a no-op`() {
        // Regression guard: an earlier bug in this area narrowed a range before sampling and made
        // the clamp a silent no-op. A clamped config must still actually delay.
        val config = parse("""{"delayMs":[0,3600000]}""")
        val outcome = ChaosInjector.decide(config, kotlin.random.Random(1))
        assertTrue(
            "a clamped delay range must still be able to produce a Delay outcome",
            outcome is ChaosInjector.ChaosOutcome.Delay
        )
    }

    @Test
    fun `the clamped range is what the live engine would sleep for`() {
        val config = parse("""{"delayMs":[3600000,7200000]}""")
        repeat(25) {
            val outcome = ChaosInjector.decide(config, kotlin.random.Random(it))
            if (outcome is ChaosInjector.ChaosOutcome.Delay) {
                assertTrue(
                    "sampled ${outcome.millis}ms exceeds the clamp",
                    outcome.millis <= RuleEnforcer.MAX_CHAOS_DELAY_MS
                )
            }
        }
    }

    // ─── metadata header allow-list ─────────────────────────────────────────────────────────

    @Test
    fun `the allow-list contains exactly the headers requestr itself stamps`() {
        assertEquals(
            setOf(
                RuleEnforcer.CHAOS_ATTRIBUTION_HEADER,
                RuleEnforcer.SCRIPT_ERROR_HEADER,
                "x-requestr-crypto-failed",
                "x-requestr-json-prettified",
                // S4 (task 26+): stage attribution headers stamped by the typed rule engine.
                "x-requestr-breakpoint",
                "x-requestr-replay-id",
                "x-requestr-mirror",
                "x-requestr-rules",
            ),
            RuleEnforcer.METADATA_HEADER_KEYS
        )
        assertEquals(RuleEnforcer.METADATA_HEADER_KEYS, com.example.interception.rules.RuleMetadataHeaders.KEYS)
    }

    @Test
    fun `a client header sharing the prefix is not treated as requestr metadata`() {
        // This is the whole point of the allow-list: `x-requestr-` is not a reserved namespace on
        // the wire, so an app under test could send one, and client content must stay suppressed
        // when the user has turned header logging off.
        assertFalse("x-requestr-session" in RuleEnforcer.METADATA_HEADER_KEYS)
        assertFalse("x-requestr-" in RuleEnforcer.METADATA_HEADER_KEYS)
    }

    @Test
    fun `every allow-listed key is lowercase so it matches parsed header keys`() {
        RuleEnforcer.METADATA_HEADER_KEYS.forEach { key ->
            assertEquals("header keys are lowercased by the parser", key.lowercase(), key)
        }
    }

    // ─── the clamp is applied at parse time, so an imported rule is bounded too ──────────────

    @Test
    fun `a rule persisted by an earlier build is clamped on read rather than on write`() {
        val rule = InterceptRuleEntity(
            domain = "example.com",
            pattern = "",
            action = RuleAction.CHAOS,
            replacementValue = """{"delayMs":[3600000,3600000]}""",
            enabled = true,
        )
        val range = RuleEnforcer.parseChaosConfig(rule.replacementValue).delayMsRange
        assertEquals(RuleEnforcer.MAX_CHAOS_DELAY_MS, range.last)
    }
}
