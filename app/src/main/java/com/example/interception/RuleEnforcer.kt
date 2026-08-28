package com.example.interception

import android.util.Log
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.interception.http.ParsedHttpMessage
import com.example.scripting.JsScriptEngine
import com.example.scripting.PmBridgeBase
import com.example.scripting.PmPrelude
import kotlinx.coroutines.runBlocking

/**
 * Shared intercept-rule engine used by every capture path (CaptureEngine's tun
 * pipeline and MitmProxyEngine's decrypted TLS stream) so BLOCK / REDIRECT /
 * MODIFY_REQUEST / MODIFY_RESPONSE / LOG_ONLY behave identically no matter
 * which engine parsed the transaction.
 *
 * Rule matching: `domain` is a case-insensitive substring match against the
 * host; `pattern` is a case-insensitive regex matched against the full URL.
 * The first matching enabled rule wins.
 */
object RuleEnforcer {

    private const val TAG = "RuleEnforcer"

    /**
     * Check enabled intercept rules against a request URL/host.
     * Returns the first matching rule's action, or [RuleAction.LOG_ONLY] when
     * no rule matches.
     */
    fun checkRules(db: RequestrDatabase, url: String, host: String): RuleAction {
        return getMatchingRule(db, url, host)?.action ?: RuleAction.LOG_ONLY
    }

    /** Returns the first matching enabled rule entity (for accessing replacementValue). */
    fun getMatchingRule(db: RequestrDatabase, url: String, host: String): InterceptRuleEntity? {
        val rules: List<InterceptRuleEntity> = runCatching {
            runBlocking { db.interceptRuleDao().getEnabledRules() }
        }.getOrDefault(emptyList())

        for (rule in rules) {
            val matchesDomain = rule.domain.isNotEmpty() && host.contains(rule.domain, ignoreCase = true)
            val matchesPattern = rule.pattern.isNotEmpty() && runCatching {
                Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(url)
            }.getOrDefault(false)

            if (matchesDomain || matchesPattern) {
                Log.d(TAG, "Rule '${rule.name}' matched: action=${rule.action.name}")
                return rule
            }
        }
        return null
    }

    /**
     * Applies request modifications from a rule's replacementValue (JSON config).
     * Format: {"addHeaders":{"X-Custom":"value"}, "removeHeaders":["Cookie"], "bodyReplace":"new body"}
     */
    fun applyRequestModification(request: ParsedHttpMessage, config: String) {
        runCatching {
            val json = org.json.JSONObject(config)
            if (json.has("addHeaders")) {
                val headers = json.getJSONObject("addHeaders")
                val mutable = request.headers.toMutableMap()
                headers.keys().forEach { key -> mutable[key] = headers.getString(key) }
                request.headers = mutable
            }
            if (json.has("removeHeaders")) {
                val toRemove = json.getJSONArray("removeHeaders")
                val mutable = request.headers.toMutableMap()
                for (i in 0 until toRemove.length()) {
                    mutable.remove(toRemove.getString(i))
                }
                request.headers = mutable
            }
            if (json.has("bodyReplace")) {
                // §3 LOW-ADD: a `bodyReplace` value of the form `file://<path>` is resolved to
                // that file's contents instead of used literally -- see MockFixtureLoader's
                // KDoc. Every existing rule's plain-string bodyReplace passes through unchanged.
                request.body = MockFixtureLoader.resolveBodyReplace(json.getString("bodyReplace")).orEmpty()
                request.bodyReplaced = true
            }
        }.onFailure { Log.w(TAG, "Failed to apply request modification: ${it.message}") }
    }

    /**
     * Applies response modifications from a rule's replacementValue (JSON config).
     * Format: {"statusCode":200, "addHeaders":{"X-Mock":"true"}, "bodyReplace":"{}"}
     */
    fun applyResponseModification(response: ParsedHttpMessage, config: String) {
        runCatching {
            val json = org.json.JSONObject(config)
            if (json.has("statusCode")) {
                response.statusCode = json.getInt("statusCode")
            }
            if (json.has("addHeaders")) {
                val headers = json.getJSONObject("addHeaders")
                val mutable = response.headers.toMutableMap()
                headers.keys().forEach { key -> mutable[key] = headers.getString(key) }
                response.headers = mutable
            }
            if (json.has("bodyReplace")) {
                // §3 LOW-ADD: same `file://` fixture indirection as applyRequestModification.
                response.body = MockFixtureLoader.resolveBodyReplace(json.getString("bodyReplace")).orEmpty()
                response.bodyReplaced = true
            }
        }.onFailure { Log.w(TAG, "Failed to apply response modification: ${it.message}") }
    }

    /**
     * Master plan §1.11 — [RuleAction.SCRIPT_REWRITE]: runs a real `pm.*` JavaScript against a
     * single live [message] (request or response, dispatched on [ParsedHttpMessage.isRequest])
     * in place, via the same [JsScriptEngine]/[PmPrelude] engine ApiTestr's pre/post scripts use
     * (`com.example.scripting.ApiTestrScriptRunner`). Deliberately additive, not a replacement
     * for [applyRequestModification]/[applyResponseModification]'s fixed
     * `{"addHeaders","removeHeaders","bodyReplace"}` schema -- both remain fully supported so
     * existing rules built before scripting existed keep working unchanged.
     *
     * `config` schema: `{"script": "<js source>", "variables": {"key": "value"}}`. `variables`
     * seeds `pm.environment`/`pm.variables` for `{{var}}` templating -- a `SCRIPT_REWRITE` rule
     * has no [com.example.data.env.Environment] of its own, so this is its only variable source
     * (in addition to the process-wide `pm.globals` every script run shares, and the built-in
     * `{{$name}}` dynamic generators). A blank/missing `script` or malformed `config` is a no-op
     * (the message passes through unmodified) rather than a thrown exception, matching
     * [applyRequestModification]/[applyResponseModification]'s fail-open behavior for a broken
     * rule config.
     */
    fun applyScriptRewrite(message: ParsedHttpMessage, config: String?) {
        if (config.isNullOrBlank()) return
        runCatching {
            val json = org.json.JSONObject(config)
            val script = json.optString("script", "")
            if (script.isBlank()) return@runCatching
            val variables = LinkedHashMap<String, String>()
            if (json.has("variables")) {
                val varsJson = json.getJSONObject("variables")
                varsJson.keys().forEach { key -> variables[key] = varsJson.getString(key) }
            }

            val bridge = object : PmBridgeBase(variables) {
                override fun requestGetUrl(): String = if (message.isRequest) message.url else ""
                override fun requestSetUrl(value: String) {
                    if (message.isRequest) message.url = value
                }
                override fun requestGetMethod(): String = message.method?.name ?: ""
                override fun requestSetMethod(value: String) {}
                override fun requestGetHeader(name: String): String? =
                    if (message.isRequest) headerLookup(message, name) else null
                override fun requestSetHeader(name: String, value: String) {
                    if (message.isRequest) setHeader(message, name, value)
                }
                override fun requestRemoveHeader(name: String) {
                    if (message.isRequest) removeHeader(message, name)
                }
                override fun requestHeaderNames(): Array<String> =
                    if (message.isRequest) message.headers.keys.toTypedArray() else emptyArray()
                override fun requestGetBody(): String = if (message.isRequest) message.body else ""
                override fun requestSetBody(value: String) {
                    if (message.isRequest) {
                        message.body = value
                        message.bodyReplaced = true
                    }
                }

                override fun responseGetStatusCode(): Int = if (!message.isRequest) message.statusCode else 0
                override fun responseSetStatusCode(value: Int) {
                    if (!message.isRequest) message.statusCode = value
                }
                override fun responseGetStatusMessage(): String = if (!message.isRequest) message.statusMessage else ""
                override fun responseGetHeader(name: String): String? =
                    if (!message.isRequest) headerLookup(message, name) else null
                override fun responseSetHeader(name: String, value: String) {
                    if (!message.isRequest) setHeader(message, name, value)
                }
                override fun responseRemoveHeader(name: String) {
                    if (!message.isRequest) removeHeader(message, name)
                }
                override fun responseHeaderNames(): Array<String> =
                    if (!message.isRequest) message.headers.keys.toTypedArray() else emptyArray()
                override fun responseGetBody(): String = if (!message.isRequest) message.body else ""
                override fun responseSetBody(value: String) {
                    if (!message.isRequest) {
                        message.body = value
                        message.bodyReplaced = true
                    }
                }
                override fun responseGetResponseTimeMs(): Long = 0
            }

            val result = JsScriptEngine.eval(script, PmPrelude.SOURCE, bridge)
            if (!result.ok) {
                Log.w(TAG, "SCRIPT_REWRITE script error: ${result.error}")
            }
        }.onFailure { Log.w(TAG, "Failed to apply script rewrite: ${it.message}") }
    }

    private fun headerLookup(message: ParsedHttpMessage, name: String): String? =
        message.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun setHeader(message: ParsedHttpMessage, name: String, value: String) {
        val mutable = message.headers.toMutableMap()
        val existingKey = mutable.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        mutable[existingKey] = value
        message.headers = mutable
    }

    private fun removeHeader(message: ParsedHttpMessage, name: String) {
        val mutable = message.headers.toMutableMap()
        mutable.keys.removeAll { it.equals(name, ignoreCase = true) }
        message.headers = mutable
    }

    /**
     * Master plan §1.5 — builds the synthesized response a [RuleAction.MOCK_RESPONSE] rule
     * answers with, from the rule's `replacementValue`.
     *
     * Reuses the exact same `{"statusCode","addHeaders","bodyReplace"}` schema
     * [applyResponseModification] already understands, so a user who has written a
     * MODIFY_RESPONSE rule can flip its action to MOCK_RESPONSE without rewriting the config.
     * The difference is *when* it applies: MODIFY_RESPONSE rewrites a real response after the
     * upstream round trip has already completed, whereas MOCK_RESPONSE is answered before any
     * upstream connection is made at all (plan Gap #11 — "every MITM rule action except BLOCK
     * still dials the real upstream server").
     *
     * Defaults, applied when a key is absent or the config is unparseable (a broken config must
     * still produce a valid HTTP message rather than hanging the client):
     *  - status `200 OK`, empty body
     *  - `Content-Type: application/json; charset=utf-8` when the body parses as a JSON
     *    object/array, `text/plain; charset=utf-8` otherwise — never guessed over a
     *    `Content-Type` the user set explicitly in `addHeaders`
     *  - `Content-Length` always recomputed from the real body bytes, overriding any
     *    user-supplied value, because a wrong length desynchronizes the connection
     *
     * Header names are lowercased to match [com.example.interception.http.HttpParser]'s
     * convention, so downstream serialization and lookups behave identically to a parsed
     * real response.
     */
    fun buildMockResponse(config: String?, requestUrl: String = ""): ParsedHttpMessage {
        var statusCode = 200
        var statusMessage = "OK"
        var body = ""
        val headers = linkedMapOf<String, String>()

        if (!config.isNullOrBlank()) {
            runCatching {
                val json = org.json.JSONObject(config)
                if (json.has("statusCode")) statusCode = json.getInt("statusCode")
                if (json.has("statusMessage")) statusMessage = json.getString("statusMessage")
                if (json.has("bodyReplace")) body = MockFixtureLoader.resolveBodyReplace(json.getString("bodyReplace")).orEmpty()
                if (json.has("addHeaders")) {
                    val added = json.getJSONObject("addHeaders")
                    added.keys().forEach { key -> headers[key.lowercase()] = added.getString(key) }
                }
            }.onFailure { Log.w(TAG, "Malformed MOCK_RESPONSE config, using defaults: ${it.message}") }
        }

        if (!headers.containsKey("content-type")) {
            headers["content-type"] =
                if (looksLikeJson(body)) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
        }
        headers["content-length"] = body.toByteArray(Charsets.UTF_8).size.toString()

        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            url = requestUrl,
            statusCode = statusCode,
            statusMessage = statusMessage,
            headers = headers,
            body = body,
        )
    }

    /** True when [body] is a parseable JSON object or array, used only to pick a default content type. */
    private fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            when (trimmed.first()) {
                '{' -> { org.json.JSONObject(trimmed); true }
                '[' -> { org.json.JSONArray(trimmed); true }
                else -> false
            }
        }.getOrDefault(false)
    }

    /**
     * Result of [applyChaos] -- what the calling live engine (currently
     * [com.example.interception.MitmProxyEngine]) should actually do to answer the client.
     * Kept separate from [ChaosInjector.ChaosOutcome] because [Fail] needs a ready-to-serialize
     * [ParsedHttpMessage] the caller can forward instead of the real response, which
     * [ChaosInjector] (a pure, engine-agnostic decision helper) deliberately has no knowledge of.
     */
    sealed interface ChaosApplyResult {
        /** No chaos triggered (including a null/blank/malformed config) -- forward the real response as-is. */
        data object PassThrough : ChaosApplyResult
        /** [message] was mutated in place (body byte-corrupted); forward it re-serialized. */
        data object Applied : ChaosApplyResult
        /** Caller should sleep [millis] before forwarding the real (unmutated) response. */
        data class Delay(val millis: Long) : ChaosApplyResult
        /** Caller should forward [failureResponse] INSTEAD of the real response. */
        data class Fail(val failureResponse: ParsedHttpMessage) : ChaosApplyResult
    }

    /**
     * Master plan §3 LOW-ADD — [RuleAction.CHAOS]: rolls the dice once per matched response via
     * [ChaosInjector.decide] and reports what the caller should do. `message` is mutated in
     * place only for [ChaosApplyResult.Applied] (body corruption) -- [ChaosApplyResult.Delay]
     * and [ChaosApplyResult.Fail] are reported, not acted on here, because sleeping the calling
     * thread and choosing whether to answer with a synthetic error vs. drop the connection
     * outright are both live-engine decisions, not [RuleEnforcer]'s to make (same "caller does
     * the real side effect" split [ChaosInjector]'s own KDoc documents).
     *
     * `config` schema: `{"failRate":0.1,"failStatusCode":503,"failStatusMessage":"...",
     * "failBody":"...","delayMs":[100,3000],"corruptBodyRate":0.05}` -- see [parseChaosConfig].
     * [random] is injectable so tests are deterministic; a null/blank/malformed config, or any
     * exception while parsing/deciding, fails open to [ChaosApplyResult.PassThrough], matching
     * every other rule action's fail-open behavior for a broken config.
     */
    fun applyChaos(
        message: ParsedHttpMessage,
        config: String?,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): ChaosApplyResult {
        if (config.isNullOrBlank()) return ChaosApplyResult.PassThrough
        return runCatching {
            when (val outcome = ChaosInjector.decide(parseChaosConfig(config), random)) {
                is ChaosInjector.ChaosOutcome.Fail -> ChaosApplyResult.Fail(buildChaosFailureResponse(config))
                is ChaosInjector.ChaosOutcome.Delay -> ChaosApplyResult.Delay(outcome.millis)
                is ChaosInjector.ChaosOutcome.CorruptBody -> {
                    message.body = ChaosInjector.corrupt(message.body, random)
                    message.bodyReplaced = true
                    ChaosApplyResult.Applied
                }
                ChaosInjector.ChaosOutcome.PassThrough -> ChaosApplyResult.PassThrough
            }
        }.getOrElse {
            Log.w(TAG, "Failed to apply chaos: ${it.message}")
            ChaosApplyResult.PassThrough
        }
    }

    /**
     * Parses a [RuleAction.CHAOS] rule's `replacementValue` into a [ChaosInjector.ChaosConfig].
     * `delayMs` is a two-element `[min, max]` array (inclusive); a single-element or missing
     * array is treated as no delay. Every field is optional and clamped/defaulted so a partial
     * or malformed config degrades to an all-zero (always-[ChaosInjector.ChaosOutcome.PassThrough])
     * config rather than throwing -- fail-open, same discipline as every schema this file parses.
     */
    internal fun parseChaosConfig(config: String?): ChaosInjector.ChaosConfig {
        if (config.isNullOrBlank()) return ChaosInjector.ChaosConfig()
        return runCatching {
            val json = org.json.JSONObject(config)
            val failRate = json.optDouble("failRate", 0.0).coerceIn(0.0, 1.0)
            val corruptBodyRate = json.optDouble("corruptBodyRate", 0.0).coerceIn(0.0, 1.0)
            val delayRange = if (json.has("delayMs")) {
                val arr = json.getJSONArray("delayMs")
                val lo = arr.optLong(0, 0L).coerceAtLeast(0L)
                val hi = (if (arr.length() > 1) arr.optLong(1, lo) else lo).coerceAtLeast(lo)
                lo..hi
            } else {
                0L..0L
            }
            ChaosInjector.ChaosConfig(failRate = failRate, delayMsRange = delayRange, corruptBodyRate = corruptBodyRate)
        }.getOrElse {
            Log.w(TAG, "Malformed CHAOS config, disabling chaos: ${it.message}")
            ChaosInjector.ChaosConfig()
        }
    }

    /**
     * Builds the synthetic error [ParsedHttpMessage] a [ChaosApplyResult.Fail] outcome answers
     * with. Defaults (`503 Service Unavailable`, a small JSON error body) are overridable via
     * the same `config` the rest of the CHAOS schema lives in (`failStatusCode`/
     * `failStatusMessage`/`failBody`), so one rule config can tune both the failure probability
     * and what the synthesized failure actually looks like.
     */
    private fun buildChaosFailureResponse(config: String?): ParsedHttpMessage {
        var statusCode = 503
        var statusMessage = "Service Unavailable"
        var body = "{\"error\":\"chaos-injected failure\"}"
        if (!config.isNullOrBlank()) {
            runCatching {
                val json = org.json.JSONObject(config)
                if (json.has("failStatusCode")) statusCode = json.getInt("failStatusCode")
                if (json.has("failStatusMessage")) statusMessage = json.getString("failStatusMessage")
                if (json.has("failBody")) body = json.getString("failBody")
            }
        }
        val headers = linkedMapOf(
            "content-type" to "application/json; charset=utf-8",
            "content-length" to body.toByteArray(Charsets.UTF_8).size.toString(),
        )
        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            statusCode = statusCode,
            statusMessage = statusMessage,
            headers = headers,
            body = body,
            bodyReplaced = true,
        )
    }
}
