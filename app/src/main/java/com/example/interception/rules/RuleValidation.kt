package com.example.interception.rules

/**
 * S4 task 33 (D3): what a rule must satisfy before it is saved, imported, or enabled.
 *
 * [validate] is the editor/API/import gate (returns every problem so the UI can show them all);
 * [clampForSnapshot] is the compile-time backstop that brings an over-limit config back inside
 * [RuleLimits] and says what it changed. Both are pure.
 */
object RuleValidation {
    data class Problem(val field: String, val message: String) {
        override fun toString(): String = "$field: $message"
    }

    /** Result of the compile-time backstop: the (possibly adjusted) config plus what was clamped. */
    data class Clamped<C : RuleConfig>(val config: C, val notes: List<String>) {
        val changed: Boolean get() = notes.isNotEmpty()
    }

    /**
     * @param enabledRules how many rules are currently enabled *excluding* [rule] itself.
     * @param acknowledged the one-time developer-mode acknowledgement from `SettingsDataStore`.
     */
    fun validate(rule: InterceptorRule, enabledRules: Int = 0, acknowledged: Boolean = true): List<Problem> {
        val problems = ArrayList<Problem>(0)
        if (rule.name.isBlank()) problems += Problem("name", "must not be blank")
        if (rule.enabled && enabledRules >= RuleLimits.MAX_ENABLED_RULES) {
            problems += Problem("enabled", "at most ${RuleLimits.MAX_ENABLED_RULES} rules may be enabled at once")
        }
        if (rule.enabled && rule.stage in RuleStage.MUTATING && !acknowledged) {
            problems += Problem("stage", "${rule.stage} rules modify live traffic; acknowledge developer mode before enabling one")
        }
        val matcher = CompiledMatcher.compile(rule.matcher)
        matcher.errors.forEach { problems += Problem("matcher", it) }
        problems += validateConfig(rule.config)
        return problems
    }

    fun validateConfig(config: RuleConfig): List<Problem> {
        val problems = ArrayList<Problem>(0)
        when (config) {
            is ScriptConfig -> {
                if (config.timeoutMs <= 0) problems += Problem("timeoutMs", "must be positive")
                if (config.timeoutMs > RuleLimits.MAX_SCRIPT_TIMEOUT_MS) problems += Problem("timeoutMs", "script timeout may not exceed ${RuleLimits.MAX_SCRIPT_TIMEOUT_MS} ms")
                if (config.maxInstructions <= 0) problems += Problem("maxInstructions", "must be positive")
            }
            is ChaosConfig -> {
                if (config.failRate !in 0.0..1.0) problems += Problem("failRate", "must be within 0..1")
                if (config.corruptBodyRate !in 0.0..1.0) problems += Problem("corruptBodyRate", "must be within 0..1")
                if (config.delayMinMs < 0 || config.delayMaxMs < 0) problems += Problem("delayMs", "must not be negative")
                if (config.delayMaxMs > RuleLimits.MAX_CHAOS_DELAY_MS) problems += Problem("delayMs", "chaos delay may not exceed ${RuleLimits.MAX_CHAOS_DELAY_MS} ms")
                if (config.delayMaxMs < config.delayMinMs) problems += Problem("delayMs", "max must be >= min")
            }
            is BreakpointConfig -> {
                if (config.timeoutMs <= 0) problems += Problem("timeoutMs", "must be positive")
                if (config.timeoutMs > RuleLimits.MAX_BREAKPOINT_TIMEOUT_MS) problems += Problem("timeoutMs", "breakpoint timeout may not exceed ${RuleLimits.MAX_BREAKPOINT_TIMEOUT_MS} ms")
            }
            is ThrottleConfig -> {
                if (config.downlinkBps < 0 || config.uplinkBps < 0) problems += Problem("bps", "must not be negative")
                if (config.lossPercent !in 0..100) problems += Problem("lossPercent", "must be within 0..100")
            }
            is MirrorConfig -> if (config.targetHost.isBlank()) problems += Problem("targetHost", "must not be blank")
            else -> Unit
        }
        return problems
    }

    /** Compile-time backstop: never rejects, always returns a config inside the limits. */
    fun clampForSnapshot(config: RuleConfig): Clamped<RuleConfig> {
        val notes = ArrayList<String>(0)
        val out: RuleConfig = when (config) {
            is ScriptConfig -> {
                var c = config
                if (c.timeoutMs !in 1..RuleLimits.MAX_SCRIPT_TIMEOUT_MS) {
                    val v = c.timeoutMs.coerceIn(1L, RuleLimits.MAX_SCRIPT_TIMEOUT_MS)
                    notes += "script timeoutMs ${c.timeoutMs} clamped to $v"; c = c.copy(timeoutMs = v)
                }
                c
            }
            is ChaosConfig -> {
                var c = config
                val lo = c.delayMinMs.coerceIn(0L, RuleLimits.MAX_CHAOS_DELAY_MS)
                val hi = c.delayMaxMs.coerceIn(lo, RuleLimits.MAX_CHAOS_DELAY_MS)
                if (lo != c.delayMinMs || hi != c.delayMaxMs) {
                    notes += "chaos delay ${c.delayMinMs}..${c.delayMaxMs} clamped to $lo..$hi"; c = c.copy(delayMinMs = lo, delayMaxMs = hi)
                }
                c
            }
            is BreakpointConfig -> {
                if (config.timeoutMs !in 1..RuleLimits.MAX_BREAKPOINT_TIMEOUT_MS) {
                    val v = config.timeoutMs.coerceIn(1L, RuleLimits.MAX_BREAKPOINT_TIMEOUT_MS)
                    notes += "breakpoint timeoutMs ${config.timeoutMs} clamped to $v"; config.copy(timeoutMs = v)
                } else config
            }
            else -> config
        }
        return Clamped(out, notes)
    }
}
