package com.example.interception.rules.stages

import android.util.Log
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.Matched
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.ScriptConfig
import com.example.scripting.JsScriptEngine
import com.example.scripting.PmBridgeBase
import com.example.scripting.PmPrelude

/**
 * S4 task 26: the SCRIPT slot. Runs every matching script in list order against the message.
 *
 * Task 26 ships the **in-process** runner ([runInProcess]) with the exact `pm.*` bridge the
 * legacy `RuleEnforcer.applyScriptRewrite` exposed (G16 parity, including the
 * `x-requestr-script-error` attribution on failure). Task 32 adds the out-of-process
 * `:scripthost` runner behind the `scriptHost` flag; [ScriptRunner] is the seam it plugs into.
 */
object ScriptStage {
    private const val TAG = "ScriptStage"

    /** Executes one script against [message]; returns the error text, or null on success. */
    fun interface ScriptRunner {
        fun run(message: ParsedHttpMessage, config: ScriptConfig, ruleName: String, responseTimeMs: Long): String?
    }

    /** The default runner; task 32 swaps this for the isolated host when the flag is on. */
    @Volatile
    var runner: ScriptRunner = ScriptRunner { message, config, ruleName, responseTimeMs -> runInProcess(message, config, ruleName, responseTimeMs) }

    data class Result(val ran: Int, val errors: List<Pair<String, String>>) {
        val changed: Boolean get() = ran > 0
        companion object {
            val NONE = Result(0, emptyList())
        }
    }

    /**
     * Applies every script in [scripts]. A failing script stamps [RuleMetadataHeaders.SCRIPT_ERROR]
     * on the message (`<error>; rule="<name>"`), which the caller keeps off the wire for requests
     * (added after serialization) and forwards on responses, exactly as the legacy engine did.
     * [stampErrorHeader] false lets the request leg stamp after serializing.
     */
    fun apply(
        message: ParsedHttpMessage,
        scripts: List<Matched<ScriptConfig>>,
        phase: FlowAuditTrail.Phase,
        trail: FlowAuditTrail? = null,
        responseTimeMs: Long = 0L,
        stampErrorHeader: Boolean = true,
    ): Result {
        if (scripts.isEmpty()) return Result.NONE
        var ran = 0
        val errors = ArrayList<Pair<String, String>>(0)
        for (matched in scripts) {
            val config = matched.config
            if (config.source.isBlank()) continue
            ran++
            val error = runner.run(message, config, matched.rule.name, responseTimeMs)
            if (error == null) {
                trail?.applied(matched.rule, phase, "script ok")
            } else {
                errors += error to matched.rule.name
                trail?.failed(matched.rule, phase, error.take(200))
                if (stampErrorHeader) stampError(message, error, matched.rule.name)
            }
        }
        return Result(ran, errors)
    }

    fun stampError(message: ParsedHttpMessage, error: String, ruleName: String) {
        message.headers = message.headers + (RuleMetadataHeaders.SCRIPT_ERROR to errorHeaderValue(error, ruleName))
    }

    fun errorHeaderValue(error: String, ruleName: String): String {
        val folded = error.replace(Regex("[\\r\\n]+"), " ").trim()
        return "$folded; ${RuleMetadataHeaders.ruleAttribution(ruleName)}"
    }

    /** Legacy-parity in-process runner: Rhino, `optimizationLevel = -1`, `isJavaPrimitiveWrap = false`. */
    fun runInProcess(message: ParsedHttpMessage, config: ScriptConfig, ruleName: String, responseTimeMs: Long = 0L): String? {
        if (config.source.isBlank()) return null
        return runCatching {
            val bridge = bridgeFor(message, config.variables, responseTimeMs)
            val result = JsScriptEngine.eval(config.source, PmPrelude.SOURCE, bridge, config.timeoutMs, config.maxInstructions)
            if (!result.ok) {
                Log.w(TAG, "script rule '$ruleName' error: ${result.error}")
                result.error ?: "script failed without an error message"
            } else {
                null
            }
        }.getOrElse {
            Log.w(TAG, "Failed to run script rule '$ruleName': ${it.message}")
            "invalid script config: ${it.message}"
        }
    }

    /** The `pm.*` bridge over a single [ParsedHttpMessage] -- request or response side, never both. */
    fun bridgeFor(message: ParsedHttpMessage, variables: Map<String, String>, responseTimeMs: Long): PmBridgeBase =
        object : PmBridgeBase(LinkedHashMap(variables)) {
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
            override fun responseGetResponseTimeMs(): Long = responseTimeMs
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
}
