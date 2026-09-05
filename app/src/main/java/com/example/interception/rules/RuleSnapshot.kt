package com.example.interception.rules

import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RuleFolderEntity

/**
 * S4 task 25 -- one enabled rule in its executable form. Immutable; shared across every flow
 * evaluated against the snapshot that owns it.
 */
class CompiledRule(
    val id: Long,
    val name: String,
    val stage: RuleStage,
    val sortOrder: Int,
    val folderId: Long?,
    val config: RuleConfig,
    val matcher: CompiledMatcher,
    val legacyRuleId: Long?,
) {
    /** Stable, allocation-free identity for audit rows and decisions. */
    val ref: RuleRef = RuleRef(id, name, stage)

    fun matches(key: FlowKey): Boolean = matcher.matches(key)

    override fun toString(): String = "CompiledRule(#$id '$name' $stage @$sortOrder)"
}

/** What a decision or an audit row keeps of a rule: enough to name it, never the config. */
data class RuleRef(val id: Long, val name: String, val stage: RuleStage)

/**
 * The immutable, fully-compiled view of the enabled rule library at one [revision]. Relay threads
 * read the snapshot published by [RuleSnapshotStore.current] and never touch the database.
 *
 * [rules] is the whole enabled library in evaluation order (`sortOrder ASC, id ASC`, folder
 * membership already applied); [byStage] is the same rules bucketed by [RuleStage] in the same
 * relative order, so the pipeline (task 26) walks one short list per slot.
 *
 * [issues] lists compile-time problems that did not prevent publication -- an invalid regex or
 * CIDR in a matcher (the affected predicate is always-false; see [CompiledMatcher]), or a rule
 * dropped because it belongs to a folder that no longer exists. They are surfaced by the rule
 * library UI; a snapshot with issues is still authoritative.
 */
class RuleSnapshot(
    val revision: Long,
    val rules: List<CompiledRule>,
    val issues: List<SnapshotIssue>,
    val compiledAtMs: Long,
    val compileDurationNanos: Long,
) {
    val byStage: Map<RuleStage, List<CompiledRule>>

    /**
     * S4 task 26: every enabled `GatewayMode.ALLOW_ONLY` rule. Non-empty flips the gateway
     * default for flows matching none of them to an implied block ([GatewayOutcome.ImpliedBlock]);
     * precomputed here so [RulePipeline.evaluate] never rescans the stage on the hot path.
     */
    val allowOnlyGatewayRules: List<CompiledRule>

    init {
        val buckets = HashMap<RuleStage, ArrayList<CompiledRule>>(RuleStage.entries.size)
        for (r in rules) buckets.getOrPut(r.stage) { ArrayList(4) }.add(r)
        byStage = buckets
        allowOnlyGatewayRules = (buckets[RuleStage.GATEWAY] ?: emptyList<CompiledRule>())
            .filter { (it.config as? GatewayConfig)?.mode == GatewayMode.ALLOW_ONLY }
    }

    val isEmpty: Boolean get() = rules.isEmpty()
    val size: Int get() = rules.size

    /** Rules of [stage] in evaluation order; empty list when none. Never allocates for a miss. */
    fun forStage(stage: RuleStage): List<CompiledRule> = byStage[stage] ?: emptyList()

    fun hasStage(stage: RuleStage): Boolean = byStage.containsKey(stage)

    fun findById(id: Long): CompiledRule? {
        for (r in rules) if (r.id == id) return r
        return null
    }

    override fun toString(): String = "RuleSnapshot(rev=$revision, rules=${rules.size}, issues=${issues.size})"

    companion object {
        /** Revision 0, no rules: what every reader sees until the store's first emission. */
        val EMPTY = RuleSnapshot(revision = 0L, rules = emptyList(), issues = emptyList(), compiledAtMs = 0L, compileDurationNanos = 0L)
    }
}

/** A non-fatal compile-time problem attached to a [RuleSnapshot]. */
data class SnapshotIssue(val ruleId: Long, val ruleName: String, val message: String)

/**
 * Turns the DAO's enabled-and-ordered rows plus the folder table into a [RuleSnapshot]. Pure and
 * synchronous; called by [RuleSnapshotStore] on its collector and directly by tests.
 *
 * Folder semantics: a rule whose `folderId` names a disabled folder is excluded; a rule whose
 * `folderId` names a folder that does not exist is excluded **and** reported (the DAO has no FK
 * on `folderId`, by design, so an import can reference a folder before it is created).
 */
object RuleSnapshotCompiler {

    fun compile(
        rules: List<InterceptorRuleEntity>,
        folders: List<RuleFolderEntity>,
        revision: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): RuleSnapshot {
        val started = System.nanoTime()
        val folderEnabled = HashMap<Long, Boolean>(folders.size)
        for (f in folders) folderEnabled[f.id] = f.enabled

        val compiled = ArrayList<CompiledRule>(rules.size)
        val issues = ArrayList<SnapshotIssue>(0)
        for (e in rules) {
            if (!e.enabled) continue
            val fid = e.folderId
            if (fid != null) {
                val enabled = folderEnabled[fid]
                if (enabled == null) {
                    issues += SnapshotIssue(e.id, e.name, "folder #$fid does not exist; rule excluded")
                    continue
                }
                if (!enabled) continue
            }
            val config: RuleConfig = try {
                RuleConfigJson.decodeConfig(e.configJson)
            } catch (t: Throwable) {
                issues += SnapshotIssue(e.id, e.name, "configJson does not decode (${t.message}); rule excluded")
                continue
            }
            val spec: RuleMatcherSpec = try {
                RuleConfigJson.decodeMatcher(e.matcherJson)
            } catch (t: Throwable) {
                issues += SnapshotIssue(e.id, e.name, "matcherJson does not decode (${t.message}); rule excluded")
                continue
            }
            val matcher = CompiledMatcher.compile(spec)
            for (err in matcher.errors) issues += SnapshotIssue(e.id, e.name, err)
            compiled += CompiledRule(
                id = e.id,
                name = e.name,
                // The config's stage is authoritative (RuleEntities.toDomain has the same rule);
                // a stage column written by a newer build has already degraded to OBSERVE.
                stage = config.stage,
                sortOrder = e.sortOrder,
                folderId = fid,
                config = config,
                matcher = matcher,
                legacyRuleId = e.legacyRuleId,
            )
        }
        // The DAO already orders; re-sorting is cheap insurance against a caller handing in an
        // unordered list (tests, imports) and keeps the contract in one place.
        compiled.sortWith(compareBy<CompiledRule> { it.sortOrder }.thenBy { it.id })
        return RuleSnapshot(
            revision = revision,
            rules = compiled,
            issues = issues,
            compiledAtMs = nowMs,
            compileDurationNanos = System.nanoTime() - started,
        )
    }
}
