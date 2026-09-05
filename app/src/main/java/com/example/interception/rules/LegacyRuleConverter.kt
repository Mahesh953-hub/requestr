package com.example.interception.rules

import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * S4 task 24 -- the single source of truth for turning a flat `intercept_rules` row into a typed
 * [InterceptorRule]. Used by `Migration22To23` (the v23 backfill), by `WorkspaceExporter` import of
 * a `schemaVersion: 1` file, and by `LocalApiServer` / `LanDashboardServer` when a v1 flat body is
 * POSTed -- one implementation, not three.
 *
 * ### Mapping (every one of the eleven [RuleAction] values lands on exactly one [RuleStage])
 *
 * | legacy action | stage | typed config |
 * |---|---|---|
 * | `BLOCK` | GATEWAY | [GatewayConfig] `BLOCK`, `showBlockedInList = false` (legacy never persisted a blocked flow) |
 * | `FORCE_MITM` | GATEWAY | [GatewayConfig] `FORCE_DECRYPT` |
 * | `LOG_ONLY` | OBSERVE | [ObserveConfig] |
 * | `REDIRECT` | REWRITE | [RewriteConfig] with one [RedirectSpec] (`replacementValue` IS the bare target URL, not JSON) |
 * | `MODIFY_REQUEST` | REWRITE | [RewriteConfig] `requestModifications` = addHeaders → removeHeaders → bodyReplace, in that order |
 * | `MODIFY_RESPONSE` | REWRITE | [RewriteConfig] `responseModifications` = statusCode → addHeaders → bodyReplace |
 * | `MOCK_RESPONSE` | MOCK | [MockConfig] |
 * | `SCRIPT_REWRITE` | SCRIPT | [ScriptConfig] |
 * | `CHAOS` | CHAOS | [ChaosConfig] |
 * | `BODY_ENCRYPT_AES` / `BODY_DECRYPT_AES` | BODY_CRYPTO | [BodyCryptoConfig]; direction from the **action**, never from the informational `cryptoDirection` column |
 *
 * ### Parity rules the converter must honour (gate G16)
 *
 *  - **Matcher.** Legacy matching is `host.contains(domain, ignoreCase) OR Regex(pattern, IGNORE_CASE)
 *    .containsMatchIn(url)`, each side only when non-blank. That is a substring match on the host,
 *    so `domain` becomes the glob `*<domain>*`, `pattern` becomes `urlRegex`, and the two combine
 *    with [MatchCombine.ANY]. A row with both blank never matched and its typed form matches nothing
 *    (see [RuleMatcherSpec]).
 *  - **Order.** The legacy evaluation order is `getEnabledRules()` with no `ORDER BY` -- a rowid
 *    scan, i.e. insertion order, which for this table is `createdAt ASC, id ASC`. First match wins
 *    at each stage in the new pipeline too, so the converter assigns `sortOrder` in that order.
 *    (`observeAll()`'s `createdAt DESC` is the *list display* order, not the evaluation order.)
 *  - **Fail-open configs.** A malformed `replacementValue` converts to the stage's *empty* config and
 *    a warning -- exactly today's behaviour, where `RuleEnforcer.applyRequestModification` etc. log
 *    and leave the message untouched. Nothing is invented and nothing is dropped.
 *  - **`MODIFY_RESPONSE` ignored `removeHeaders`.** `RuleEnforcer.applyResponseModification` never
 *    read that key, so the converter does not emit `RemoveHeader` for responses; it records a
 *    warning so the user can opt in from the typed editor.
 *  - **`file://` bodies** survive as [BodySource.File] (still templated before resolution, as before).
 *  - **Chaos clamps** are the same as `RuleEnforcer.parseChaosConfig`: rates into `[0,1]`, delays
 *    into `[0, MAX_CHAOS_DELAY_MS]`, `hi >= lo`; a one-element `delayMs` array means `lo..lo`.
 *
 * The converter is pure Kotlin (org.json + this package) and never touches Room, so it runs inside
 * a migration transaction and in plain JVM tests.
 */
object LegacyRuleConverter {

    /** One conversion outcome. [warnings] are non-fatal parity notes surfaced to the import/migration log. */
    sealed interface ConversionResult {
        data class Converted(val rule: InterceptorRule, val warnings: List<String> = emptyList()) : ConversionResult
        data class Unconvertible(val legacyId: Long, val reason: String) : ConversionResult
    }

    /**
     * Converts one legacy row. [sortOrder] is supplied by the caller, which owns the ordering
     * contract (see the class KDoc); [convertAll] applies it.
     */
    fun convert(legacy: InterceptRuleEntity, sortOrder: Int): ConversionResult {
        val warnings = ArrayList<String>(2)
        val matcher = matcherOf(legacy)
        val config: RuleConfig = try {
            configOf(legacy, warnings)
        } catch (t: Throwable) {
            return ConversionResult.Unconvertible(legacy.id, "config conversion failed: ${t.message}")
        }
        val rule = InterceptorRule(
            id = 0,
            name = legacy.name.ifBlank { "Rule ${legacy.id}" },
            stage = config.stage,
            enabled = legacy.enabled,
            sortOrder = sortOrder,
            folderId = null,
            matcher = matcher,
            config = config,
            createdAt = legacy.createdAt,
            updatedAt = legacy.createdAt,
            legacyRuleId = legacy.id,
        )
        return ConversionResult.Converted(rule, warnings)
    }

    /**
     * Converts a whole legacy table in evaluation order (`createdAt ASC, id ASC`), assigning
     * consecutive `sortOrder` values from [firstSortOrder]. Unconvertible rows do not consume a slot.
     */
    fun convertAll(legacy: Collection<InterceptRuleEntity>, firstSortOrder: Int = 0): List<ConversionResult> {
        val ordered = legacy.sortedWith(compareBy<InterceptRuleEntity> { it.createdAt }.thenBy { it.id })
        var next = firstSortOrder
        return ordered.map { row ->
            val result = convert(row, next)
            if (result is ConversionResult.Converted) next++
            result
        }
    }

    // ── matcher ───────────────────────────────────────────────────────────────────────────────

    /** `domain` substring → `*domain*` glob; `pattern` → `urlRegex`; OR-combined. Both blank → matches nothing. */
    fun matcherOf(legacy: InterceptRuleEntity): RuleMatcherSpec {
        val hostGlobs = if (legacy.domain.isNotEmpty()) listOf("*${legacy.domain}*") else emptyList()
        val urlRegex = legacy.pattern.takeIf { it.isNotEmpty() }
        return RuleMatcherSpec(hostGlobs = hostGlobs, urlRegex = urlRegex, combine = MatchCombine.ANY)
    }

    // ── per-action config ─────────────────────────────────────────────────────────────────────

    private fun configOf(legacy: InterceptRuleEntity, warnings: MutableList<String>): RuleConfig = when (legacy.action) {
        RuleAction.BLOCK -> GatewayConfig(mode = GatewayMode.BLOCK, showBlockedInList = false)
        RuleAction.FORCE_MITM -> GatewayConfig(mode = GatewayMode.FORCE_DECRYPT)
        RuleAction.LOG_ONLY -> ObserveConfig
        RuleAction.REDIRECT -> redirect(legacy, warnings)
        RuleAction.MODIFY_REQUEST -> modifyRequest(legacy, warnings)
        RuleAction.MODIFY_RESPONSE -> modifyResponse(legacy, warnings)
        RuleAction.MOCK_RESPONSE -> mock(legacy, warnings)
        RuleAction.SCRIPT_REWRITE -> script(legacy, warnings)
        RuleAction.CHAOS -> chaos(legacy, warnings)
        RuleAction.BODY_ENCRYPT_AES -> bodyCrypto(legacy, CryptoDirection.ENCRYPT, warnings)
        RuleAction.BODY_DECRYPT_AES -> bodyCrypto(legacy, CryptoDirection.DECRYPT, warnings)
    }

    private fun redirect(legacy: InterceptRuleEntity, warnings: MutableList<String>): RewriteConfig {
        val target = legacy.replacementValue?.trim().orEmpty()
        if (target.isEmpty()) {
            warnings += "REDIRECT rule '${legacy.name}' (#${legacy.id}) has no target URL; converted to a no-op rewrite"
            return RewriteConfig()
        }
        // Legacy: request.url = target; request.host = URI(target).host; headers["host"] = host.
        return RewriteConfig(redirection = RedirectSpec(targetUrl = target, preserveHost = false, directoryMatch = false))
    }

    private fun modifyRequest(legacy: InterceptRuleEntity, warnings: MutableList<String>): RewriteConfig {
        val json = parseObjectOrNull(legacy, warnings) ?: return RewriteConfig()
        val mods = ArrayList<Modification>()
        // Order matters and matches RuleEnforcer.applyRequestModification exactly.
        json.optJSONObject("addHeaders")?.let { added ->
            for (key in added.keys()) mods += Modification.SetHeader(key, added.optString(key, ""))
        }
        json.optJSONArray("removeHeaders")?.let { removed ->
            for (i in 0 until removed.length()) mods += Modification.RemoveHeader(removed.optString(i, ""))
        }
        if (json.has("bodyReplace")) mods += Modification.ReplaceBody(BodySource.fromLegacyValue(json.optString("bodyReplace", "")))
        return RewriteConfig(requestModifications = mods, variables = variablesOf(json))
    }

    private fun modifyResponse(legacy: InterceptRuleEntity, warnings: MutableList<String>): RewriteConfig {
        val json = parseObjectOrNull(legacy, warnings) ?: return RewriteConfig()
        val mods = ArrayList<Modification>()
        // Order matters and matches RuleEnforcer.applyResponseModification exactly: statusCode,
        // addHeaders, bodyReplace. `removeHeaders` was never read on the response side.
        if (json.has("statusCode")) {
            val code = json.optInt("statusCode", Int.MIN_VALUE)
            if (code != Int.MIN_VALUE) mods += Modification.SetStatus(code)
            else warnings += "MODIFY_RESPONSE rule '${legacy.name}' (#${legacy.id}) has a non-integer statusCode; ignored"
        }
        json.optJSONObject("addHeaders")?.let { added ->
            for (key in added.keys()) mods += Modification.SetHeader(key, added.optString(key, ""))
        }
        if (json.has("bodyReplace")) mods += Modification.ReplaceBody(BodySource.fromLegacyValue(json.optString("bodyReplace", "")))
        if (json.has("removeHeaders")) {
            warnings += "MODIFY_RESPONSE rule '${legacy.name}' (#${legacy.id}) carries removeHeaders, which the legacy " +
                "engine never applied to responses; not converted (add RemoveHeader steps in the editor to opt in)"
        }
        return RewriteConfig(responseModifications = mods, variables = variablesOf(json))
    }

    private fun mock(legacy: InterceptRuleEntity, warnings: MutableList<String>): MockConfig {
        val json = parseObjectOrNull(legacy, warnings) ?: return MockConfig()
        val headers = LinkedHashMap<String, String>()
        json.optJSONObject("addHeaders")?.let { added ->
            // Legacy lowercased mock header names on parse (HttpParser convention).
            for (key in added.keys()) headers[key.lowercase()] = added.optString(key, "")
        }
        return MockConfig(
            statusCode = if (json.has("statusCode")) json.optInt("statusCode", 200) else 200,
            statusMessage = if (json.has("statusMessage")) json.optString("statusMessage", "OK") else "OK",
            headers = headers,
            body = if (json.has("bodyReplace")) BodySource.fromLegacyValue(json.optString("bodyReplace", "")) else BodySource.Empty,
            variables = variablesOf(json),
        )
    }

    private fun script(legacy: InterceptRuleEntity, warnings: MutableList<String>): ScriptConfig {
        val json = parseObjectOrNull(legacy, warnings) ?: return ScriptConfig(source = "")
        val source = json.optString("script", "")
        if (source.isBlank()) warnings += "SCRIPT_REWRITE rule '${legacy.name}' (#${legacy.id}) has a blank script; converted as a no-op"
        return ScriptConfig(source = source, variables = variablesOf(json))
    }

    private fun chaos(legacy: InterceptRuleEntity, warnings: MutableList<String>): ChaosConfig {
        val json = parseObjectOrNull(legacy, warnings) ?: return ChaosConfig()
        val failRate = json.optDouble("failRate", 0.0).coerceIn(0.0, 1.0)
        val corruptBodyRate = json.optDouble("corruptBodyRate", 0.0).coerceIn(0.0, 1.0)
        var lo = 0L
        var hi = 0L
        val delay: JSONArray? = json.optJSONArray("delayMs")
        if (delay != null) {
            lo = delay.optLong(0, 0L).coerceIn(0L, ChaosConfig.MAX_DELAY_MS)
            hi = (if (delay.length() > 1) delay.optLong(1, lo) else lo).coerceIn(lo, ChaosConfig.MAX_DELAY_MS)
        } else if (json.has("delayMs")) {
            warnings += "CHAOS rule '${legacy.name}' (#${legacy.id}) has a non-array delayMs; treated as no delay"
        }
        return ChaosConfig(
            failRate = failRate,
            corruptBodyRate = corruptBodyRate,
            delayMinMs = lo,
            delayMaxMs = hi,
            failStatusCode = if (json.has("failStatusCode")) json.optInt("failStatusCode", ChaosConfig.DEFAULT_FAIL_STATUS_CODE) else ChaosConfig.DEFAULT_FAIL_STATUS_CODE,
            failStatusMessage = if (json.has("failStatusMessage")) json.optString("failStatusMessage", ChaosConfig.DEFAULT_FAIL_STATUS_MESSAGE) else ChaosConfig.DEFAULT_FAIL_STATUS_MESSAGE,
            failBody = if (json.has("failBody")) json.optString("failBody", ChaosConfig.DEFAULT_FAIL_BODY) else ChaosConfig.DEFAULT_FAIL_BODY,
        )
    }

    private fun bodyCrypto(legacy: InterceptRuleEntity, direction: CryptoDirection, warnings: MutableList<String>): BodyCryptoConfig {
        // RuleEnforcer.applyBodyCryptoWith: "REQUEST" -> request only, "RESPONSE" -> response only,
        // anything else (incl. "BOTH" and garbage) -> both. Normalise to the enum accordingly.
        val scope = when (legacy.cryptoScope.uppercase()) {
            "REQUEST" -> CryptoScope.REQUEST
            "RESPONSE" -> CryptoScope.RESPONSE
            "BOTH" -> CryptoScope.BOTH
            else -> {
                warnings += "crypto rule '${legacy.name}' (#${legacy.id}) has unrecognised cryptoScope '${legacy.cryptoScope}'; treated as BOTH (legacy behaviour)"
                CryptoScope.BOTH
            }
        }
        if (legacy.cryptoKeyAlias.isBlank()) {
            warnings += "crypto rule '${legacy.name}' (#${legacy.id}) has a blank key alias; it fails closed at apply time (legacy behaviour)"
        }
        if (legacy.cryptoDirection.isNotBlank() && !legacy.cryptoDirection.equals(direction.name, ignoreCase = true)) {
            warnings += "crypto rule '${legacy.name}' (#${legacy.id}) cryptoDirection '${legacy.cryptoDirection}' disagrees with action ${legacy.action}; the action wins (legacy behaviour)"
        }
        return BodyCryptoConfig(
            direction = direction,
            scope = scope,
            keyAlias = legacy.cryptoKeyAlias,
            algorithm = legacy.cryptoAlgorithm.ifBlank { BodyCryptoConfig.DEFAULT_ALGORITHM },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Null for a blank config (a legitimate "no config" -- no warning) or a malformed one (warned). */
    private fun parseObjectOrNull(legacy: InterceptRuleEntity, warnings: MutableList<String>): JSONObject? {
        val raw = legacy.replacementValue
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            warnings += "${legacy.action} rule '${legacy.name}' (#${legacy.id}) has malformed replacementValue JSON (${t.message}); converted to an empty config (legacy fail-open)"
            null
        }
    }

    /** The optional `"variables": {"k":"v"}` object; absent/malformed → empty (RuleEnforcer.parseTemplateVariables). */
    private fun variablesOf(json: JSONObject): Map<String, String> {
        val vars = json.optJSONObject("variables") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in vars.keys()) out[key] = vars.optString(key, "")
        return out
    }
}
