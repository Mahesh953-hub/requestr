package com.example.ui.screens

import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RuleAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, JVM-testable validity rules for the Add-Rule form in `RulesConfigBottomSheet` (see
 * `InterceptScreen.kt`) and for the rules already saved in `intercept_rules`.
 *
 * The single principle (review finding M4, shared-context §8 "Honesty rules"): **a rule is valid
 * only if the engine path for its action would do something observable with the saved config.**
 * Every `RuleEnforcer`/`CaptureEngine`/`MitmProxyEngine` branch that reads a rule config fails
 * open -- a blank, malformed, or key-less config is logged at most and the traffic passes through
 * untouched -- so an enabled rule with such a config is a silent no-op the user can never see.
 * The form must therefore refuse to save one, and the list must flag any that already exist.
 *
 * Engine evidence, per [RuleAction] (locate by symbol; line numbers drift):
 *
 * - `BLOCK` / `LOG_ONLY` / `FORCE_MITM` -- never read `replacementValue`
 *   (`CaptureEngine.persistTransaction`, `MitmProxyEngine.handleParsedRequest`, `ConnectControl`).
 *   Always valid.
 * - `REDIRECT` -- `if (rule?.replacementValue?.isNotBlank() == true) request.url = ...` in both
 *   `CaptureEngine.persistTransaction` and `MitmProxyEngine.handleParsedRequest`; a blank value is
 *   skipped and `java.net.URI(...).host` needs a scheme + host. Valid iff absolute URL with host.
 * - `MODIFY_REQUEST` -- `rule?.replacementValue?.let { applyRequestModification(request, it) }`;
 *   `applyRequestModification` wraps `JSONObject(config)` in `runCatching` and only acts on
 *   `addHeaders` / `removeHeaders` / `bodyReplace`. Null, blank, malformed, or key-less → no-op.
 * - `MODIFY_RESPONSE` -- same shape via `applyResponseModification`, keys `statusCode` /
 *   `addHeaders` / `bodyReplace`.
 * - `MOCK_RESPONSE` -- `buildMockResponse(config)` starts from `200 OK`, empty body, and only
 *   consults the config `if (!config.isNullOrBlank())`. A blank config therefore *is* the
 *   documented empty `200 OK` -- a real, intentional response -- and stays valid. A non-blank
 *   config whose keys the mock never reads is rejected, because it would be silently ignored.
 * - `SCRIPT_REWRITE` -- `applyScriptRewrite`: `if (config.isNullOrBlank()) return`, then
 *   `json.optString("script", "")` and `if (script.isBlank()) return@runCatching`. Valid iff the
 *   persisted config is a JSON object with a non-blank `script`.
 * - `CHAOS` -- `applyChaos`: `if (config.isNullOrBlank()) return PassThrough`; then
 *   `ChaosInjector.decide(parseChaosConfig(config))` only ever leaves `PassThrough` when
 *   `failRate > 0`, `corruptBodyRate > 0`, or `delayMs`'s upper bound `> 0`. A config that
 *   parses but has all three at zero (including the default when a key is absent, or when
 *   `optDouble` falls back to `0.0` for a non-numeric value) is a no-op. Valid iff at least one
 *   trigger is above zero. `failStatusCode`/`failStatusMessage`/`failBody` only shape a Fail
 *   outcome (`buildChaosFailureResponse`); they can never cause one.
 * - `BODY_ENCRYPT_AES` / `BODY_DECRYPT_AES` -- configured via `cryptoKeyAlias`/`cryptoScope`, not
 *   `replacementValue` (`applyBodyCrypto`); a blank alias fails *visibly* (a synthetic failure
 *   response, never a silent pass-through), and [cryptoKeyAliasError] already gates it.
 *
 * Matching is the other half of "does something": `RuleEnforcer.getMatchingRule` matches on
 * `domain` (substring) OR `pattern` (`Regex(pattern)` inside `runCatching { }.getOrDefault(false)`),
 * so a pattern that does not compile never matches -- see [rulePatternError].
 */
enum class ReplacementValueShape { URL, JSON_OBJECT }

/**
 * Everything the Add-Rule form needs to render a self-describing `replacementValue` field for a
 * given [RuleAction] AND validate it before Save -- one source of truth so the label/placeholder/
 * supporting text/required-ness and [replacementValueError] can never disagree with each other.
 *
 * @property required When true, a blank value is rejected because the engine path skips the rule
 * entirely without one (see the file KDoc for the per-action evidence). Only `MOCK_RESPONSE` is
 * optional: its blank config is the documented empty `200 OK`.
 * @property effectiveJsonKeys Only meaningful when [shape] is [ReplacementValueShape.JSON_OBJECT]:
 * the JSON object keys the engine's parser for this action actually reads. `"variables"` is
 * deliberately never listed -- every JSON schema also accepts a `variables` map (see
 * [mergeVariablesIntoConfigJson]), but variables only matter for `{{...}}` placeholders *inside*
 * one of the effective keys; a config with only `variables` still changes nothing.
 */
data class ReplacementValueSpec(
    val shape: ReplacementValueShape,
    val label: String,
    val placeholder: String,
    val supportingText: String,
    val required: Boolean,
    val effectiveJsonKeys: Set<String> = emptySet(),
)

/**
 * The single source of truth for what [action]'s `replacementValue` field should look like in
 * the Add-Rule form, and what [replacementValueError] validates it against. Returns `null` for
 * every [RuleAction] that has no free-text `replacementValue` field in the form: `BLOCK`/`LOG_ONLY`/
 * `FORCE_MITM` never read it, `BODY_ENCRYPT_AES`/`BODY_DECRYPT_AES` configure via `cryptoKeyAlias`/
 * `cryptoScope` instead, and `SCRIPT_REWRITE` gets its own dedicated "Script (JS)" field (its
 * `{"script": ...}` config JSON is built by [replacementValueToSave], and validated by
 * [replacementValueError]'s `SCRIPT_REWRITE` branch, not by a spec here).
 */
fun replacementValueSpecFor(action: RuleAction): ReplacementValueSpec? = when (action) {
    RuleAction.REDIRECT -> ReplacementValueSpec(
        shape = ReplacementValueShape.URL,
        label = "Redirect URL",
        placeholder = "https://api.example.com/v2/users",
        supportingText = "Absolute URL (with a scheme and host) the matched request's URL is " +
            "rewritten to before it's persisted/forwarded.",
        required = true,
    )
    RuleAction.MODIFY_REQUEST -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Request modification (JSON)",
        placeholder = "{\"addHeaders\":{\"X-Trace\":\"{{traceId}}\"},\"bodyReplace\":\"{}\"}",
        supportingText = "JSON object with at least one of addHeaders / removeHeaders / bodyReplace -- " +
            "the engine only ever changes the request through those keys.",
        required = true,
        effectiveJsonKeys = setOf("addHeaders", "removeHeaders", "bodyReplace"),
    )
    RuleAction.MODIFY_RESPONSE -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Response modification (JSON)",
        placeholder = "{\"statusCode\":200,\"addHeaders\":{\"X-Mock\":\"true\"},\"bodyReplace\":\"{}\"}",
        supportingText = "JSON object with at least one of statusCode / addHeaders / bodyReplace -- " +
            "the engine only ever changes the response through those keys.",
        required = true,
        effectiveJsonKeys = setOf("statusCode", "addHeaders", "bodyReplace"),
    )
    RuleAction.MOCK_RESPONSE -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Mock response (JSON, optional)",
        placeholder = "{\"statusCode\":200,\"addHeaders\":{\"Content-Type\":\"application/json\"},\"bodyReplace\":\"{}\"}",
        supportingText = "JSON object with any of statusCode / statusMessage / addHeaders / " +
            "bodyReplace. Left blank, the mock answers 200 OK with an empty body.",
        required = false,
        effectiveJsonKeys = setOf("statusCode", "statusMessage", "addHeaders", "bodyReplace"),
    )
    RuleAction.CHAOS -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Chaos config (JSON)",
        placeholder = "{\"failRate\":0.1,\"delayMs\":[200,800],\"corruptBodyRate\":0.05}",
        supportingText = "JSON object with at least one of failRate / corruptBodyRate (0-1) or " +
            "delayMs ([min,max] ms) above zero -- otherwise the rule never injects anything.",
        required = true,
        effectiveJsonKeys = setOf("failRate", "delayMs", "corruptBodyRate"),
    )
    else -> null
}

/**
 * True when [text] parses as an absolute URL with both a scheme and a non-blank host -- exactly
 * what the REDIRECT handling needs before it hands the string to `java.net.URI(...).host`. A
 * relative reference (`"/v2/users"`) or a scheme with no host (`"mailto:a@b.com"`,
 * `"http:///path"`) both fail this.
 */
fun isAbsoluteUrlWithHost(text: String): Boolean {
    val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return false
    return uri.isAbsolute && !uri.host.isNullOrBlank()
}

/**
 * The per-action config the Add-Rule form has collected so far -- the raw text of every field
 * that ends up in the saved [InterceptRuleEntity], *before* any merging. [replacementValueToSave]
 * turns it into the exact persisted `replacementValue`; [ruleConfigError] validates it.
 */
data class RuleConfigDraft(
    val action: RuleAction,
    /** Raw text of the `replacementValue` field (ignored for `SCRIPT_REWRITE`, which uses [scriptText]). */
    val replacementValueText: String = "",
    /** Raw text of the dedicated "Script (JS)" field -- only read for `SCRIPT_REWRITE`. */
    val scriptText: String = "",
    /** Already-filtered Template-variables rows, see [variableRowsToMap]. */
    val variables: Map<String, String> = emptyMap(),
    /** Only read for the two AES body-crypto actions. */
    val cryptoKeyAlias: String = "",
)

/**
 * The exact `replacementValue` the Add-Rule form persists for [draft] -- the ONE builder both the
 * Save handler and [ruleConfigError] go through, so what gets validated is byte-for-byte what
 * gets saved. Returns `null` (not `""`) when there is nothing to persist, matching
 * [InterceptRuleEntity.replacementValue]'s nullable default.
 *
 * - `SCRIPT_REWRITE`: `{"script": "<js>", "variables": {...}}` built from [RuleConfigDraft.scriptText]
 *   via [mergeScriptIntoConfigJson] + [mergeVariablesIntoConfigJson]; `null` only when both are empty.
 * - Other templating actions ([ruleActionUsesTemplating]) with a non-empty variables map: the raw
 *   JSON text with `"variables"` merged in ([mergeVariablesIntoConfigJson]) -- so a blank raw text
 *   plus variables yields the `{"variables":{...}}` shape [replacementValueError] must reject.
 * - Everything else: the trimmed raw text, or `null` when blank.
 */
fun replacementValueToSave(draft: RuleConfigDraft): String? {
    val raw = draft.replacementValueText.trim()
    return when {
        draft.action == RuleAction.SCRIPT_REWRITE -> {
            val script = draft.scriptText.trim()
            if (script.isBlank() && draft.variables.isEmpty()) {
                null
            } else {
                mergeVariablesIntoConfigJson(mergeScriptIntoConfigJson(null, script), draft.variables)
            }
        }
        ruleActionUsesTemplating(draft.action) && draft.variables.isNotEmpty() ->
            mergeVariablesIntoConfigJson(raw.ifBlank { null }, draft.variables).ifBlank { null }
        else -> raw.ifBlank { null }
    }
}

/**
 * Validates the whole per-action config of [draft] the way the user sees it in the form, and
 * returns a specific, user-facing reason the rule would do nothing -- or `null` when saving it
 * would produce a rule whose engine path has an observable effect.
 *
 * Checks, in order: the AES key alias ([cryptoKeyAliasError]); a blank `SCRIPT_REWRITE` script
 * (named as the "Script (JS)" field the user is looking at, not as the derived JSON); a non-blank
 * JSON-shaped field that isn't a JSON object (caught on the raw text, because merging variables
 * into unparseable text would otherwise discard it and mis-report "no effective key"); and
 * finally [replacementValueError] on exactly the value [replacementValueToSave] will persist.
 */
fun ruleConfigError(draft: RuleConfigDraft): String? {
    cryptoKeyAliasError(draft.action, draft.cryptoKeyAlias)?.let { return it }
    if (draft.action == RuleAction.SCRIPT_REWRITE && draft.scriptText.isBlank()) {
        return "Script (JS) is required for ${ruleActionLabel(draft.action)} -- with no script, every matched request/response passes through untouched."
    }
    val spec = replacementValueSpecFor(draft.action)
    val raw = draft.replacementValueText.trim()
    if (spec?.shape == ReplacementValueShape.JSON_OBJECT && raw.isNotBlank() && parseJsonObject(raw) == null) {
        return "${spec.label} must be a JSON object, e.g. ${spec.placeholder}"
    }
    return replacementValueError(draft.action, replacementValueToSave(draft))
}

/**
 * Why the URL pattern would never match anything, or `null` when it will. Mirrors
 * `RuleEnforcer.getMatchingRule`, which requires `pattern.isNotEmpty()` and evaluates
 * `Regex(pattern, IGNORE_CASE)` inside `runCatching { }.getOrDefault(false)` -- a pattern that
 * doesn't compile therefore silently matches nothing.
 */
fun rulePatternError(pattern: String): String? {
    val trimmed = pattern.trim()
    if (trimmed.isBlank()) return "URL Pattern is required -- a rule with no pattern never matches any request."
    val failure = runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.exceptionOrNull() ?: return null
    val detail = failure.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return "URL Pattern is not a valid regex${if (detail.isNotBlank()) " ($detail)" else ""} -- it would never match any request."
}

/**
 * The single reason the Add-Rule button is disabled, or `null` when the rule can be saved. The
 * button's `enabled` and the inline text under it both derive from this, so the user always
 * sees exactly why Save is blocked -- never a silently dead button. Order matches the form's
 * top-to-bottom layout: name, pattern, then the per-action config ([ruleConfigError]).
 */
fun addRuleSaveBlocker(name: String, pattern: String, draft: RuleConfigDraft): String? {
    if (name.isBlank()) return "Rule Name is required."
    rulePatternError(pattern)?.let { return it }
    return ruleConfigError(draft)
}

/**
 * Why an already-saved [rule] would do nothing when it matches, or `null` when it is effective.
 * Used by the rules list to flag rules persisted before the form refused no-op configs -- they
 * are shown, not hidden or silently rewritten, and the reason is spelled out next to them.
 *
 * Matching is included: with a blank `domain`, a blank or non-compiling `pattern` means
 * `RuleEnforcer.getMatchingRule` can never select the rule at all. (A non-blank `domain` matches
 * by substring on its own, so the pattern is only decisive when the domain is blank.)
 */
fun savedRuleConfigError(rule: InterceptRuleEntity): String? {
    if (rule.domain.isBlank()) rulePatternError(rule.pattern)?.let { return it }
    cryptoKeyAliasError(rule.action, rule.cryptoKeyAlias)?.let { return it }
    return replacementValueError(rule.action, rule.replacementValue)
}

/**
 * Validates a persisted-shape `replacementValue` for [action] and returns a specific user-facing
 * reason it would be a silent no-op, or `null` when the engine path would do something with it.
 * This is the persisted-level gate; [ruleConfigError] is the form-level wrapper that feeds it the
 * exact value [replacementValueToSave] produces. See the file KDoc for the engine evidence.
 *
 * `SCRIPT_REWRITE` is validated here too (`{"script": ...}` with a non-blank script), even though
 * [replacementValueSpecFor] has no field spec for it, so [savedRuleConfigError] can flag a saved
 * script rule with an empty script.
 */
fun replacementValueError(action: RuleAction, rawText: String?): String? {
    val trimmed = rawText?.trim().orEmpty()
    if (action == RuleAction.SCRIPT_REWRITE) return scriptRewriteConfigError(trimmed)
    val spec = replacementValueSpecFor(action) ?: return null
    if (trimmed.isBlank()) {
        return if (spec.required) {
            "${spec.label} is required for ${ruleActionLabel(action)} -- with it blank the engine skips this rule entirely."
        } else {
            null
        }
    }
    return when (spec.shape) {
        ReplacementValueShape.URL ->
            if (isAbsoluteUrlWithHost(trimmed)) {
                null
            } else {
                "Enter an absolute URL with a scheme and host, e.g. ${spec.placeholder}"
            }
        ReplacementValueShape.JSON_OBJECT -> {
            val json = parseJsonObject(trimmed)
                ?: return "${spec.label} must be a JSON object, e.g. ${spec.placeholder}"
            if (action == RuleAction.CHAOS) chaosConfigError(json) else modifyOrMockConfigError(action, spec, json)
        }
    }
}

private fun parseJsonObject(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

/** `applyScriptRewrite`: blank config → return; `optString("script")` blank → return. */
private fun scriptRewriteConfigError(trimmed: String): String? {
    if (trimmed.isBlank()) {
        return "Script (JS) is required for ${ruleActionLabel(RuleAction.SCRIPT_REWRITE)} -- with no script, every matched request/response passes through untouched."
    }
    val json = parseJsonObject(trimmed)
        ?: return "Script config must be a JSON object of the form {\"script\":\"...\"} -- this rule's config is not, so the script never runs."
    if (json.optString("script", "").isBlank()) {
        return "Script (JS) is empty -- with no script, every matched request/response passes through untouched."
    }
    return null
}

/**
 * `MODIFY_REQUEST` / `MODIFY_RESPONSE` / `MOCK_RESPONSE`: each present effective key must have
 * the shape the engine's getter needs (a wrong type throws inside the engine's `runCatching`,
 * which aborts the whole modification), and at least one present key must actually carry
 * something (`addHeaders: {}` and `removeHeaders: []` iterate zero times). For the optional
 * `MOCK_RESPONSE`, `{}` and a variables-only object are as valid as blank -- the default `200 OK`
 * is the effect -- but an object carrying only keys the mock never reads is rejected, because
 * the user typed a config that would be silently ignored.
 */
private fun modifyOrMockConfigError(action: RuleAction, spec: ReplacementValueSpec, json: JSONObject): String? {
    val presentKeys = spec.effectiveJsonKeys.filter { json.has(it) }
    for (key in presentKeys) {
        effectiveKeyShapeError(key, json)?.let { return it }
    }
    val emptyKeys = presentKeys.filter { isEmptyContainer(json, it) }
    val effectiveKeys = presentKeys - emptyKeys.toSet()
    if (effectiveKeys.isNotEmpty()) return null

    val keyList = spec.effectiveJsonKeys.joinToString(", ")
    if (emptyKeys.isNotEmpty()) {
        return "${emptyKeys.joinToString(", ")} ${if (emptyKeys.size == 1) "is" else "are"} empty -- the rule would have no effect. Put at least one entry in it, or use one of: $keyList."
    }
    val unreadKeys = json.keys().asSequence().filter { it != "variables" }.toList()
    if (!spec.required && unreadKeys.isEmpty()) {
        // MOCK_RESPONSE `{}` / `{"variables":{...}}`: identical to blank -- default 200 OK.
        return null
    }
    if (!spec.required) {
        return "The mock never reads ${unreadKeys.joinToString(", ")} -- it would answer the default 200 OK and ignore them. Use one of: $keyList, or leave the field blank for the default."
    }
    return "This JSON object has none of: $keyList -- the rule would have no effect."
}

/** Runs the exact getter `RuleEnforcer` uses for [key]; a throw there is a broken config. */
private fun effectiveKeyShapeError(key: String, json: JSONObject): String? = when (key) {
    "addHeaders" -> runCatching { json.getJSONObject(key) }.exceptionOrNull()
        ?.let { "addHeaders must be a JSON object of header name to value, e.g. {\"X-Trace\":\"1\"}." }
    "removeHeaders" -> runCatching { json.getJSONArray(key) }.exceptionOrNull()
        ?.let { "removeHeaders must be a JSON array of header names, e.g. [\"Authorization\"]." }
    "statusCode" -> {
        val code = runCatching { json.getInt(key) }.getOrNull()
        when {
            code == null -> "statusCode must be an integer HTTP status, e.g. 200."
            // The engine writes this straight into the status line (`HTTP/1.1 <code> ...`); anything
            // outside RFC 9110's three-digit range is not a status a client can parse.
            code !in HTTP_STATUS_RANGE -> "statusCode $code is not a valid HTTP status -- use a three-digit code from 100 to 599, e.g. 200 or 503."
            else -> null
        }
    }
    // bodyReplace / statusMessage: getString(...) coerces any non-null value.
    else -> runCatching { json.getString(key) }.exceptionOrNull()
        ?.let { "$key must be a string." }
}

/** RFC 9110 §15: status codes are three digits, 100-599. */
private val HTTP_STATUS_RANGE = 100..599

private fun isEmptyContainer(json: JSONObject, key: String): Boolean = when (val value = json.opt(key)) {
    is JSONObject -> value.length() == 0
    is JSONArray -> value.length() == 0
    else -> false
}

/**
 * `CHAOS`: replays `RuleEnforcer.parseChaosConfig` + `ChaosInjector.decide`'s trigger conditions.
 * `optDouble` falls back to `0.0` for a non-numeric value and `getJSONArray("delayMs")` throws
 * for a non-array -- both of which the engine turns into an all-zero, always-pass-through config.
 */
private fun chaosConfigError(json: JSONObject): String? {
    for (rateKey in listOf("failRate", "corruptBodyRate")) {
        if (json.has(rateKey) && json.optDouble(rateKey, Double.NaN).isNaN()) {
            return "$rateKey must be a number between 0 and 1, e.g. 0.1."
        }
    }
    val failRate = json.optDouble("failRate", 0.0).coerceIn(0.0, 1.0)
    val corruptBodyRate = json.optDouble("corruptBodyRate", 0.0).coerceIn(0.0, 1.0)
    val delayHi = if (json.has("delayMs")) {
        val arr = runCatching { json.getJSONArray("delayMs") }.getOrNull()
            ?: return "delayMs must be a two-element [min, max] array of milliseconds, e.g. [200, 800]."
        val lo = arr.optLong(0, 0L).coerceAtLeast(0L)
        (if (arr.length() > 1) arr.optLong(1, lo) else lo).coerceAtLeast(lo)
    } else {
        0L
    }
    if (failRate > 0.0 || corruptBodyRate > 0.0 || delayHi > 0L) return null
    return "failRate and corruptBodyRate are 0 and delayMs is 0 -- the rule would have no effect. Set at least one of them above zero."
}

/**
 * Human-readable summary of a captured transaction's `x-requestr-chaos` response header
 * ([com.example.interception.RuleEnforcer.CHAOS_ATTRIBUTION_HEADER]) -- the marker
 * `MitmProxyEngine` stamps on every exchange a `CHAOS` rule actually touched, so the traffic
 * list and detail sheet can badge it as injected rather than let it pass for a real upstream
 * 503 / corrupted payload / slow server. Returns null when the header is absent (no chaos ran).
 * Matches the header key case-insensitively (persisted headers are lowercased, but rows
 * imported from HAR or other engines may not be).
 */
fun chaosAttributionSummary(responseHeaders: Map<String, String>): String? {
    val value = responseHeaders.entries
        .firstOrNull { it.key.equals("x-requestr-chaos", ignoreCase = true) }
        ?.value?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val rule = Regex("""rule="([^"]*)"""").find(value)?.groupValues?.get(1)
    val byRule = if (rule.isNullOrBlank()) "a Chaos rule" else "Chaos rule \"$rule\""
    val kind = value.substringBefore(';').trim()
    return when {
        kind == "fail" -> "$byRule injected this failure response. The request never reached the server."
        kind == "corrupt-body" && value.contains("body-empty=true") ->
            "$byRule rolled body corruption, but the response body was empty so nothing changed."
        kind == "corrupt-body" -> "$byRule corrupted this response body before it reached the client."
        kind.startsWith("delay=") -> "$byRule delayed this response by ${kind.removePrefix("delay=")} before it reached the client."
        else -> "$byRule injected: $value"
    }
}

/**
 * Human-readable summary of a captured transaction's `x-requestr-script-error` header
 * ([com.example.interception.RuleEnforcer.SCRIPT_ERROR_HEADER]) -- stamped by `MitmProxyEngine`
 * when a `SCRIPT_REWRITE` rule's JavaScript threw, on the request headers (request-leg failure,
 * never sent upstream) or the response headers (response-leg failure, also sent to the client).
 * Returns null when neither side carries it (the script ran cleanly or no such rule matched).
 */
fun scriptErrorSummary(requestHeaders: Map<String, String>, responseHeaders: Map<String, String>): String? {
    fun find(headers: Map<String, String>): String? = headers.entries
        .firstOrNull { it.key.equals("x-requestr-script-error", ignoreCase = true) }
        ?.value?.trim()?.takeIf { it.isNotEmpty() }
    val onRequest = find(requestHeaders)
    val onResponse = find(responseHeaders)
    val value = onRequest ?: onResponse ?: return null
    val leg = if (onRequest != null) "request" else "response"
    val ruleMatch = Regex("""; rule="([^"]*)"$""").find(value)
    val rule = ruleMatch?.groupValues?.get(1)
    val error = if (ruleMatch != null) value.removeRange(ruleMatch.range).trim() else value
    val byRule = if (rule.isNullOrBlank()) "A Script Rewrite rule" else "Script Rewrite rule \"$rule\""
    return "$byRule threw while rewriting the $leg, so it was forwarded as the script left it: $error"
}
