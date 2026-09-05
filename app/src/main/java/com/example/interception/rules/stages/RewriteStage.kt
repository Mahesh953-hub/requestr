package com.example.interception.rules.stages

import android.util.Log
import com.example.interception.MockFixtureLoader
import com.example.interception.RuleTemplateExpander
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BodySource
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.Matched
import com.example.interception.rules.MessageReplacement
import com.example.interception.rules.Modification
import com.example.interception.rules.RedirectSpec
import com.example.interception.rules.ResolvedRewrite
import com.example.interception.rules.RewriteConfig
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * S4 task 26/29: the REWRITE slot applier. Order per message is fixed:
 * **redirection → replacement → modifications**, where at most one redirection applies (the
 * pipeline already picked it), at most one replacement per direction, and *every* matching
 * rule's modifications run in list order with that rule's own `variables` for templating.
 *
 * Byte-level parity with the legacy `RuleEnforcer.applyRequestModification` /
 * `applyResponseModification` / REDIRECT branches is a G16 gate ([LegacyParityTest]):
 *  - [Modification.SetHeader] writes the header under the name as given (the legacy map put);
 *    [Modification.RemoveHeader] removes the exact key (legacy `remove(name)`).
 *  - [Modification.ReplaceBody] resolves the (templated) value through [MockFixtureLoader] --
 *    `file://` fixtures byte-exact, anything else as text -- and a broken fixture leaves the body
 *    untouched, exactly as before.
 *  - A redirection sets `url`, `host` (from the target URI) and the `host` header, unless
 *    [RedirectSpec.preserveHost] keeps the original Host header.
 *
 * Content-Length is NOT recomputed here: `MitmProxyEngine.serializeRequest/serializeResponse`
 * already recompute it unconditionally from the bytes they emit for a rewritten message.
 */
object RewriteStage {
    private const val TAG = "RewriteStage"

    /** Whether anything was changed (so the caller knows to re-serialize) and how many steps ran. */
    data class Result(val changed: Boolean, val stepsApplied: Int) {
        companion object {
            val UNCHANGED = Result(changed = false, stepsApplied = 0)
        }
    }

    fun applyRequest(request: ParsedHttpMessage, rewrite: ResolvedRewrite, trail: FlowAuditTrail? = null): Result {
        if (rewrite.isEmpty) return Result.UNCHANGED
        var changed = false
        var steps = 0
        rewrite.redirection?.let { matched ->
            val spec = matched.config.redirection ?: return@let
            if (applyRedirect(request, spec, matched.config.variables)) {
                changed = true; steps++
                trail?.applied(matched.rule, FlowAuditTrail.Phase.REQUEST, "redirect -> ${request.url.take(120)}")
            }
        }
        rewrite.replaceRequest?.let { matched ->
            val replacement = matched.config.replaceRequest ?: return@let
            applyReplacement(request, replacement, matched.config.variables)
            changed = true; steps++
            trail?.applied(matched.rule, FlowAuditTrail.Phase.REQUEST, "replace request")
        }
        for (matched in rewrite.rules) {
            val mods = matched.config.requestModifications
            if (mods.isEmpty()) continue
            val applied = applyModifications(request, mods, matched.config.variables, matched.rule.name)
            if (applied > 0) {
                changed = true; steps += applied
                trail?.applied(matched.rule, FlowAuditTrail.Phase.REQUEST, "$applied request modification(s)")
            }
        }
        return Result(changed, steps)
    }

    fun applyResponse(response: ParsedHttpMessage, rewrite: ResolvedRewrite, trail: FlowAuditTrail? = null): Result {
        if (rewrite.isEmpty) return Result.UNCHANGED
        var changed = false
        var steps = 0
        rewrite.replaceResponse?.let { matched ->
            val replacement = matched.config.replaceResponse ?: return@let
            applyReplacement(response, replacement, matched.config.variables)
            changed = true; steps++
            trail?.applied(matched.rule, FlowAuditTrail.Phase.RESPONSE, "replace response")
        }
        for (matched in rewrite.rules) {
            val mods = matched.config.responseModifications
            if (mods.isEmpty()) continue
            val applied = applyModifications(response, mods, matched.config.variables, matched.rule.name)
            if (applied > 0) {
                changed = true; steps += applied
                trail?.applied(matched.rule, FlowAuditTrail.Phase.RESPONSE, "$applied response modification(s)")
            }
        }
        return Result(changed, steps)
    }

    /** Single-rule convenience (tests, CDP adapter): applies [config] alone to [message]. */
    fun applyRequest(request: ParsedHttpMessage, config: RewriteConfig): Result =
        applyRequest(request, single(config))

    fun applyResponse(response: ParsedHttpMessage, config: RewriteConfig): Result =
        applyResponse(response, single(config))

    private fun single(config: RewriteConfig): ResolvedRewrite {
        val m = Matched(com.example.interception.rules.RuleRef(0L, "", config.stage), config)
        return ResolvedRewrite(
            redirection = if (config.redirection != null) m else null,
            replaceRequest = if (config.replaceRequest != null) m else null,
            replaceResponse = if (config.replaceResponse != null) m else null,
            rules = listOf(m),
        )
    }

    // ── redirection ──────────────────────────────────────────────────────────────────────

    /**
     * Legacy REDIRECT parity: `request.url = target; request.host = URI(target).host ?: host;
     * headers["host"] = request.host`. [RedirectSpec.directoryMatch] appends the original path
     * (and query) to the target so a whole tree can be re-pointed with one rule.
     */
    fun applyRedirect(request: ParsedHttpMessage, spec: RedirectSpec, variables: Map<String, String> = emptyMap()): Boolean {
        val context = RuleTemplateExpander.Context.fromMessage(request)
        var target = RuleTemplateExpander.expand(spec.targetUrl, variables, context).trim()
        if (target.isEmpty()) return false
        if (spec.directoryMatch) {
            val original = runCatching { URI(request.url) }.getOrNull()
            val suffix = buildString {
                val path = original?.rawPath.orEmpty()
                append(path.removePrefix("/"))
                original?.rawQuery?.let { append('?').append(it) }
            }
            if (suffix.isNotEmpty()) target = target.trimEnd('/') + "/" + suffix
        }
        request.url = target
        runCatching { URI(target).host?.let { request.host = it } }
        if (!spec.preserveHost) {
            val headers = request.headers.toMutableMap()
            headers["host"] = request.host
            request.headers = headers
        }
        return true
    }

    // ── replacement ──────────────────────────────────────────────────────────────────────

    private fun applyReplacement(message: ParsedHttpMessage, replacement: MessageReplacement, variables: Map<String, String>) {
        val context = RuleTemplateExpander.Context.fromMessage(message)
        fun tmpl(value: String) = RuleTemplateExpander.expand(value, variables, context)
        if (!message.isRequest) replacement.statusCode?.let { message.statusCode = it }
        if (replacement.headers.isNotEmpty()) {
            val mutable = message.headers.toMutableMap()
            for ((k, v) in replacement.headers) mutable[k] = tmpl(v)
            message.headers = mutable
        }
        applyBody(message, replacement.body, ::tmpl, "replacement")
    }

    // ── modifications ────────────────────────────────────────────────────────────────────

    /** Applies [mods] in order; returns how many ran (a failed fixture still counts as attempted). */
    fun applyModifications(
        message: ParsedHttpMessage,
        mods: List<Modification>,
        variables: Map<String, String> = emptyMap(),
        ruleName: String = "",
    ): Int {
        if (mods.isEmpty()) return 0
        val context = RuleTemplateExpander.Context.fromMessage(message)
        fun tmpl(value: String) = RuleTemplateExpander.expand(value, variables, context)
        var applied = 0
        for (mod in mods) {
            runCatching {
                when (mod) {
                    is Modification.SetHeader -> {
                        val mutable = message.headers.toMutableMap()
                        mutable[mod.name] = tmpl(mod.value)
                        message.headers = mutable
                    }
                    is Modification.RemoveHeader -> {
                        val mutable = message.headers.toMutableMap()
                        mutable.remove(mod.name)
                        message.headers = mutable
                    }
                    is Modification.RegexHeader -> {
                        val key = message.headers.keys.firstOrNull { it.equals(mod.name, ignoreCase = true) }
                        if (key != null) {
                            val mutable = message.headers.toMutableMap()
                            mutable[key] = Regex(mod.pattern).replace(mutable[key]!!, tmpl(mod.replacement))
                            message.headers = mutable
                        }
                    }
                    is Modification.SetQueryParam -> if (message.isRequest) {
                        message.url = setQueryParam(message.url, mod.name, tmpl(mod.value))
                    }
                    is Modification.RemoveQueryParam -> if (message.isRequest) {
                        message.url = removeQueryParam(message.url, mod.name)
                    }
                    is Modification.RegexBody -> {
                        val replaced = Regex(mod.pattern).replace(message.body, tmpl(mod.replacement))
                        if (replaced != message.body || !message.bodyReplaced) {
                            message.body = replaced
                            message.replacedBodyBytes = null
                            message.bodyReplaced = true
                        }
                    }
                    is Modification.ReplaceBody -> applyBody(message, mod.body, ::tmpl, ruleName)
                    is Modification.SetStatus -> if (!message.isRequest) message.statusCode = mod.code
                }
                applied++
            }.onFailure { Log.w(TAG, "modification $mod failed (rule '$ruleName'): ${it.message}") }
        }
        return applied
    }

    /**
     * Legacy `bodyReplace` parity: the (templated) value goes through [MockFixtureLoader.resolve]
     * so a `file://` reference is loaded byte-exact and a broken one leaves the body untouched.
     * [BodySource.Empty] is the legacy `"bodyReplace": ""` -- an explicit empty body.
     */
    private fun applyBody(message: ParsedHttpMessage, source: BodySource, tmpl: (String) -> String, ruleName: String) {
        val raw = when (source) {
            BodySource.Empty -> ""
            is BodySource.Text -> source.value
            is BodySource.File -> if (source.path.startsWith(BodySource.FILE_SCHEME)) source.path else BodySource.FILE_SCHEME + source.path
        }
        val resolution = MockFixtureLoader.resolve(tmpl(raw))
        MockFixtureLoader.applyTo(message, resolution) { error ->
            Log.w(TAG, "bodyReplace fixture failed (rule '$ruleName'), body left unchanged: $error")
        }
    }

    // ── query helpers ────────────────────────────────────────────────────────────────────

    internal fun setQueryParam(url: String, name: String, value: String): String {
        val (base, query, fragment) = splitUrl(url)
        val params = parseQuery(query).filterNot { it.first == name }.toMutableList()
        params += name to value
        return joinUrl(base, params, fragment)
    }

    internal fun removeQueryParam(url: String, name: String): String {
        val (base, query, fragment) = splitUrl(url)
        val params = parseQuery(query).filterNot { it.first == name }
        return joinUrl(base, params, fragment)
    }

    private fun splitUrl(url: String): Triple<String, String, String?> {
        val hash = url.indexOf('#')
        val fragment = if (hash >= 0) url.substring(hash + 1) else null
        val noFrag = if (hash >= 0) url.substring(0, hash) else url
        val q = noFrag.indexOf('?')
        return if (q >= 0) Triple(noFrag.substring(0, q), noFrag.substring(q + 1), fragment) else Triple(noFrag, "", fragment)
    }

    private fun parseQuery(query: String): List<Pair<String, String>> {
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { part ->
            val eq = part.indexOf('=')
            if (eq >= 0) decode(part.substring(0, eq)) to decode(part.substring(eq + 1)) else decode(part) to ""
        }
    }

    private fun joinUrl(base: String, params: List<Pair<String, String>>, fragment: String?): String = buildString {
        append(base)
        if (params.isNotEmpty()) {
            append('?')
            append(params.joinToString("&") { (k, v) -> if (v.isEmpty()) encode(k) else "${encode(k)}=${encode(v)}" })
        }
        if (fragment != null) append('#').append(fragment)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
