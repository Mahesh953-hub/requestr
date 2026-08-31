package com.example.ui.screens

import com.example.data.local.RuleAction
import org.json.JSONObject

/**
 * Followup to §3 (C10) -- the two `replacementValue` shapes RuleEnforcer's parsers and
 * CaptureEngine's REDIRECT handling actually understand. See [replacementValueSpecFor]'s KDoc
 * for the single source of truth mapping each [RuleAction] to its shape, so the Add-Rule form's
 * label/placeholder/supporting-text/required-ness and [replacementValueError]'s validation can
 * never drift out of sync with each other.
 */
enum class ReplacementValueShape { URL, JSON_OBJECT }

/**
 * Everything `RulesConfigBottomSheet`'s Add-Rule form needs to render a self-describing
 * `replacementValue` field for a given [RuleAction] AND validate it before Save -- one source of
 * truth so the label/placeholder/supporting text/required-ness and the "does this JSON object
 * actually do anything" check in [replacementValueError] can never disagree with each other.
 *
 * @property effectiveJsonKeys Only meaningful when [shape] is [ReplacementValueShape.JSON_OBJECT].
 * The JSON object keys RuleEnforcer's parser for this action actually reads (see
 * [replacementValueSpecFor]'s per-branch KDoc for the exact source function). A parsed JSON
 * object containing none of these -- e.g. `{"variables":{...}}` alone for `MODIFY_REQUEST` -- has
 * zero effect on a matched request/response, so [replacementValueError] rejects it as a silent
 * no-op, exactly like malformed JSON. `"variables"` is deliberately never listed here: every
 * schema below also accepts a `variables` map (see [mergeVariablesIntoConfigJson]), but variables
 * only matter for `{{...}}` placeholders *inside* one of the effective keys -- a config with only
 * `variables` and no effective key still changes nothing.
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
 * every [RuleAction] that does not read `replacementValue` at all -- `BLOCK`/`LOG_ONLY`/
 * `FORCE_MITM` ignore it outright ([com.example.interception.CaptureEngine.persistTransaction]),
 * `BODY_ENCRYPT_AES`/`BODY_DECRYPT_AES` configure via `cryptoKeyAlias`/`cryptoScope` instead
 * ([com.example.interception.RuleEnforcer.applyBodyCrypto]), and `SCRIPT_REWRITE` gets its own
 * dedicated "Script (JS)" field in `RulesConfigBottomSheet` (the config JSON is built from that
 * raw script text automatically, so there is no user-typed JSON shape to validate here).
 */
fun replacementValueSpecFor(action: RuleAction): ReplacementValueSpec? = when (action) {
    // com.example.interception.CaptureEngine.persistTransaction's REDIRECT branch assigns
    // replacementValue straight to request.url and runs java.net.URI(...) on it -- a blank,
    // relative, or scheme-less value either throws there (caught, leaving request.host stale)
    // or produces a nonsense host.
    RuleAction.REDIRECT -> ReplacementValueSpec(
        shape = ReplacementValueShape.URL,
        label = "Redirect URL",
        placeholder = "https://api.example.com/v2/users",
        supportingText = "Absolute URL (with a scheme and host) the matched request's URL is " +
            "rewritten to before it's persisted/forwarded.",
        required = true,
    )
    // com.example.interception.RuleEnforcer.applyRequestModification
    RuleAction.MODIFY_REQUEST -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Request modification (JSON)",
        placeholder = "{\"addHeaders\":{\"X-Trace\":\"{{traceId}}\"},\"bodyReplace\":\"{}\"}",
        supportingText = "JSON object with any of addHeaders / removeHeaders / bodyReplace -- " +
            "a config with none of those keys never changes the request.",
        required = false,
        effectiveJsonKeys = setOf("addHeaders", "removeHeaders", "bodyReplace"),
    )
    // com.example.interception.RuleEnforcer.applyResponseModification
    RuleAction.MODIFY_RESPONSE -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Response modification (JSON)",
        placeholder = "{\"statusCode\":200,\"addHeaders\":{\"X-Mock\":\"true\"},\"bodyReplace\":\"{}\"}",
        supportingText = "JSON object with any of statusCode / addHeaders / bodyReplace -- a " +
            "config with none of those keys never changes the response.",
        required = false,
        effectiveJsonKeys = setOf("statusCode", "addHeaders", "bodyReplace"),
    )
    // com.example.interception.RuleEnforcer.buildMockResponse
    RuleAction.MOCK_RESPONSE -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Mock response (JSON, optional)",
        placeholder = "{\"statusCode\":200,\"addHeaders\":{\"Content-Type\":\"application/json\"},\"bodyReplace\":\"{}\"}",
        supportingText = "JSON object with any of statusCode / statusMessage / addHeaders / " +
            "bodyReplace. Left blank, the mock defaults to 200 OK with an empty body.",
        required = false,
        effectiveJsonKeys = setOf("statusCode", "statusMessage", "addHeaders", "bodyReplace"),
    )
    // com.example.interception.RuleEnforcer.parseChaosConfig -- failStatusCode/failStatusMessage/
    // failBody (buildChaosFailureResponse) are deliberately NOT in effectiveJsonKeys: they only
    // shape what a Fail outcome looks like, they can never by themselves cause one.
    RuleAction.CHAOS -> ReplacementValueSpec(
        shape = ReplacementValueShape.JSON_OBJECT,
        label = "Chaos config (JSON)",
        placeholder = "{\"failRate\":0.1,\"delayMs\":[200,800],\"corruptBodyRate\":0.05}",
        supportingText = "JSON object with any of failRate / delayMs / corruptBodyRate -- a " +
            "config with none of those keys never injects any chaos (always passes through).",
        required = false,
        effectiveJsonKeys = setOf("failRate", "delayMs", "corruptBodyRate"),
    )
    else -> null
}

/**
 * True when [text] parses as an absolute URL with both a scheme and a non-blank host -- exactly
 * what `CaptureEngine`'s REDIRECT handling needs before it hands the string to
 * `java.net.URI(...).host`. A relative reference (`java.net.URI.isAbsolute` false, e.g.
 * `"/v2/users"`) or a scheme with no host (`"mailto:a@b.com"`, `"http:///path"`) both fail this.
 */
fun isAbsoluteUrlWithHost(text: String): Boolean {
    val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return false
    return uri.isAbsolute && !uri.host.isNullOrBlank()
}

/**
 * The exact `replacementValue` `RulesConfigBottomSheet`'s Save button will actually persist for
 * [action], given the raw text typed into the field and the current Template-variables rows (see
 * `variableRowsToMap`/[mergeVariablesIntoConfigJson] in `RuleTemplateUiHelpers.kt`, same package).
 * Needed because merging variables into an otherwise-blank config produces exactly the no-op
 * `{"variables":{...}}` shape [replacementValueError] must reject -- validating [rawText] alone
 * would miss that case entirely, since [rawText] is blank right up until the merge happens.
 */
fun effectiveReplacementValueForSave(action: RuleAction, rawText: String, variables: Map<String, String>): String =
    if (ruleActionUsesTemplating(action) && action != RuleAction.SCRIPT_REWRITE && variables.isNotEmpty()) {
        mergeVariablesIntoConfigJson(rawText.trim().ifBlank { null }, variables)
    } else {
        rawText
    }

/**
 * Validates [rawText] against [action]'s [replacementValueSpecFor] before Save. Returns a
 * user-facing error string, or `null` when the value is fine to save as-is -- which, for every
 * non-[ReplacementValueSpec.required] shape, includes a blank [rawText] (MOCK_RESPONSE's own
 * defaults make a blank config meaningful; MODIFY_REQUEST/MODIFY_RESPONSE/CHAOS's blank config
 * is a no-op rule the user is still free to save deliberately, e.g. while deciding what to fill
 * in -- only a *non-blank but useless-or-malformed* value is rejected).
 *
 * This is the single gate that stops the Add-Rule form from saving a rule whose replacementValue
 * would be a silent no-op on the engine side: every one of RuleEnforcer's `apply*`/
 * `buildMockResponse`/`parseChaosConfig` parsers, and CaptureEngine's REDIRECT handling, either
 * wraps its parsing in `runCatching` or otherwise fails open -- a bad value is logged at most,
 * never surfaced to the user again once the rule is saved.
 *
 * Callers that also offer a Template-variables editor (see [effectiveReplacementValueForSave])
 * must pass that function's result here, not the raw field text, or a variables-only config will
 * slip through as if it were blank.
 */
fun replacementValueError(action: RuleAction, rawText: String): String? {
    val spec = replacementValueSpecFor(action) ?: return null
    val trimmed = rawText.trim()
    if (trimmed.isBlank()) {
        return if (spec.required) "${spec.label} is required for ${action.name}." else null
    }
    return when (spec.shape) {
        ReplacementValueShape.URL ->
            if (isAbsoluteUrlWithHost(trimmed)) {
                null
            } else {
                "Enter an absolute URL with a scheme and host, e.g. ${spec.placeholder}"
            }
        ReplacementValueShape.JSON_OBJECT -> {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
                ?: return "Must be a JSON object, e.g. ${spec.placeholder}"
            val hasEffect = spec.effectiveJsonKeys.any { json.has(it) }
            if (hasEffect) {
                null
            } else {
                "This JSON object has none of: ${spec.effectiveJsonKeys.joinToString(", ")} -- the rule would have no effect."
            }
        }
    }
}

