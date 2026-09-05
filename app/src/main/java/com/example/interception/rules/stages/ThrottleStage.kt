package com.example.interception.rules.stages

import com.example.interception.NetworkConditionInjector
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleRef
import com.example.interception.rules.ThrottleConfig
import java.io.OutputStream
import kotlin.random.Random

/**
 * S4 task 30: network-condition presets for the THROTTLE stage. Bandwidth figures are **bits per
 * second** (the unit every network tool quotes), latency/jitter in milliseconds, loss in percent.
 * A preset is a starting point copied into a [ThrottleConfig]; the numbers on the rule remain
 * authoritative so a user may tweak a preset without losing the label.
 */
object NetworkConditionPresets {
    data class Preset(
        val key: String,
        val label: String,
        val downlinkBps: Long,
        val uplinkBps: Long,
        val latencyMs: Int,
        val jitterMs: Int = 0,
        val lossPercent: Int = 0,
    ) {
        fun toConfig(): ThrottleConfig = ThrottleConfig(
            presetKey = key, downlinkBps = downlinkBps, uplinkBps = uplinkBps,
            latencyMs = latencyMs, jitterMs = jitterMs, lossPercent = lossPercent,
        )
    }

    const val CUSTOM_KEY = "custom"

    val GPRS = Preset("gprs", "GPRS", downlinkBps = 50_000, uplinkBps = 20_000, latencyMs = 500, jitterMs = 100, lossPercent = 1)
    val EDGE_2G = Preset("2g", "2G (EDGE)", downlinkBps = 250_000, uplinkBps = 50_000, latencyMs = 300, jitterMs = 60)
    val UMTS_3G = Preset("3g", "3G", downlinkBps = 750_000, uplinkBps = 250_000, latencyMs = 100, jitterMs = 20)
    val LTE_4G = Preset("4g", "4G (LTE)", downlinkBps = 4_000_000, uplinkBps = 3_000_000, latencyMs = 20, jitterMs = 5)
    val NR_5G = Preset("5g", "5G", downlinkBps = 100_000_000, uplinkBps = 50_000_000, latencyMs = 5, jitterMs = 1)

    val ALL: List<Preset> = listOf(GPRS, EDGE_2G, UMTS_3G, LTE_4G, NR_5G)

    fun byKey(key: String?): Preset? = key?.let { k -> ALL.firstOrNull { it.key.equals(k, ignoreCase = true) } }
}

/**
 * S4 task 30: the THROTTLE slot applier -- resolves a rule's [ThrottleConfig] to a per-connection
 * [Shaper] that the relay copy loops wrap their output streams with. Bandwidth is enforced by a
 * [TokenBucket] per direction on the bytes actually written to that peer (downlink = towards the
 * client, uplink = towards the upstream); latency/jitter/loss reuse the existing
 * [NetworkConditionInjector] model on the upstream dial, so a throttled flow behaves like the
 * global weak-network setting plus bandwidth.
 *
 * G7: a 10 MB download under a preset completes within ±20 % of `10 MB * 8 / downlinkBps`
 * ([ThrottleStageTest] drives a virtual clock for every preset and a real-clock check).
 */
object ThrottleStage {
    /** Fully resolved per-flow numbers. `0` bps = unlimited on that direction. */
    data class Shape(
        val downlinkBps: Long,
        val uplinkBps: Long,
        val latencyMs: Int,
        val jitterMs: Int,
        val lossPercent: Int,
        val label: String,
    ) {
        val isNoop: Boolean get() = downlinkBps <= 0 && uplinkBps <= 0 && latencyMs <= 0 && jitterMs <= 0 && lossPercent <= 0
        val shapesBandwidth: Boolean get() = downlinkBps > 0 || uplinkBps > 0

        /** The dial-time part of this shape (delay + simulated loss), or null when it has none. */
        fun networkCondition(): NetworkConditionInjector.NetworkCondition? {
            if (latencyMs <= 0 && jitterMs <= 0 && lossPercent <= 0) return null
            return NetworkConditionInjector.NetworkCondition(
                enabled = true,
                latencyMs = latencyMs.coerceAtLeast(0),
                jitterMs = jitterMs.coerceAtLeast(0),
                packetLossPercent = lossPercent.coerceIn(0, 100),
                downlinkBps = downlinkBps.coerceAtLeast(0),
                uplinkBps = uplinkBps.coerceAtLeast(0),
            )
        }

        fun describe(): String = buildString {
            append(label)
            if (downlinkBps > 0) append(" down=").append(humanBps(downlinkBps))
            if (uplinkBps > 0) append(" up=").append(humanBps(uplinkBps))
            if (latencyMs > 0 || jitterMs > 0) append(" latency=").append(latencyMs).append("ms±").append(jitterMs)
            if (lossPercent > 0) append(" loss=").append(lossPercent).append('%')
        }
    }

    /**
     * Resolves the rule's numbers. They are authoritative; a config that names a preset but
     * carries all-zero numbers (an API client that only sent `presetKey`) falls back to the preset.
     */
    fun resolve(config: ThrottleConfig): Shape {
        val preset = NetworkConditionPresets.byKey(config.presetKey)
        val allZero = config.downlinkBps <= 0 && config.uplinkBps <= 0 && config.latencyMs <= 0 && config.jitterMs <= 0 && config.lossPercent <= 0
        val source = if (allZero && preset != null) preset.toConfig() else config
        return Shape(
            downlinkBps = source.downlinkBps.coerceAtLeast(0),
            uplinkBps = source.uplinkBps.coerceAtLeast(0),
            latencyMs = source.latencyMs.coerceAtLeast(0),
            jitterMs = source.jitterMs.coerceAtLeast(0),
            lossPercent = source.lossPercent.coerceIn(0, 100),
            label = preset?.label ?: (config.presetKey?.takeIf { it.isNotBlank() } ?: NetworkConditionPresets.CUSTOM_KEY),
        )
    }

    /** Bandwidth shaping for a global [NetworkConditionInjector.NetworkCondition] (no rule). */
    fun shaperFor(condition: NetworkConditionInjector.NetworkCondition, clock: () -> Long = System::nanoTime, sleep: (Long) -> Unit = TokenBucket::sleepNanos): Shaper? {
        if (!condition.enabled || (condition.downlinkBps <= 0 && condition.uplinkBps <= 0)) return null
        return Shaper(
            shape = Shape(condition.downlinkBps, condition.uplinkBps, condition.latencyMs, condition.jitterMs, condition.packetLossPercent, "settings"),
            rule = null, clock = clock, sleep = sleep,
        )
    }

    /**
     * Builds the per-connection [Shaper] for a matched rule and records the CONNECT-phase audit
     * line. Returns null when the shape does nothing at all.
     */
    fun shaperFor(
        rule: RuleRef,
        config: ThrottleConfig,
        trail: FlowAuditTrail? = null,
        clock: () -> Long = System::nanoTime,
        sleep: (Long) -> Unit = TokenBucket::sleepNanos,
    ): Shaper? {
        val shape = resolve(config)
        if (shape.isNoop) {
            trail?.record(rule, FlowAuditTrail.Phase.CONNECT, FlowAuditTrail.Outcome.SKIPPED_PRECEDENCE, "throttle is a no-op (all zero)")
            return null
        }
        trail?.applied(rule, FlowAuditTrail.Phase.CONNECT, "throttle ${shape.describe()}")
        return Shaper(shape, rule, clock, sleep)
    }

    /**
     * One connection's pacing state. A single bucket per direction is shared by every writer on
     * that direction (pipelined responses, blind relay, WebSocket frames) so the whole connection
     * -- not each message -- is held to the rate.
     */
    class Shaper internal constructor(
        val shape: Shape,
        val rule: RuleRef?,
        clock: () -> Long,
        sleep: (Long) -> Unit,
    ) {
        val downlink: TokenBucket? = if (shape.downlinkBps > 0) TokenBucket(bytesPerSecond(shape.downlinkBps), clock = clock, sleep = sleep) else null
        val uplink: TokenBucket? = if (shape.uplinkBps > 0) TokenBucket(bytesPerSecond(shape.uplinkBps), clock = clock, sleep = sleep) else null

        /** Wraps the stream that writes towards the client (download direction). */
        fun toClient(out: OutputStream): OutputStream = downlink?.let { ThrottledOutputStream(out, it) } ?: out

        /** Wraps the stream that writes towards the upstream server (upload direction). */
        fun toUpstream(out: OutputStream): OutputStream = uplink?.let { ThrottledOutputStream(out, it) } ?: out

        /**
         * The dial-time condition for the upstream leg: the rule's latency/loss when it has any,
         * else the global setting the caller already read.
         */
        fun networkCondition(global: NetworkConditionInjector.NetworkCondition): NetworkConditionInjector.NetworkCondition =
            shape.networkCondition() ?: global

        /** Convenience for opaque relays: sleep for one latency sample and maybe drop. */
        fun applyDialCondition(global: NetworkConditionInjector.NetworkCondition, random: Random = Random.Default, sleep: (Long) -> Unit = Thread::sleep) {
            NetworkConditionInjector.apply(networkCondition(global), random, sleep)
        }
    }

    /** Bits/s -> bytes/s, floor 1 so a sub-8-bps rule still constructs (and crawls). */
    fun bytesPerSecond(bitsPerSecond: Long): Long = (bitsPerSecond / 8).coerceAtLeast(1L)

    /** Whole-transfer expectation, used by callers/tests: seconds to move [bytes] at [bitsPerSecond]. */
    fun expectedSeconds(bytes: Long, bitsPerSecond: Long): Double = bytes * 8.0 / bitsPerSecond

    private fun humanBps(bps: Long): String = when {
        bps >= 1_000_000 -> "${bps / 1_000_000}Mbps"
        bps >= 1_000 -> "${bps / 1_000}kbps"
        else -> "${bps}bps"
    }
}
