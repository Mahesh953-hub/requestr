package com.example.interception.rules

/**
 * S4 task 33 (D3): the hard caps of the rule engine. Enforced twice -- in [RuleValidation] when a
 * rule is saved or imported, and again in [RuleSnapshotCompiler] so a hand-edited import or a
 * direct DB write cannot exceed them either.
 */
object RuleLimits {
    /** Rules that may be enabled at once; the 201st is rejected by validation and excluded by the compiler. */
    const val MAX_ENABLED_RULES = 200

    /** Concurrently paused flows across all BREAKPOINT rules (the registry's semaphore size). */
    const val MAX_PAUSED_FLOWS = 8

    /** Replay connections in flight (S5 replay queue reads this). */
    const val MAX_REPLAY_CONCURRENCY = 255

    /** Per-call script deadline ceiling; the host client caps its own deadline at this too. */
    const val MAX_SCRIPT_TIMEOUT_MS = 10_000L

    /** Longest artificial chaos delay -- the legacy `RuleEnforcer.MAX_CHAOS_DELAY_MS` value. */
    const val MAX_CHAOS_DELAY_MS = 30_000L

    /** Longest a breakpoint may hold a flow before mandatory auto-resume. */
    const val MAX_BREAKPOINT_TIMEOUT_MS = 10 * 60_000L
}
