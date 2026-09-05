package com.example.interception.rules

import com.example.data.local.AppliedRuleEntity

/**
 * S4 task 26: per-flow provenance -- what each rule did (or did not do) to one exchange.
 *
 * One trail is created per flow (CONNECT, or the request leg of an HTTP exchange), the stage
 * appliers call [record] as they act, and the engine hands [toEntities] to the capture sink
 * ([com.example.data.persistence.CaptureSink.appliedRules]) once the row exists. Rows land in
 * `applied_rules` (v19 table) and feed the traffic list's rule badges and the detail sheet's
 * provenance list. Nothing here touches Room directly.
 *
 * Thread-safety: the request and response legs of one exchange run on different relay threads,
 * so [record] and [toRecords] synchronise on the trail.
 */
class FlowAuditTrail(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Phase { CONNECT, REQUEST, RESPONSE }

    enum class Outcome {
        /** The rule matched; a later record says what happened. Written for precedence losers too. */
        MATCHED,
        /** The stage acted on the flow. */
        APPLIED,
        /** Matched, but another rule in the same slot took precedence. */
        SKIPPED_PRECEDENCE,
        /** Matched, but the safety gate (D3) refused to let a mutating stage act. */
        REFUSED_SAFETY,
        /** The stage tried to act and failed (crypto error, script error, fixture missing, ...). */
        FAILED,
    }

    data class Record(val rule: RuleRef, val phase: Phase, val outcome: Outcome, val detail: String?, val atMs: Long) {
        fun toEntity(trafficId: Long): AppliedRuleEntity = AppliedRuleEntity(
            trafficId = trafficId,
            ruleId = rule.id.toString(),
            ruleName = rule.name,
            stage = rule.stage.name,
            phase = phase.name,
            outcome = outcome.name,
            detail = detail,
            appliedAt = atMs,
        )
    }

    private val records = ArrayList<Record>(4)

    fun record(rule: RuleRef, phase: Phase, outcome: Outcome, detail: String? = null) {
        val row = Record(rule, phase, outcome, detail, clock())
        synchronized(records) { records.add(row) }
    }

    fun applied(rule: RuleRef, phase: Phase, detail: String? = null) = record(rule, phase, Outcome.APPLIED, detail)
    fun failed(rule: RuleRef, phase: Phase, detail: String?) = record(rule, phase, Outcome.FAILED, detail)
    fun refused(rule: RuleRef, phase: Phase, detail: String?) = record(rule, phase, Outcome.REFUSED_SAFETY, detail)

    /** Records every precedence loser of [decision] once, plus a MATCHED row for observe rules. */
    fun recordDecision(decision: RuleDecision, phase: Phase) {
        for (s in decision.skipped) record(s.rule, phase, Outcome.SKIPPED_PRECEDENCE, s.reason)
        for (o in decision.observe) record(o, phase, Outcome.MATCHED, "observe")
    }

    val isEmpty: Boolean get() = synchronized(records) { records.isEmpty() }
    val size: Int get() = synchronized(records) { records.size }

    fun toRecords(): List<Record> = synchronized(records) { ArrayList(records) }

    /** Rows for the persistence queue; `trafficId` is patched to the real id at write time. */
    fun toEntities(trafficId: Long = 0L): List<AppliedRuleEntity> = toRecords().map { it.toEntity(trafficId) }

    override fun toString(): String = "FlowAuditTrail(${toRecords().joinToString { "${it.rule.stage}:${it.outcome}" }})"
}
