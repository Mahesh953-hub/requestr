package com.example.interception.rules

import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RuleFolderEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * S4 task 34 -- the `schemaVersion: 2` rule envelope shared by `LocalApiServer` (`/api/rules`),
 * `LanDashboardServer` (`/dash/rules`) and `WorkspaceExporter` (`interceptorRules` /
 * `ruleFolders`). One codec, three consumers.
 *
 * ### Emitted shape (always v2)
 * ```
 * { "schemaVersion": 2,
 *   "rules": [ { "schemaVersion": 2, "id", "name", "stage", "enabled", "sortOrder", "folderId",
 *                "matcher": {...RuleMatcherSpec...}, "config": {"stage": ..., ...RuleConfig...},
 *                "createdAt", "updatedAt", "legacyRuleId",
 *                // v1 compatibility projection (LegacyRuleProjection) for readers that only
 *                // know the flat shape -- the browser extension and the LAN dashboard page:
 *                "pattern", "domain", "action", "replacementValue" } ],
 *   "folders": [ { "id", "name", "sortOrder", "enabled", "collapsed", "createdAt" } ] }
 * ```
 *
 * ### Accepted input
 * [parseNewRule] / [applyUpdate] accept a typed body (`stage` + `matcher` + `config`, `schemaVersion`
 * 2 or absent) **or** a v1 flat body (`pattern`/`domain`/`action`/`replacementValue`, detected by
 * [LegacyInterceptRule.isLegacyShape]) which is routed through [LegacyRuleConverter]. Every
 * accepted rule is run through [RuleValidation.validate]; problems are reported, never persisted.
 */
object RuleApiJson {

    const val SCHEMA_VERSION = 2

    sealed interface Parsed {
        data class Ok(val rule: InterceptorRule, val warnings: List<String> = emptyList()) : Parsed
        data class Invalid(val message: String) : Parsed
    }

    // ── emit ──────────────────────────────────────────────────────────────────────────────────

    fun envelope(rules: List<InterceptorRuleEntity>, folders: List<RuleFolderEntity> = emptyList()): JSONObject =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("rules", JSONArray().also { arr -> rules.forEach { arr.put(ruleToJson(it)) } })
            .put("folders", JSONArray().also { arr -> folders.forEach { arr.put(folderToJson(it)) } })

    fun ruleToJson(entity: InterceptorRuleEntity): JSONObject {
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("id", entity.id)
            .put("name", entity.name)
            .put("stage", entity.stage.name)
            .put("enabled", entity.enabled)
            .put("sortOrder", entity.sortOrder)
            .put("folderId", entity.folderId ?: JSONObject.NULL)
            .put("matcher", JSONObject(entity.matcherJson))
            .put("config", JSONObject(entity.configJson))
            .put("createdAt", entity.createdAt)
            .put("updatedAt", entity.updatedAt)
            .put("legacyRuleId", entity.legacyRuleId ?: JSONObject.NULL)
        val rule = runCatching { entity.toDomain() }.getOrNull()
        if (rule != null) {
            val p = LegacyRuleProjection.project(rule)
            json.put("pattern", p.pattern)
                .put("domain", p.domain)
                .put("action", p.action?.name ?: entity.stage.name)
                .put("replacementValue", p.replacementValue ?: JSONObject.NULL)
        } else {
            json.put("pattern", "").put("domain", "").put("action", entity.stage.name).put("replacementValue", JSONObject.NULL)
        }
        return json
    }

    fun ruleToJson(rule: InterceptorRule): JSONObject = ruleToJson(InterceptorRuleEntity.fromDomain(rule))

    fun folderToJson(folder: RuleFolderEntity): JSONObject = JSONObject()
        .put("id", folder.id)
        .put("name", folder.name)
        .put("sortOrder", folder.sortOrder)
        .put("enabled", folder.enabled)
        .put("collapsed", folder.collapsed)
        .put("createdAt", folder.createdAt)

    fun folderFromJson(json: JSONObject, nowMs: Long = System.currentTimeMillis()): RuleFolderEntity = RuleFolderEntity(
        id = 0,
        name = json.optString("name", "Folder"),
        sortOrder = json.optInt("sortOrder", 0),
        enabled = json.optBoolean("enabled", true),
        collapsed = json.optBoolean("collapsed", false),
        createdAt = if (json.has("createdAt")) json.optLong("createdAt", nowMs) else nowMs,
    )

    // ── accept ────────────────────────────────────────────────────────────────────────────────

    /**
     * Parses a create body. [sortOrder] is where the new rule lands (callers pass
     * `dao.maxSortOrder() + 1`); [enabledRules] and [acknowledged] feed [RuleValidation.validate].
     */
    fun parseNewRule(
        json: JSONObject,
        sortOrder: Int,
        nowMs: Long = System.currentTimeMillis(),
        enabledRules: Int = 0,
        acknowledged: Boolean = true,
    ): Parsed {
        val (rule, warnings) = when {
            LegacyInterceptRule.isLegacyShape(json) -> {
                val legacy = LegacyInterceptRule.fromJson(json, nowMs)
                when (val r = LegacyRuleConverter.convert(legacy, sortOrder)) {
                    is LegacyRuleConverter.ConversionResult.Converted -> r.rule.copy(legacyRuleId = null) to r.warnings
                    is LegacyRuleConverter.ConversionResult.Unconvertible -> return Parsed.Invalid(r.reason)
                }
            }
            else -> try {
                typedRule(json, existing = null, sortOrder = sortOrder, nowMs = nowMs) to emptyList()
            } catch (t: Throwable) {
                return Parsed.Invalid(t.message ?: "malformed rule")
            }
        }
        return validated(rule, warnings, enabledRules, acknowledged)
    }

    /**
     * Partial update: every field present in [json] overwrites [existing]; anything omitted keeps
     * its stored value (so `{"enabled": false}` alone is a valid toggle). A v1 flat body updates
     * the rule by converting the merged flat view and keeping id / sortOrder / folder / createdAt.
     */
    fun applyUpdate(
        existing: InterceptorRule,
        json: JSONObject,
        nowMs: Long = System.currentTimeMillis(),
        enabledRules: Int = 0,
        acknowledged: Boolean = true,
    ): Parsed {
        val (rule, warnings) = when {
            LegacyInterceptRule.isLegacyShape(json) -> {
                val projection = LegacyRuleProjection.project(existing)
                val base = LegacyInterceptRule(
                    id = existing.id,
                    name = existing.name,
                    pattern = projection.pattern,
                    domain = projection.domain,
                    action = projection.action ?: LegacyRuleAction.LOG_ONLY,
                    enabled = existing.enabled,
                    replacementValue = projection.replacementValue,
                    createdAt = existing.createdAt,
                )
                val merged = base.copy(
                    name = if (json.has("name")) json.optString("name", base.name) else base.name,
                    pattern = if (json.has("pattern")) json.optString("pattern", base.pattern) else base.pattern,
                    domain = if (json.has("domain")) json.optString("domain", base.domain) else base.domain,
                    action = if (json.has("action")) LegacyRuleAction.fromNameOrNull(json.optString("action")) ?: base.action else base.action,
                    enabled = if (json.has("enabled")) json.optBoolean("enabled", base.enabled) else base.enabled,
                    replacementValue = if (json.has("replacementValue")) {
                        if (json.isNull("replacementValue")) null else json.getString("replacementValue").takeIf { it.isNotBlank() }
                    } else base.replacementValue,
                )
                when (val r = LegacyRuleConverter.convert(merged, existing.sortOrder)) {
                    is LegacyRuleConverter.ConversionResult.Converted -> r.rule.copy(
                        id = existing.id,
                        folderId = existing.folderId,
                        createdAt = existing.createdAt,
                        updatedAt = nowMs,
                        legacyRuleId = existing.legacyRuleId,
                    ) to r.warnings
                    is LegacyRuleConverter.ConversionResult.Unconvertible -> return Parsed.Invalid(r.reason)
                }
            }
            else -> try {
                typedRule(json, existing = existing, sortOrder = existing.sortOrder, nowMs = nowMs) to emptyList()
            } catch (t: Throwable) {
                return Parsed.Invalid(t.message ?: "malformed rule")
            }
        }
        return validated(rule, warnings, enabledRules, acknowledged)
    }

    private fun typedRule(json: JSONObject, existing: InterceptorRule?, sortOrder: Int, nowMs: Long): InterceptorRule {
        val stageName = json.optString("stage", existing?.stage?.name ?: "")
        val stage = if (stageName.isBlank()) {
            existing?.stage ?: throw IllegalArgumentException("stage is required")
        } else {
            RuleStage.entries.firstOrNull { it.name.equals(stageName, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown stage '$stageName'")
        }
        val matcher = json.optJSONObject("matcher")?.let { RuleConfigJson.decodeMatcher(it.toString()) }
            ?: existing?.matcher
            ?: RuleMatcherSpec.MATCH_ALL
        val config: RuleConfig = json.optJSONObject("config")?.let { raw ->
            if (!raw.has("stage")) raw.put("stage", stage.name)
            RuleConfigJson.decodeConfig(raw.toString())
        } ?: existing?.config?.takeIf { it.stage == stage }
            ?: if (stage == RuleStage.OBSERVE) ObserveConfig else throw IllegalArgumentException("config is required for stage $stage")
        if (config.stage != stage) throw IllegalArgumentException("config is for stage ${config.stage} but rule stage is $stage")
        val folderId: Long? = when {
            json.has("folderId") -> if (json.isNull("folderId")) null else json.optLong("folderId")
            else -> existing?.folderId
        }
        return InterceptorRule(
            id = existing?.id ?: 0,
            name = if (json.has("name")) json.optString("name", existing?.name ?: "") else existing?.name ?: "",
            stage = stage,
            enabled = if (json.has("enabled")) json.optBoolean("enabled", existing?.enabled ?: true) else existing?.enabled ?: true,
            sortOrder = if (json.has("sortOrder")) json.optInt("sortOrder", sortOrder) else sortOrder,
            folderId = folderId,
            matcher = matcher,
            config = config,
            createdAt = existing?.createdAt ?: (if (json.has("createdAt")) json.optLong("createdAt", nowMs) else nowMs),
            updatedAt = nowMs,
            legacyRuleId = existing?.legacyRuleId,
        )
    }

    private fun validated(rule: InterceptorRule, warnings: List<String>, enabledRules: Int, acknowledged: Boolean): Parsed {
        val problems = RuleValidation.validate(rule, enabledRules = enabledRules, acknowledged = acknowledged)
        if (problems.isNotEmpty()) {
            return Parsed.Invalid(problems.joinToString("; ") { "${it.field}: ${it.message}" })
        }
        return Parsed.Ok(rule, warnings)
    }
}
