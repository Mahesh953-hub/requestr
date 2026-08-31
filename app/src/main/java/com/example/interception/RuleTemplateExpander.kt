package com.example.interception

import com.example.scripting.DynamicVariables
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import kotlin.random.Random

/**
 * Master plan §3 (C10) — "`{{var}}` environment templating for the live rewrite-rule engine",
 * distinct from ApiTestr's own already-working `{{VAR}}` substitution
 * ([com.example.data.env.EnvironmentRepository.substitute]) and from [RuleEnforcer]'s
 * `SCRIPT_REWRITE` action's JS-side `pm.variables.replaceIn`
 * ([com.example.scripting.PmBridgeBase.templateString]) -- this is the templating pass for the
 * *other* three rule actions (`MODIFY_REQUEST`/`MODIFY_RESPONSE`/`MOCK_RESPONSE`), whose
 * `addHeaders`/`bodyReplace` config values were, until now, always used completely literally.
 *
 * Deliberately does **not** depend on [com.example.data.env.EnvironmentRepository] (a mutable,
 * Android-`Context`-backed process-wide singleton -- unsuitable for a pure function, and its
 * shared mutable state would make tests non-deterministic across a JVM test run). Instead,
 * user-defined variables come from the same explicit `"variables": {...}` JSON object already
 * accepted by [RuleEnforcer.applyScriptRewrite]'s config schema, extended here to the other three
 * actions' config schema too -- one convention, no parallel variable store.
 *
 * Resolution order for a `{{token}}` (first match wins; an unrecognized token is left completely
 * untouched -- a typo must be visibly diagnosable, never silently blanked):
 *  1. `\{{literal}}` (a backslash immediately before `{{`) — always renders as the literal text
 *     `{{literal}}`, with the backslash itself dropped; never expanded, no matter what follows.
 *  2. `{{$name}}` Postman-style dynamic generators (`$guid`, `$isoTimestamp`, `$randomInt`, ...)
 *     via [DynamicVariables] -- the same table [PmBridgeBase] and
 *     [com.example.data.env.EnvironmentRepository] already share.
 *  3. `{{request.host}}` / `{{request.path}}` / `{{request.method}}` / `{{request.url}}` /
 *     `{{request.header.NAME}}` (case-insensitive) / `{{request.query.NAME}}` -- built-ins
 *     describing the in-flight request, from [context]. Any built-in [context] does not have a
 *     value for (e.g. `{{request.method}}` when only a bare URL was available, as in
 *     [RuleEnforcer.buildMockResponse]'s call site) is left untouched rather than rendered blank.
 *  4. Legacy `{{TIMESTAMP}}` (epoch-millis, from [clock]) / `{{RANDOM}}` (from [random]) /
 *     `{{UUID}}` (from [random]) -- kept for parity with
 *     [com.example.data.env.EnvironmentRepository.substitute]'s longstanding three built-ins, so
 *     a rule author who already knows those names from ApiTestr gets the same behavior here.
 *  5. [variables] -- the rule config's own user-defined `"variables"` map.
 *  6. Left untouched.
 */
object RuleTemplateExpander {

    /**
     * Message-context built-ins available to [expand], independent of [variables]. Every field
     * defaults to `null` ("not known in this call site") rather than `""`, so an unavailable
     * built-in is left as an intact, visibly-unexpanded `{{request.*}}` token instead of silently
     * rendering as an empty string.
     */
    data class Context(
        val host: String? = null,
        val path: String? = null,
        val method: String? = null,
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
    ) {
        companion object {
            val EMPTY = Context()

            /**
             * Builds a [Context] from a live [ParsedHttpMessage] (the `MODIFY_REQUEST`/
             * `MODIFY_RESPONSE` shape). Prefers the message's own already-parsed [ParsedHttpMessage.host]/
             * [ParsedHttpMessage.path] (request messages populate both at parse time); falls back to
             * re-deriving them from [ParsedHttpMessage.url] only if those are blank (a response
             * message has no `path`/`host` of its own -- see that field's KDoc -- so its
             * `{{request.*}}` tokens degrade to "left untouched" rather than a wrong value).
             */
            fun fromMessage(message: com.example.interception.http.ParsedHttpMessage): Context {
                val parsedUrl = message.url.toHttpUrlOrNull()
                return Context(
                    host = message.host.ifEmpty { parsedUrl?.host },
                    path = message.path.ifEmpty { parsedUrl?.encodedPath },
                    method = message.method?.name,
                    url = message.url.ifEmpty { null },
                    headers = message.headers,
                )
            }

            /**
             * Builds a [Context] from a bare request URL string -- [RuleEnforcer.buildMockResponse]'s
             * shape, where no live [ParsedHttpMessage] exists yet since the mock IS the response
             * (there is no separate "the request" object to point at). `method`/`headers` are
             * whatever the caller happens to have on hand (both optional; a `MOCK_RESPONSE` call
             * site with only a URL simply won't be able to expand those specific tokens).
             */
            fun fromRequestUrl(requestUrl: String, method: String? = null, headers: Map<String, String> = emptyMap()): Context {
                if (requestUrl.isBlank()) return Context(method = method, headers = headers)
                val parsedUrl = requestUrl.toHttpUrlOrNull()
                return Context(
                    host = parsedUrl?.host,
                    path = parsedUrl?.encodedPath,
                    method = method,
                    url = requestUrl,
                    headers = headers,
                )
            }
        }
    }

    private val ESCAPE_SENTINEL = "\u0000RULE_TEMPLATE_ESCAPED_BRACE\u0000"
    private val TOKEN_PATTERN = Regex("""\{\{\s*([^{}]+?)\s*}}""")

    /**
     * Expands every recognized `{{...}}` placeholder in [template]. See the class KDoc for the
     * full resolution order. Pure and side-effect-free: [clock] and [random] are the only sources
     * of non-determinism, both injectable so callers (and tests) can pin them down exactly.
     *
     * A no-op fast path (`template` unchanged) is returned whenever [template] contains no `{{`
     * at all, so applying this to every header/body value -- most of which have no placeholders --
     * stays effectively free.
     */
    fun expand(
        template: String,
        variables: Map<String, String> = emptyMap(),
        context: Context = Context.EMPTY,
        clock: () -> Long = System::currentTimeMillis,
        random: Random = Random.Default,
    ): String {
        if (!template.contains("{{")) return template

        var working = template.replace("\\{{", ESCAPE_SENTINEL)
        working = DynamicVariables.substitute(working)
        working = TOKEN_PATTERN.replace(working) { match ->
            resolveToken(match.groupValues[1], variables, context, clock, random) ?: match.value
        }
        return working.replace(ESCAPE_SENTINEL, "{{")
    }

    private fun resolveToken(
        key: String,
        variables: Map<String, String>,
        context: Context,
        clock: () -> Long,
        random: Random,
    ): String? = when {
        key == "request.host" -> context.host
        key == "request.path" -> context.path
        key == "request.method" -> context.method
        key == "request.url" -> context.url
        key.startsWith("request.header.") -> headerLookup(context.headers, key.removePrefix("request.header."))
        key.startsWith("request.query.") -> queryLookup(context.url, key.removePrefix("request.query."))
        key == "TIMESTAMP" -> clock().toString()
        key == "RANDOM" -> random.nextInt(1000, 100_000).toString()
        key == "UUID" -> UUID(random.nextLong(), random.nextLong()).toString()
        variables.containsKey(key) -> variables[key]
        else -> null
    }

    private fun headerLookup(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun queryLookup(url: String?, name: String): String? {
        if (url == null) return null
        return url.toHttpUrlOrNull()?.queryParameter(name)
    }
}
