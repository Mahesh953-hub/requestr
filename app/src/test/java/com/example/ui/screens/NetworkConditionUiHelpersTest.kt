package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkConditionUiHelpersTest {

    // ── parseClampedNonNegativeIntInput ─────────────────────────────────────────────────────

    @Test
    fun `parses a plain valid integer unchanged when within range`() {
        assertEquals(300, parseClampedNonNegativeIntInput("300", 10_000))
    }

    @Test
    fun `blank input falls back to zero`() {
        assertEquals(0, parseClampedNonNegativeIntInput("", 10_000))
        assertEquals(0, parseClampedNonNegativeIntInput("   ", 10_000))
    }

    @Test
    fun `non-numeric input falls back to zero`() {
        assertEquals(0, parseClampedNonNegativeIntInput("abc", 10_000))
        assertEquals(0, parseClampedNonNegativeIntInput("12ms", 10_000))
        assertEquals(0, parseClampedNonNegativeIntInput("-", 10_000))
    }

    @Test
    fun `negative input clamps to zero`() {
        assertEquals(0, parseClampedNonNegativeIntInput("-5", 10_000))
        assertEquals(0, parseClampedNonNegativeIntInput("-999999", 10_000))
    }

    @Test
    fun `over-max input clamps to max`() {
        assertEquals(10_000, parseClampedNonNegativeIntInput("999999", 10_000))
    }

    @Test
    fun `int overflow text falls back to zero rather than throwing`() {
        // Too large to fit in an Int -- String#toIntOrNull returns null for this, not a parsed
        // huge value, so this must land on the "unparseable" fallback, not on the max clamp.
        assertEquals(0, parseClampedNonNegativeIntInput("99999999999999999999", 10_000))
    }

    @Test
    fun `surrounding whitespace is trimmed before parsing`() {
        assertEquals(42, parseClampedNonNegativeIntInput("  42  ", 10_000))
    }

    // ── parseClampedPercentInput -- delegates to the above, so only the 0..100 ceiling is new ──

    @Test
    fun `percent input is passed through in range and clamped to 100 above it`() {
        assertEquals(50, parseClampedPercentInput("50"))
        assertEquals(100, parseClampedPercentInput("100"))
        assertEquals(100, parseClampedPercentInput("250"))
    }

    // ── sanitizedNetworkCondition -- must never throw regardless of input ──────────────────

    @Test
    fun `sanitized condition never throws for extreme int values`() {
        val condition = sanitizedNetworkCondition(
            enabled = true,
            latencyMs = Int.MAX_VALUE,
            jitterMs = Int.MIN_VALUE,
            packetLossPercent = Int.MIN_VALUE,
        )
        assertEquals(MAX_NETWORK_LATENCY_MS, condition.latencyMs)
        assertEquals(0, condition.jitterMs)
        assertEquals(0, condition.packetLossPercent)
    }

    @Test
    fun `sanitized condition clamps packet loss above 100`() {
        val condition = sanitizedNetworkCondition(
            enabled = true,
            latencyMs = 10,
            jitterMs = 10,
            packetLossPercent = 500,
        )
        assertEquals(100, condition.packetLossPercent)
    }

    @Test
    fun `sanitized condition preserves valid in-range values exactly`() {
        val condition = sanitizedNetworkCondition(enabled = true, latencyMs = 200, jitterMs = 50, packetLossPercent = 5)
        assertEquals(true, condition.enabled)
        assertEquals(200, condition.latencyMs)
        assertEquals(50, condition.jitterMs)
        assertEquals(5, condition.packetLossPercent)
    }

    @Test
    fun `disabled sanitized condition still clamps fields`() {
        val condition = sanitizedNetworkCondition(enabled = false, latencyMs = -1, jitterMs = -1, packetLossPercent = -1)
        assertEquals(false, condition.enabled)
        assertEquals(0, condition.latencyMs)
        assertEquals(0, condition.jitterMs)
        assertEquals(0, condition.packetLossPercent)
    }

    // ── NETWORK_CONDITION_PRESETS -- must all be valid, enabled, non-trivial conditions ─────

    @Test
    fun `every preset is enabled and within the documented clamp ranges`() {
        NETWORK_CONDITION_PRESETS.forEach { preset ->
            val c = preset.condition
            assert(c.enabled) { "${preset.label} should be enabled" }
            assert(c.latencyMs in 0..MAX_NETWORK_LATENCY_MS) { "${preset.label} latency out of range" }
            assert(c.jitterMs in 0..MAX_NETWORK_JITTER_MS) { "${preset.label} jitter out of range" }
            assert(c.packetLossPercent in 0..100) { "${preset.label} packet loss out of range" }
        }
    }

    @Test
    fun `preset labels are unique`() {
        val labels = NETWORK_CONDITION_PRESETS.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `preset descriptions name their concrete numbers`() {
        // The spec requires the label/supporting text to state the concrete numbers, not just
        // a vague qualitative label -- assert each description mentions its own latency figure.
        NETWORK_CONDITION_PRESETS.forEach { preset ->
            assert(preset.description.contains("${preset.condition.latencyMs}ms")) {
                "${preset.label} description should mention its latency figure: ${preset.description}"
            }
        }
    }
}
