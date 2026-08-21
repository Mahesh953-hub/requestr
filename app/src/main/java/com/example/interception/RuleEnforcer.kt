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
            }
        }.onFailure { Log.w(TAG, "Failed to apply response modification: ${it.message}") }
    }
}
