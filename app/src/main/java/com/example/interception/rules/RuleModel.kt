package com.example.interception.rules

/**
 * S4 task 24 (interceptor plan §4 v23, §7 task 24) -- the typed rule library that replaces the flat,
 * first-match-wins `intercept_rules` model.
 *
 * A rule belongs to exactly one [RuleStage]. The staged pipeline (task 26) evaluates a flow once,
 * in the fixed slot order `GATEWAY -> MIRROR -> REWRITE -> BREAKPOINT -> SCRIPT -> MOCK ->
 * BODY_CRYPTO -> THROTTLE -> CHAOS`, plus `OBSERVE` (a pure capture marker that never mutates).
 * Within a stage, rules are ordered by [InterceptorRule.sortOrder] (ties broken by id); the
 * per-slot precedence rules live in `RulePipeline`, not here.
 *
 * The declaration order of [RuleStage] IS the pipeline order -- `ordinal` is used by the
 * pipeline to bucket rules, so do not reorder the constants.
 */
enum class RuleStage {
    /** Access control at connect time: allow-only / bypass / block / suspend / force-decrypt. */
    GATEWAY,
    /** Host A -> host B with independent Host / SNI / CN name policies, applied before TLS. */
    MIRROR,
    /** Redirection -> replacement -> modification on request and response messages. */
    REWRITE,
    /** Pause the flow for interactive edit / resume / abort. */
    BREAKPOINT,
    /** `onRequest` / `onResponse` JavaScript in the isolated script host. */
    SCRIPT,
    /** Answer from a synthesized response; never dials upstream. */
    MOCK,
    /** AES/GCM body encrypt / decrypt in place (fail-closed). */
    BODY_CRYPTO,
    /** Token-bucket bandwidth shaping plus latency / jitter / loss. */
    THROTTLE,
    /** Probabilistic failure / corruption / delay injection. */
    CHAOS,
    /** Capture-only marker. The legacy `LOG_ONLY` action lands here. */
    OBSERVE;

    companion object {
        /**
         * Lenient name lookup: an unknown or null name decodes to [OBSERVE], mirroring the existing
         * "unknown `LegacyRuleAction` decodes to `LOG_ONLY`" contract in `RequestrTypeConverters`.
         * A row written by a newer build (with a stage this build does not know) must never make the
         * snapshot compile fail or make the row unreadable -- it degrades to observe-only.
         */
        fun fromNameOrObserve(name: String?): RuleStage =
            name?.let { n -> entries.firstOrNull { it.name == n } } ?: OBSERVE

        /** Stages whose successful application changes what reaches the client or the origin (D3 safety gate, task 33). */
        val MUTATING: Set<RuleStage> = setOf(REWRITE, BREAKPOINT, SCRIPT, MOCK, BODY_CRYPTO, CHAOS)
    }
}

/**
 * One typed rule. Persisted as `interceptor_rules` (see `data/local/RuleEntities.kt`); this is the
 * decoded in-memory form used by the editors, the converter and the snapshot compiler.
 *
 * @property sortOrder Evaluation order within the whole library (ascending). Ties broken by [id].
 * @property folderId Optional [RuleFolder.id]; a disabled folder disables every rule inside it.
 * @property legacyRuleId The `intercept_rules.id` this rule was backfilled from by v23, or null for
 *   a rule created in the typed model. Kept so v24 (task 34) and the parity suite (G16) can trace
 *   every converted row back to its source.
 */
data class InterceptorRule(
    val id: Long = 0,
    val name: String,
    val stage: RuleStage,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val folderId: Long? = null,
    val matcher: RuleMatcherSpec,
    val config: RuleConfig,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val legacyRuleId: Long? = null,
) {
    init {
        require(config.stage == stage) {
            "rule '$name': config is for stage ${config.stage} but rule stage is $stage"
        }
    }
}

/** A folder in the rule library. Folders order among themselves by [sortOrder]; disabling one disables its rules. */
data class RuleFolder(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val collapsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
