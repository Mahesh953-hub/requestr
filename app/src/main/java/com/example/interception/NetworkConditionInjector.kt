package com.example.interception

import kotlin.random.Random

/**
 * Master plan §3 (C2) — "Weak-network simulation (latency/jitter/packet-loss injection)" for the
 * live proxy relay tier ([HttpProxyShared] -- the only tier that runs in this emulator sandbox;
 * see that object's KDoc for why VPN/root/Frida tiers can't be exercised here).
 *
 * Same "pure decision function, caller applies the effect" shape as [ChaosInjector] (its sibling
 * in this file), deliberately not doing any [Thread.sleep]/socket I/O itself so it stays a fully
 * deterministic, injectable-clock/RNG unit under test. Unlike [ChaosInjector] (still unwired,
 * `RuleEnforcer` in that file's KDoc is "out of scope"), this one IS wired live into
 * [HttpProxyShared.forwardRequest]/[HttpProxyShared.handleConnectAttempt] via
 * [com.example.data.prefs.SettingsDataStore.networkCondition].
 *
 * Packet loss is modeled as an outright connection failure rather than literal mid-stream byte
 * dropping: this operates above the OS's own TCP stack, which already retransmits any real lost
 * packet transparently, so there is no way to reproduce a *silent* single-packet loss at this
 * layer without corrupting HTTP framing. A failed connection attempt is a faithful stand-in for
 * "a poor connection" and is what every reference implementation this plan cites
 * (`proxypin`'s `network_condition.dart`) actually simulates too.
 */
object NetworkConditionInjector {

    /**
     * `latencyMs`/`jitterMs` are non-negative milliseconds; `packetLossPercent` is `[0, 100]`.
     * [enabled] `= false` (the default) is a hard master switch -- [delayMillisFor] and
     * [shouldDrop] both short-circuit to "no effect" regardless of the other fields' values, so a
     * caller never needs its own separate `if (enabled)` guard.
     */
    data class NetworkCondition(
        val enabled: Boolean = false,
        val latencyMs: Int = 0,
        val jitterMs: Int = 0,
        val packetLossPercent: Int = 0,
    ) {
        init {
            require(latencyMs >= 0) { "latencyMs must not be negative" }
            require(jitterMs >= 0) { "jitterMs must not be negative" }
            require(packetLossPercent in 0..100) { "packetLossPercent must be within [0, 100]" }
        }

        companion object {
            /** No delay, no loss -- applying this to a connection is always a complete no-op. */
            val NONE = NetworkCondition()
        }
    }

    /**
     * The number of milliseconds a caller should delay before proceeding, given [condition]:
     * uniformly distributed over `[latencyMs - jitterMs, latencyMs + jitterMs]`, with the lower
     * bound raised to `0` since no delay can be negative. `0` when [NetworkCondition.enabled] is
     * false or [NetworkCondition.latencyMs] and [NetworkCondition.jitterMs] are both `0`.
     *
     * The range is narrowed rather than sampled-then-clamped so that a `jitterMs` larger than
     * `latencyMs` (e.g. 50ms latency, 500ms jitter -- a legal, and realistic, "mostly fast but
     * occasionally awful" link) still spreads across the whole reachable range instead of piling
     * a large share of its draws up on exactly `0`.
     */
    fun delayMillisFor(condition: NetworkCondition, random: Random = Random.Default): Long {
        if (!condition.enabled) return 0L
        if (condition.latencyMs == 0 && condition.jitterMs == 0) return 0L
        if (condition.jitterMs == 0) return condition.latencyMs.toLong()

        // Long math throughout: latencyMs + jitterMs are independently-validated Ints whose sum
        // can overflow Int (the Settings UI clamps both, but NetworkCondition itself only
        // requires non-negative).
        val lowest = (condition.latencyMs.toLong() - condition.jitterMs).coerceAtLeast(0L)
        val highest = condition.latencyMs.toLong() + condition.jitterMs
        return random.nextLong(lowest, highest + 1)
    }

    /**
     * Whether this connection attempt should be simulated as lost, given [condition]. Always
     * `false` when [NetworkCondition.enabled] is false or [NetworkCondition.packetLossPercent]
     * is `0` -- [random] is never even consulted in that case, so a disabled/zero-loss condition
     * has zero effect on RNG state (matters for deterministic tests that also check
     * [delayMillisFor] against the same [random] instance).
     */
    fun shouldDrop(condition: NetworkCondition, random: Random = Random.Default): Boolean {
        if (!condition.enabled || condition.packetLossPercent <= 0) return false
        if (condition.packetLossPercent >= 100) return true
        return random.nextInt(100) < condition.packetLossPercent
    }

    /** Thrown by [HttpProxyShared] call sites when [shouldDrop] simulates a lost connection. */
    class SimulatedPacketLossException :
        java.io.IOException("Simulated packet loss (NetworkConditionInjector)")

    /**
     * Convenience wrapper a caller applies right before dialing/forwarding: sleeps for
     * [delayMillisFor] (via the injectable [sleep], defaulting to a real [Thread.sleep] --
     * tests always pass a no-op/recording lambda instead so no test ever really blocks), then
     * throws [SimulatedPacketLossException] if [shouldDrop] fires. Delay is always applied before
     * the drop check, matching a real degraded link (packets that end up dropped were still
     * "in flight" for some amount of time first).
     */
    fun apply(
        condition: NetworkCondition,
        random: Random = Random.Default,
        sleep: (Long) -> Unit = Thread::sleep,
    ) {
        val delay = delayMillisFor(condition, random)
        if (delay > 0) sleep(delay)
        if (shouldDrop(condition, random)) throw SimulatedPacketLossException()
    }
}
