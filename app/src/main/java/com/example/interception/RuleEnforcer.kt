package com.example.interception

import android.util.Log
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.interception.http.ParsedHttpMessage
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
                request.body = json.getString("bodyReplace")
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
                response.body = json.getString("bodyReplace")
                response.bodyReplaced = true
            }
        }.onFailure { Log.w(TAG, "Failed to apply response modification: ${it.message}") }
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
                if (json.has("bodyReplace")) body = json.getString("bodyReplace")
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
}
