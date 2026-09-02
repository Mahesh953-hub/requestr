package com.example.ui.screens

import com.example.data.local.RuleAction
import com.example.interception.RuleTemplateExpander
import com.example.scripting.DynamicVariables
import org.json.JSONObject

/**
 * Master plan §3 (C10) — pure UI-layer helpers for [RulesConfigBottomSheet]'s "New Rule" form's
 * template-variable affordances (the `variables` map editor, the placeholder cheat sheet, and
 * the live expansion preview). Kept out of [com.example.interception.RuleTemplateExpander] and
 * [com.example.interception.RuleEnforcer] deliberately — those are the final, already-tested
 * engine contract this file's callers must never reimplement; everything here either edits the
 * rule's `config` JSON text the form already produces, or calls the real engine functions
 * directly for the preview.
 */

/**
 * Whether [action]'s `config`/`replacementValue` is actually run through template-variable
 * expansion at all -- i.e. whether the rule editor's variables map, cheat sheet, and live preview
 * are relevant to show for this action. `MODIFY_REQUEST`/`MODIFY_RESPONSE`/`MOCK_RESPONSE` go
 * through [RuleTemplateExpander.expand] directly (see [com.example.interception.RuleEnforcer]);
 * `SCRIPT_REWRITE` doesn't call [RuleTemplateExpander] itself but shares the exact same
 * `"variables"` config-JSON key to seed its own `pm.variables`/`{{$name}}` JS-side templating
 * ([com.example.scripting.PmBridgeBase.templateString]) -- so the same editor genuinely applies
 * to it too, even though the expansion mechanism differs slightly under the hood.
 */
fun ruleActionUsesTemplating(action: RuleAction): Boolean = when (action) {
    RuleAction.MODIFY_REQUEST, RuleAction.MODIFY_RESPONSE, RuleAction.MOCK_RESPONSE, RuleAction.SCRIPT_REWRITE -> true
    else -> false
}

/** One entry in the rule editor's placeholder-token cheat sheet. */
data class TemplateTokenInfo(val token: String, val description: String)

/** `{{request.*}}` built-ins, in [RuleTemplateExpander]'s own KDoc resolution order. */
private val BUILTIN_REQUEST_TEMPLATE_TOKENS: List<TemplateTokenInfo> = listOf(
    TemplateTokenInfo("{{request.host}}", "The matched request's host, e.g. api.example.com"),
    TemplateTokenInfo("{{request.path}}", "The matched request's path, e.g. /v1/users/42"),
    TemplateTokenInfo("{{request.method}}", "The matched request's HTTP method, e.g. POST"),
    TemplateTokenInfo("{{request.url}}", "The matched request's full URL"),
    TemplateTokenInfo("{{request.header.NAME}}", "A request header's value by name (replace NAME); case-insensitive"),
    TemplateTokenInfo("{{request.query.NAME}}", "A query-string parameter's value by name (replace NAME)"),
)

/** Legacy three built-ins, kept for parity with `EnvironmentRepository.substitute`. */
private val LEGACY_TEMPLATE_TOKENS: List<TemplateTokenInfo> = listOf(
    TemplateTokenInfo("{{TIMESTAMP}}", "Epoch milliseconds at expansion time"),
    TemplateTokenInfo("{{RANDOM}}", "A random integer (legacy alias, same family as {{\$randomInt}})"),
    TemplateTokenInfo("{{UUID}}", "A freshly generated random UUID (legacy alias for {{\$guid}})"),
)

/**
 * Every Postman-style `{{\$name}}` dynamic generator [DynamicVariables] supports, read straight
 * from [DynamicVariables.supportedNames] so this list can never silently drift out of sync with
 * the real generator table as new ones are added there.
 */
fun dynamicGeneratorTemplateTokens(): List<TemplateTokenInfo> =
    DynamicVariables.supportedNames.sorted().map { name ->
        TemplateTokenInfo("{{\$$name}}", "Dynamic generator -- produces a fresh value on every expansion")
    }

/**
 * The full placeholder cheat sheet shown in the rule editor: built-in `request.*` tokens, the
 * legacy trio, every dynamic generator, and finally -- listed last since they're specific to
 * this one rule, not global built-ins -- one entry per non-blank key in [userVariableKeys] (this
 * rule's own `variables` map, as currently being edited).
 */
fun templateCheatSheet(userVariableKeys: List<String>): List<TemplateTokenInfo> {
    val userTokens = userVariableKeys
        .filter { it.isNotBlank() }
        .distinct()
        .map { TemplateTokenInfo("{{$it}}", "This rule's own variable") }
    return BUILTIN_REQUEST_TEMPLATE_TOKENS + LEGACY_TEMPLATE_TOKENS + dynamicGeneratorTemplateTokens() + userTokens
}

/**
 * Inserts [insertion] at [cursorIndex] within [text], returning the new full text and a new
 * cursor index positioned immediately *after* the inserted text (so tapping the same cheat-sheet
 * token twice appends two copies in order, rather than typing "into" the first one).
 * [cursorIndex] is clamped into `[0, text.length]` first, so a stale/out-of-range caret position
 * can never throw a [StringIndexOutOfBoundsException].
 */
fun insertTextAtCursor(text: String, cursorIndex: Int, insertion: String): Pair<String, Int> {
    val safeIndex = cursorIndex.coerceIn(0, text.length)
    val newText = text.substring(0, safeIndex) + insertion + text.substring(safeIndex)
    return newText to (safeIndex + insertion.length)
}

/**
 * Collapses the rule editor's editable key/value rows into the map that gets serialized into
 * the `"variables"` config key. Blank keys are dropped (an editor row left empty while the user
 * is still typing must never become a spurious `"": "..."` entry); a duplicate key keeps the
 * *last* row's value, matching how a plain `Map` build would behave and how `org.json.JSONObject`
 * itself resolves duplicate keys when parsed back.
 */
fun variableRowsToMap(rows: List<Pair<String, String>>): Map<String, String> =
    rows.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }

/** Parses [baseConfigJson] as a JSON object, or starts a fresh empty one if it's blank/invalid. */
private fun parseConfigJsonOrEmpty(baseConfigJson: String?): JSONObject =
    if (baseConfigJson.isNullOrBlank()) {
        JSONObject()
    } else {
        runCatching { JSONObject(baseConfigJson) }.getOrElse { JSONObject() }
    }

/**
 * Sets/replaces the `"variables"` key of a rule's `config` JSON text (the same string persisted
 * as `InterceptRuleEntity.replacementValue` for every templatable action -- see
 * [com.example.interception.RuleEnforcer]'s KDoc) with [variables], preserving every *other* key
 * already present in [baseConfigJson] untouched (`addHeaders`, `removeHeaders`, `bodyReplace`,
 * `statusCode`, `script`, ...). An empty [variables] map removes the `"variables"` key entirely
 * rather than persisting an empty object, so a rule with no variables round-trips back to
 * exactly the config a user without this feature would have written.
 *
 * A blank or unparseable [baseConfigJson] starts from an empty object rather than throwing --
 * matching [com.example.interception.RuleEnforcer]'s own lenient config-parsing convention
 * elsewhere. That does mean a genuinely non-JSON [baseConfigJson] (e.g. stray literal text a
 * user typed before this feature existed) has its un-parseable content discarded when variables
 * are merged in -- unavoidable since there's nothing structured to preserve, and that content
 * was already inert at runtime (`RuleEnforcer`'s own `JSONObject(config)` parse would have
 * thrown and been silently swallowed too).
 */
fun mergeVariablesIntoConfigJson(baseConfigJson: String?, variables: Map<String, String>): String {
    val json = parseConfigJsonOrEmpty(baseConfigJson)
    if (variables.isEmpty()) {
        json.remove("variables")
    } else {
        val varsJson = JSONObject()
        variables.forEach { (key, value) -> if (key.isNotBlank()) varsJson.put(key, value) }
        json.put("variables", varsJson)
    }
    return json.toString()
}

/**
 * Sets/replaces the `"script"` key of a rule's `config` JSON text -- [RuleAction.SCRIPT_REWRITE]'s
 * own config schema (`{"script": "<js source>", "variables": {...}}`, see
 * [com.example.interception.RuleEnforcer.applyScriptRewrite]'s KDoc) -- preserving every other
 * key already present in [baseConfigJson]. A blank [script] removes the key entirely rather than
 * persisting an empty string, matching [mergeVariablesIntoConfigJson]'s "absent, not empty"
 * convention for an unset field.
 */
fun mergeScriptIntoConfigJson(baseConfigJson: String?, script: String): String {
    val json = parseConfigJsonOrEmpty(baseConfigJson)
    if (script.isBlank()) {
        json.remove("script")
    } else {
        json.put("script", script)
    }
    return json.toString()
}

/** Example request headers used by [templatePreviewContext] -- fixed so the preview is stable. */
private val PREVIEW_EXAMPLE_HEADERS: Map<String, String> = linkedMapOf(
    "Authorization" to "Bearer abc123",
    "X-Request-Id" to "example-request-id",
)

/** The fixed, human-readable description of [templatePreviewContext], shown above the preview. */
const val TEMPLATE_PREVIEW_EXAMPLE_DESCRIPTION: String =
    "POST https://api.example.com/v1/users/42?active=true&limit=10"

/**
 * A single fixed, representative example request the rule editor's live preview expands
 * `{{...}}` templates against, via the real [RuleTemplateExpander.expand] (never a
 * reimplementation of it). Deliberately not derived from any real captured traffic -- a rule is
 * edited before it has ever matched anything, so there is no real "the request" to preview
 * against; a clearly-labeled synthetic example (see [TEMPLATE_PREVIEW_EXAMPLE_DESCRIPTION])
 * exercises every built-in `{{request.*}}` token (a header, a query parameter, host/path/method/
 * url) without pretending to be a real transaction.
 */
fun templatePreviewContext(): RuleTemplateExpander.Context = RuleTemplateExpander.Context.fromRequestUrl(
    requestUrl = "https://api.example.com/v1/users/42?active=true&limit=10",
    method = "POST",
    headers = PREVIEW_EXAMPLE_HEADERS,
)
