package com.example.interception

import kotlin.random.Random

/**
 * Master plan §3 LOW-ADD — chaos/fault injection.
 *
 * Reference repos (`easy-api`, some `proxypin` rule types) let a rule randomly fail/delay/corrupt
 * traffic to exercise a client's error handling. This object is the pure, side-effect-free
 * decision half: given a [ChaosConfig], [decide] returns what a caller *should* do, and
 * [corrupt]/[corruptBytes] are the pure body transforms for the [ChaosOutcome.CorruptBody] case.
 *
 * Live wiring (the half that actually does it): a [com.example.data.local.RuleAction.CHAOS] rule's
 * `replacementValue` (`{"failRate":0.1,"delayMs":[100,3000],"corruptBodyRate":0.05,...}`) is
 * parsed by [RuleEnforcer.parseChaosConfig] and dispatched by
 * [com.example.interception.MitmProxyEngine] on the TLS-MITM path in two legs --
 * [RuleEnforcer.applyChaosToRequest] (fail: answer the client with a synthetic error and never
 * dial upstream) before the upstream connection is opened, and [RuleEnforcer.applyChaosToResponse]
 * (corrupt body / delay) on the real upstream response. Every injected outcome is stamped with
 * [RuleEnforcer.CHAOS_ATTRIBUTION_HEADER] so the client and the captured row can tell it apart
 * from a genuine server failure. The plain-TCP/tun path (`CaptureEngine`) and the CDP path
 * (`CdpFetchAdapter`) do not run chaos -- see their respective CHAOS-branch comments.
 */
object ChaosInjector {

    /** `failRate`/`corruptBodyRate` are probabilities in `[0.0, 1.0]`; `delayMsRange` is inclusive. */
    data class ChaosConfig(
        val failRate: Double = 0.0,
        val delayMsRange: LongRange = 0L..0L,
        val corruptBodyRate: Double = 0.0,
    ) {
        init {
            require(failRate in 0.0..1.0) { "failRate must be within [0.0, 1.0]" }
            require(corruptBodyRate in 0.0..1.0) { "corruptBodyRate must be within [0.0, 1.0]" }
            require(delayMsRange.first >= 0) { "delayMsRange must not be negative" }
        }
    }

    sealed interface ChaosOutcome {
        data object PassThrough : ChaosOutcome
        data object Fail : ChaosOutcome
        data class Delay(val millis: Long) : ChaosOutcome
        data object CorruptBody : ChaosOutcome
    }

    /**
     * Rolls the dice once per call against [config] using [random] (injectable for deterministic
     * tests). Precedence when multiple triggers roll true in the same call: fail > corrupt >
     * delay > pass-through -- failing the request outright is the most disruptive outcome and
     * makes the others moot, so it's checked first.
     */
    fun decide(config: ChaosConfig, random: Random = Random.Default): ChaosOutcome {
        if (config.failRate > 0.0 && random.nextDouble() < config.failRate) {
            return ChaosOutcome.Fail
        }
        if (config.corruptBodyRate > 0.0 && random.nextDouble() < config.corruptBodyRate) {
            return ChaosOutcome.CorruptBody
        }
        if (config.delayMsRange.last > 0) {
            val delay = if (config.delayMsRange.first == config.delayMsRange.last) {
                config.delayMsRange.first
            } else {
                random.nextLong(config.delayMsRange.first, config.delayMsRange.last + 1)
            }
            if (delay > 0) return ChaosOutcome.Delay(delay)
        }
        return ChaosOutcome.PassThrough
    }

    /** Pure body-corruption transform a caller can apply when [decide] returns [ChaosOutcome.CorruptBody]. */
    fun corrupt(body: String, random: Random = Random.Default): String {
        if (body.isEmpty()) return body
        val chars = body.toCharArray()
        val flips = (chars.size / 20).coerceAtLeast(1)
        repeat(flips) {
            val idx = random.nextInt(chars.size)
            chars[idx] = ((chars[idx].code xor 0xFF) and 0x7F).toChar()
        }
        return String(chars)
    }

    /**
     * Byte-level twin of [corrupt] for binary bodies (images, protobuf, anything
     * [com.example.interception.http.ParsedHttpMessage.isBodyBinary]), where corrupting the lossy
     * text rendering would replace the payload with garbage-of-the-placeholder instead of
     * damaging the real bytes. Flips roughly 5% of bytes (at least one) in a copy; the input is
     * never mutated. Empty input is returned as-is (a fresh empty array).
     */
    fun corruptBytes(body: ByteArray, random: Random = Random.Default): ByteArray {
        if (body.isEmpty()) return ByteArray(0)
        val out = body.copyOf()
        val flips = (out.size / 20).coerceAtLeast(1)
        repeat(flips) {
            val idx = random.nextInt(out.size)
            out[idx] = (out[idx].toInt() xor 0xFF).toByte()
        }
        return out
    }
}
