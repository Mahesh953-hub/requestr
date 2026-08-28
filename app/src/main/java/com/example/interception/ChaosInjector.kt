package com.example.interception

import kotlin.random.Random

/**
 * Master plan §3 LOW-ADD — chaos/fault injection.
 *
 * Reference repos (`easy-api`, some `proxypin` rule types) let a rule randomly fail/delay/corrupt
 * traffic to exercise a client's error handling. This is deliberately **not** a new
 * [com.example.data.local.RuleAction] value (that enum is consumed by exhaustive `when` blocks in
 * `CaptureEngine.kt`/`CdpFetchAdapter.kt`/`MitmProxyEngine.kt`, all out of scope here) and not
 * wired into [RuleEnforcer] ([RuleEnforcer.kt] itself is also out of scope). Instead this is a
 * fully standalone, side-effect-free decision function: given a [ChaosConfig], [decide] returns
 * what a caller *should* do, and the caller (a future edit to `RuleEnforcer`/`MitmProxyEngine`)
 * is responsible for actually doing it (dropping the connection, sleeping, or corrupting bytes).
 *
 * Exact integration point once `RuleEnforcer.kt` is back in scope: add a `CHAOS` schema field to
 * `applyResponseModification`'s existing `{"statusCode",...,"bodyReplace"}` JSON config (e.g.
 * `"chaos": {"failRate": 0.1, "delayMs": [100, 3000], "corruptBodyRate": 0.05}`), parse it into a
 * [ChaosConfig], call [decide], and act on the returned [ChaosOutcome] right where the response
 * modification would otherwise be applied.
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
}
