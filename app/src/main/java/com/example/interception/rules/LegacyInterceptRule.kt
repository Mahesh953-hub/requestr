package com.example.interception.rules

import org.json.JSONObject

/**
 * S4 task 34 -- the retired v1 (`schemaVersion: 1`) flat rule shape, kept **only** as an input
 * DTO. Until v24 this was the Room entity `InterceptRuleEntity` backing the `intercept_rules`
 * table; [com.example.data.local.MIGRATION_23_24] drops that table and the entity, the DAO and
 * its type converters are gone. What remains is the wire/file shape that still arrives from:
 *
 *  - v1 workspace exports (`interceptRules: [...]`) imported by `WorkspaceExporter`,
 *  - v1 flat bodies POSTed to `LocalApiServer` / `LanDashboardServer` (`{"name","pattern",
 *    "domain","action","replacementValue",...}`),
 *  - the flat quick-add form in `RulesConfigBottomSheet` until the typed rule editors land
 *    (sub-plan task 16), and
 *  - the v22 -> v23 backfill, which reads legacy rows with raw SQL inside the migration.
 *
 * Every consumer converts through [LegacyRuleConverter] into an [InterceptorRule]; nothing
 * persists or evaluates this shape any more.
 */
enum class LegacyRuleAction {
    BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY, FORCE_MITM, MOCK_RESPONSE, SCRIPT_REWRITE, CHAOS,
    BODY_ENCRYPT_AES,
    BODY_DECRYPT_AES;

    companion object {
        /** The legacy column/JSON parse: unknown or blank names fall back to `LOG_ONLY` (the old entity default). */
        fun fromNameOrLogOnly(name: String?): LegacyRuleAction =
            runCatching { valueOf((name ?: "").trim().uppercase()) }.getOrNull() ?: LOG_ONLY

        /** Strict parse -- null for an unknown name -- for callers that must not silently downgrade. */
        fun fromNameOrNull(name: String?): LegacyRuleAction? =
            runCatching { valueOf((name ?: "").trim().uppercase()) }.getOrNull()
    }
}

/** See [LegacyRuleAction]. Field semantics are exactly those of the retired `intercept_rules` columns. */
data class LegacyInterceptRule(
    val id: Long = 0,
    val name: String = "",
    /** Regex matched (case-insensitively, `containsMatchIn`) against the full request URL. */
    val pattern: String = "",
    /** Bare host fragment, matched as a case-insensitive substring of the host. */
    val domain: String = "",
    val action: LegacyRuleAction = LegacyRuleAction.LOG_ONLY,
    val enabled: Boolean = true,
    /** Action-specific config: a bare URL for REDIRECT, a JSON object for the others, null for none. */
    val replacementValue: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val cryptoKeyAlias: String = "",
    val cryptoAlgorithm: String = "AES/GCM/NoPadding",
    /** `"ENCRYPT"` or `"DECRYPT"`; informational only -- the [action] decides the direction. */
    val cryptoDirection: String = "ENCRYPT",
    /** `"REQUEST"`, `"RESPONSE"`, or `"BOTH"`. */
    val cryptoScope: String = "BOTH",
) {
    /** The v1 JSON shape (`GET /api/rules` before v24, workspace `interceptRules[]`). Nulls are emitted as JSON null. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("pattern", pattern)
        .put("domain", domain)
        .put("action", action.name)
        .put("enabled", enabled)
        .put("replacementValue", replacementValue ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("cryptoKeyAlias", cryptoKeyAlias)
        .put("cryptoAlgorithm", cryptoAlgorithm)
        .put("cryptoDirection", cryptoDirection)
        .put("cryptoScope", cryptoScope)

    companion object {
        /** True when [json] looks like a v1 flat rule rather than a typed (`stage`/`matcher`/`config`) envelope. */
        fun isLegacyShape(json: JSONObject): Boolean =
            !json.has("stage") && !json.has("matcher") && !json.has("config") &&
                (json.has("action") || json.has("pattern") || json.has("domain") || json.has("replacementValue"))

        /**
         * Parses a v1 flat body. Every field is optional and defaults exactly as the old entity
         * did; an unknown `action` becomes `LOG_ONLY`. [nowMs] seeds `createdAt` when absent.
         */
        fun fromJson(json: JSONObject, nowMs: Long = System.currentTimeMillis()): LegacyInterceptRule = LegacyInterceptRule(
            id = json.optLong("id", 0L),
            name = json.optString("name", ""),
            pattern = json.optString("pattern", ""),
            domain = json.optString("domain", ""),
            action = LegacyRuleAction.fromNameOrLogOnly(json.optString("action", "LOG_ONLY")),
            enabled = json.optBoolean("enabled", true),
            replacementValue = if (json.has("replacementValue") && !json.isNull("replacementValue")) {
                json.getString("replacementValue").takeIf { it.isNotBlank() }
            } else null,
            createdAt = if (json.has("createdAt")) json.optLong("createdAt", nowMs) else nowMs,
            cryptoKeyAlias = json.optString("cryptoKeyAlias", ""),
            cryptoAlgorithm = json.optString("cryptoAlgorithm", "AES/GCM/NoPadding"),
            cryptoDirection = json.optString("cryptoDirection", "ENCRYPT"),
            cryptoScope = json.optString("cryptoScope", "BOTH"),
        )
    }
}
