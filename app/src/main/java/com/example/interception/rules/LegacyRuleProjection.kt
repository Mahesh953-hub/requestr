package com.example.interception.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * S4 task 34 -- the *reverse* of [LegacyRuleConverter]: a best-effort v1 view of a typed
 * [InterceptorRule], for readers that still understand only the flat `{pattern, domain, action,
 * replacementValue}` shape -- the MV3 browser extension's `enforcer.mjs` / `rules.mjs` and the
 * LAN dashboard's in-browser list. [RuleApiJson] appends these four fields to every typed rule it
 * emits so those readers keep working while the typed envelope is the one source of truth.
 *
 * The projection is lossy by design and only claims what v1 could express:
 *
 *  - `domain` is recovered from a single `*<domain>*` host glob (the exact shape the converter
 *    emits); any other glob list yields an empty domain. `pattern` is the `urlRegex`.
 *  - `action` maps stage/config back onto the eleven [LegacyRuleAction]s where one fits; a typed
 *    rule with no v1 equivalent (ALLOW_ONLY/BYPASS/SUSPEND gateways, MIRROR, BREAKPOINT,
 *    THROTTLE, mixed request+response rewrites, regex/query-param steps, replacements) is
 *    reported with `action = null` and callers emit the stage name instead, so a v1 reader sees
 *    an unknown action and does nothing -- the same fail-open it always had for names it did not
 *    know.
 *  - `replacementValue` is rebuilt in the v1 JSON dialect (`addHeaders`/`removeHeaders`/
 *    `bodyReplace`/`statusCode`/`script`/`variables`/chaos keys) so `parseRequestModification`
 *    and friends parse it exactly as before.
 */
object LegacyRuleProjection {

    data class Projection(
        val domain: String,
        val pattern: String,
        /** Null when the typed rule has no v1 equivalent. */
        val action: LegacyRuleAction?,
        val replacementValue: String?,
    )

    fun project(rule: InterceptorRule): Projection {
        val m = rule.matcher
        val domain = m.hostGlobs.singleOrNull()
            ?.takeIf { it.length > 2 && it.startsWith("*") && it.endsWith("*") && !it.substring(1, it.length - 1).contains('*') }
            ?.let { it.substring(1, it.length - 1) }
            .orEmpty()
        val pattern = m.urlRegex.orEmpty()
        val (action, value) = actionOf(rule.config)
        return Projection(domain = domain, pattern = pattern, action = action, replacementValue = value)
    }

    private fun actionOf(config: RuleConfig): Pair<LegacyRuleAction?, String?> = when (config) {
        is GatewayConfig -> when (config.mode) {
            GatewayMode.BLOCK -> LegacyRuleAction.BLOCK to null
            GatewayMode.FORCE_DECRYPT -> LegacyRuleAction.FORCE_MITM to null
            else -> null to null
        }
        is ObserveConfig -> LegacyRuleAction.LOG_ONLY to null
        is RewriteConfig -> rewrite(config)
        is MockConfig -> LegacyRuleAction.MOCK_RESPONSE to JSONObject().apply {
            put("statusCode", config.statusCode)
            if (config.statusMessage != "OK") put("statusMessage", config.statusMessage)
            if (config.headers.isNotEmpty()) put("addHeaders", JSONObject(config.headers))
            bodyValue(config.body)?.let { put("bodyReplace", it) }
            putVariables(config.variables)
        }.toString()
        is ScriptConfig -> LegacyRuleAction.SCRIPT_REWRITE to JSONObject().apply {
            put("script", config.source)
            putVariables(config.variables)
        }.toString()
        is ChaosConfig -> LegacyRuleAction.CHAOS to JSONObject().apply {
            put("failRate", config.failRate)
            put("corruptBodyRate", config.corruptBodyRate)
            put("delayMs", JSONArray().put(config.delayMinMs).put(config.delayMaxMs))
            put("failStatusCode", config.failStatusCode)
            put("failStatusMessage", config.failStatusMessage)
            put("failBody", config.failBody)
        }.toString()
        is BodyCryptoConfig -> when (config.direction) {
            CryptoDirection.ENCRYPT -> LegacyRuleAction.BODY_ENCRYPT_AES
            CryptoDirection.DECRYPT -> LegacyRuleAction.BODY_DECRYPT_AES
        } to null
        is MirrorConfig, is BreakpointConfig, is ThrottleConfig -> null to null
    }

    private fun rewrite(config: RewriteConfig): Pair<LegacyRuleAction?, String?> {
        val redirect = config.redirection
        val hasReq = config.requestModifications.isNotEmpty()
        val hasResp = config.responseModifications.isNotEmpty()
        if (config.replaceRequest != null || config.replaceResponse != null) return null to null
        if (redirect != null) {
            if (hasReq || hasResp || redirect.preserveHost || redirect.directoryMatch) return null to null
            return LegacyRuleAction.REDIRECT to redirect.targetUrl
        }
        if (hasReq && hasResp) return null to null
        if (hasReq) {
            val json = JSONObject()
            val add = JSONObject()
            val remove = JSONArray()
            for (mod in config.requestModifications) when (mod) {
                is Modification.SetHeader -> add.put(mod.name, mod.value)
                is Modification.RemoveHeader -> remove.put(mod.name)
                is Modification.ReplaceBody -> json.put("bodyReplace", bodyValue(mod.body) ?: "")
                else -> return null to null
            }
            if (add.length() > 0) json.put("addHeaders", add)
            if (remove.length() > 0) json.put("removeHeaders", remove)
            json.putVariables(config.variables)
            return LegacyRuleAction.MODIFY_REQUEST to json.toString()
        }
        if (hasResp) {
            val json = JSONObject()
            val add = JSONObject()
            for (mod in config.responseModifications) when (mod) {
                is Modification.SetStatus -> json.put("statusCode", mod.code)
                is Modification.SetHeader -> add.put(mod.name, mod.value)
                is Modification.ReplaceBody -> json.put("bodyReplace", bodyValue(mod.body) ?: "")
                else -> return null to null
            }
            if (add.length() > 0) json.put("addHeaders", add)
            json.putVariables(config.variables)
            return LegacyRuleAction.MODIFY_RESPONSE to json.toString()
        }
        // An empty rewrite: v1 could only express it as a MODIFY_REQUEST with no config.
        return LegacyRuleAction.MODIFY_REQUEST to null
    }

    private fun bodyValue(body: BodySource): String? = when (body) {
        BodySource.Empty -> null
        is BodySource.Text -> body.value
        is BodySource.File -> body.path
    }

    private fun JSONObject.putVariables(variables: Map<String, String>) {
        if (variables.isNotEmpty()) put("variables", JSONObject(variables))
    }
}
