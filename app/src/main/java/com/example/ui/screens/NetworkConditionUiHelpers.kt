package com.example.ui.screens

import com.example.interception.NetworkConditionInjector.NetworkCondition

/**
 * Master plan §3 (C2) — pure UI-layer helpers for [SettingsScreen]'s "WEAK NETWORK SIMULATION"
 * section: text-input clamping (so a [NetworkCondition] can never be constructed out of its own
 * valid range and throw -- see that class's `init` block) and the one-tap preset table.
 *
 * Clamp maxima are a UI-only choice, not an engine constraint -- [NetworkCondition] itself only
 * requires non-negative latency/jitter and a `0..100` loss percent. [MAX_NETWORK_LATENCY_MS] and
 * [MAX_NETWORK_JITTER_MS] exist purely to keep the slider/typed-input range sane: beyond ~10s a
 * simulated delay is indistinguishable from "the connection basically hung" and adds no further
 * illustrative value, while staying under most HTTP client read-timeout defaults means requests
 * still visibly *complete* (slowly) instead of looking identical to an unrelated timeout bug.
 */

/** Upper bound offered for `latencyMs` in the Settings UI -- see this file's KDoc for why. */
const val MAX_NETWORK_LATENCY_MS: Int = 10_000

/** Upper bound offered for `jitterMs` in the Settings UI -- see this file's KDoc for why. */
const val MAX_NETWORK_JITTER_MS: Int = 5_000

/**
 * Parses a user-typed non-negative integer field (latency/jitter ms), clamped to `[0, max]`.
 * Non-numeric, blank, negative, or too-large-to-fit-in-an-`Int` text all fall back to `0` rather
 * than throwing -- a mid-edit text field (e.g. just "-", or empty after backspacing) must never
 * crash the screen.
 */
fun parseClampedNonNegativeIntInput(raw: String, max: Int): Int {
    val parsed = raw.trim().toIntOrNull() ?: return 0
    return parsed.coerceIn(0, max)
}

/** Parses a user-typed packet-loss percent field, clamped to `[0, 100]`. */
fun parseClampedPercentInput(raw: String): Int = parseClampedNonNegativeIntInput(raw, max = 100)

/**
 * Builds a [NetworkCondition] that can never throw regardless of how out-of-range [latencyMs]/
 * [jitterMs]/[packetLossPercent] already are (e.g. a value that reached here some other way than
 * [parseClampedNonNegativeIntInput]/[parseClampedPercentInput], such as a slider drag's
 * transient intermediate value) -- the final defensive clamp immediately before construction.
 */
fun sanitizedNetworkCondition(
    enabled: Boolean,
    latencyMs: Int,
    jitterMs: Int,
    packetLossPercent: Int,
): NetworkCondition = NetworkCondition(
    enabled = enabled,
    latencyMs = latencyMs.coerceIn(0, MAX_NETWORK_LATENCY_MS),
    jitterMs = jitterMs.coerceIn(0, MAX_NETWORK_JITTER_MS),
    packetLossPercent = packetLossPercent.coerceIn(0, 100),
)

/** One one-tap weak-network preset offered in the Settings UI. */
data class NetworkConditionPreset(
    val label: String,
    /** Shown as supporting text under [label] -- must name the concrete numbers, per the spec. */
    val description: String,
    val condition: NetworkCondition,
)

/**
 * Illustrative, honestly-labeled one-tap presets. Figures are ballpark real-world RTT/loss
 * figures for each connection class, not a promise of exact real-network fidelity -- each
 * [NetworkConditionPreset.description] says "approx." for that reason. Packet loss here always
 * means "this fraction of connection attempts fail outright", per [NetworkConditionInjector]'s
 * KDoc -- never literal per-packet corruption.
 */
val NETWORK_CONDITION_PRESETS: List<NetworkConditionPreset> = listOf(
    NetworkConditionPreset(
        label = "3G",
        description = "Approx. 300ms latency, ±100ms jitter, 1% connection-failure rate",
        condition = sanitizedNetworkCondition(enabled = true, latencyMs = 300, jitterMs = 100, packetLossPercent = 1),
    ),
    NetworkConditionPreset(
        label = "Edge / 2G",
        description = "Approx. 800ms latency, ±200ms jitter, 3% connection-failure rate",
        condition = sanitizedNetworkCondition(enabled = true, latencyMs = 800, jitterMs = 200, packetLossPercent = 3),
    ),
    NetworkConditionPreset(
        label = "Lossy Wi-Fi",
        description = "Approx. 40ms latency, ±30ms jitter, 8% connection-failure rate -- fast but flaky",
        condition = sanitizedNetworkCondition(enabled = true, latencyMs = 40, jitterMs = 30, packetLossPercent = 8),
    ),
    NetworkConditionPreset(
        label = "Satellite",
        description = "Approx. 600ms latency, ±50ms jitter, 2% connection-failure rate -- geostationary RTT",
        condition = sanitizedNetworkCondition(enabled = true, latencyMs = 600, jitterMs = 50, packetLossPercent = 2),
    ),
)
