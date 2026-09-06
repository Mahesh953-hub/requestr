package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.RuleConfigJson
import com.example.interception.rules.RuleFolder
import com.example.interception.rules.RuleStage

/*
 * S4 task 24 (interceptor plan §4 v23) -- the five typed-rule tables. Every `CREATE TABLE` in
 * Migration22To23.kt mirrors these entities exactly (same columns, same NOT NULL-ness, same primary
 * key, same indices, same foreign keys) or Room's schema validation fails on reopen -- the same
 * discipline as MIGRATION_17_18 / Migration18To19.
 *
 * `intercept_rules` (InterceptRuleEntity) is NOT touched by v23: it stays read-only until v24
 * (task 34) drops it once the parity gate G16 is green.
 */

/**
 * One typed rule (`interception/rules/RuleModel.kt` [InterceptorRule], stored form).
 *
 * `matcherJson` / `configJson` are the Moshi encodings from [RuleConfigJson]; `stage` is stored
 * redundantly as its own indexed column so the snapshot compiler and the "is there a BLOCK rule
 * for this host" existence checks can filter without decoding every config.
 *
 * `legacyRuleId` links a v23-backfilled row to the `intercept_rules.id` it came from (null for a
 * rule created in the typed model); kept until v24 for the parity suite and for import de-dup.
 */
@Entity(
    tableName = "interceptor_rules",
    indices = [Index("stage"), Index("enabled"), Index("sortOrder"), Index("folderId"), Index("legacyRuleId")],
)
data class InterceptorRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [RuleStage] name; unknown names decode to OBSERVE (RequestrTypeConverters). */
    val stage: RuleStage,
    val enabled: Boolean = true,
    /** Evaluation order across the whole library, ascending; ties by id. */
    val sortOrder: Int = 0,
    val folderId: Long? = null,
    val matcherJson: String,
    val configJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val legacyRuleId: Long? = null,
) {
    /** Decodes to the domain model. Throws on a malformed body (see [RuleConfigJson] for the contract). */
    fun toDomain(): InterceptorRule {
        val config = RuleConfigJson.decodeConfig(configJson)
        return InterceptorRule(
            id = id,
            name = name,
            // The config's own stage is authoritative: an unknown stored stage decodes to OBSERVE
            // and the config decodes to ObserveConfig, so the pair stays consistent.
            stage = config.stage,
            enabled = enabled,
            sortOrder = sortOrder,
            folderId = folderId,
            matcher = RuleConfigJson.decodeMatcher(matcherJson),
            config = config,
            createdAt = createdAt,
            updatedAt = updatedAt,
            legacyRuleId = legacyRuleId,
        )
    }

    companion object {
        fun fromDomain(rule: InterceptorRule): InterceptorRuleEntity = InterceptorRuleEntity(
            id = rule.id,
            name = rule.name,
            stage = rule.stage,
            enabled = rule.enabled,
            sortOrder = rule.sortOrder,
            folderId = rule.folderId,
            matcherJson = RuleConfigJson.encodeMatcher(rule.matcher),
            configJson = RuleConfigJson.encodeConfig(rule.config),
            createdAt = rule.createdAt,
            updatedAt = rule.updatedAt,
            legacyRuleId = rule.legacyRuleId,
        )
    }
}

/** A rule-library folder ([RuleFolder], stored form). Disabling a folder disables its rules at snapshot compile time. */
@Entity(tableName = "rule_folders", indices = [Index("sortOrder")])
data class RuleFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val collapsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toDomain() = RuleFolder(id, name, sortOrder, enabled, collapsed, createdAt)

    companion object {
        fun fromDomain(f: RuleFolder) = RuleFolderEntity(f.id, f.name, f.sortOrder, f.enabled, f.collapsed, f.createdAt)
    }
}

/**
 * A flow currently parked by the breakpoint engine (task 31). Persisted so the queue survives
 * process death and the breakpoint workspace renders from Room rather than in-memory state. The
 * engine owns the lifecycle: a row is inserted when the pause is granted and deleted on resume /
 * abort / timeout; rows found at startup are stale and are reported then swept.
 *
 * `requestSnapshot` / `responseSnapshot` are the serialised `ParsedHttpMessage` heads plus a
 * bounded body preview (the full body stays in the relay thread's memory); `state` is
 * `PAUSED` | `EDITING` | `RESUMING`.
 */
@Entity(tableName = "breakpoint_paused_flows", indices = [Index("pausedAt"), Index("ruleId")])
data class PausedFlowEntity(
    @PrimaryKey val flowId: String,
    val ruleId: Long,
    val ruleName: String,
    /** REQUEST or RESPONSE -- the phase actually paused (a BOTH rule pauses twice, one row at a time). */
    val phase: String,
    val host: String,
    val url: String,
    val method: String? = null,
    val requestSnapshot: String? = null,
    val responseSnapshot: String? = null,
    val pausedAt: Long,
    val timeoutAt: Long,
    val state: String = "PAUSED",
)

/**
 * A replay job (task 39 fills the engine; v23 creates the tables so the ledger is closed in S4).
 * `sourceFlowIds` is a JSON array of `captured_traffic.id`; `status` is
 * `PENDING` | `RUNNING` | `COMPLETED` | `FAILED` | `CANCELLED`.
 */
@Entity(tableName = "replay_jobs", indices = [Index("status"), Index("createdAt")])
data class ReplayJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceFlowIds: String,
    val iterations: Int,
    val intervalMs: Long,
    val jitterMs: Long,
    val concurrency: Int,
    /** Optional `host:port` the replayed requests are sent through instead of the direct origin. */
    val proxyTarget: String? = null,
    val status: String = "PENDING",
    val queuedCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
)

/** One replay attempt. `capturedTrafficId` points at the row the attempt re-entered the capture list as. */
@Entity(
    tableName = "replay_results",
    indices = [Index("jobId"), Index("sourceFlowId")],
    foreignKeys = [
        ForeignKey(
            entity = ReplayJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReplayResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val sourceFlowId: Long,
    val attemptNumber: Int,
    val capturedTrafficId: Long? = null,
    val statusCode: Int? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
    val startedAt: Long,
    val completedAt: Long? = null,
)
