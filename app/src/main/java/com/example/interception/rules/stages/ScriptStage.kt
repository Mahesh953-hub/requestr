package com.example.interception.rules.stages

import android.util.Log
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.Matched
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.ScriptConfig
import com.example.scripting.JsScriptEngine
import com.example.scripting.PmBridgeBase
import com.example.scripting.GlobalVariableStore
import com.example.scripting.PmPrelude
import com.example.scripting.ScriptConsoleLog
import com.example.scripting.host.ScriptContract
import com.example.scripting.host.ScriptHostClient

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

    /** One script invocation: the message, the rule, and the per-flow lifecycle state. */
    class ScriptCall(
        val message: ParsedHttpMessage,
        val config: ScriptConfig,
        val ruleName: String,
        val responseTimeMs: Long,
        val phase: FlowAuditTrail.Phase,
        val flowId: String,
        /** `context.shared`: mutated in place so the response leg sees what the request leg stored. */
        val shared: MutableMap<String, String>,
    ) {
        val phaseName: String get() = if (phase == FlowAuditTrail.Phase.RESPONSE) PmPrelude.PHASE_RESPONSE else PmPrelude.PHASE_REQUEST
    }

    /** [error] null = the script ran; [aborted] = its lifecycle hook returned `null` (drop the flow). */
    data class ScriptOutcome(val error: String?, val aborted: Boolean = false)

    /** Executes one script against the call's message. */
    fun interface ScriptRunner {
        fun run(call: ScriptCall): ScriptOutcome
    }

    /** The default runner (in-process Rhino); [MitmProxyEngine.start] swaps in [HostRunner] when `scriptHost` is on. */
    @Volatile
    var runner: ScriptRunner = ScriptRunner { call -> runInProcess(call) }

    data class Result(
        val ran: Int,
        val errors: List<Pair<String, String>>,
        /** S4 task 32: a lifecycle hook returned `null` -- the caller must not forward this flow. */
        val abortedBy: String? = null,
    ) {
        val changed: Boolean get() = ran > 0
        val aborted: Boolean get() = abortedBy != null
        companion object {
            val NONE = Result(0, emptyList())
        }
    }

    /**
     * Applies every script in [scripts] in list order. A failing script stamps
     * [RuleMetadataHeaders.SCRIPT_ERROR] on the message (`<error>; rule="<name>"`), which the
     * caller keeps off the wire for requests (added after serialization) and forwards on
     * responses, exactly as the legacy engine did. [stampErrorHeader] false lets the request leg
     * stamp after serializing. An abort stops the list and is reported through [Result.abortedBy].
     */
    fun apply(
        message: ParsedHttpMessage,
        scripts: List<Matched<ScriptConfig>>,
        phase: FlowAuditTrail.Phase,
        trail: FlowAuditTrail? = null,
        responseTimeMs: Long = 0L,
        stampErrorHeader: Boolean = true,
        flowId: String = "",
        shared: MutableMap<String, String> = LinkedHashMap(),
    ): Result {
        if (scripts.isEmpty()) return Result.NONE
        var ran = 0
        val errors = ArrayList<Pair<String, String>>(0)
        for (matched in scripts) {
            val config = matched.config
            if (config.source.isBlank()) continue
            ran++
            val outcome = runner.run(ScriptCall(message, config, matched.rule.name, responseTimeMs, phase, flowId, shared))
            val error = outcome.error
            if (error == null) {
                if (outcome.aborted) {
                    trail?.applied(matched.rule, phase, "script aborted the flow")
                    return Result(ran, errors, abortedBy = matched.rule.name)
                }
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

    /** The synthetic reply the client gets when a script's lifecycle hook aborted the flow. */
    fun abortResponse(ruleName: String, version: String = "HTTP/1.1"): ParsedHttpMessage = ParsedHttpMessage(
        isRequest = false, method = null, version = version.ifEmpty { "HTTP/1.1" },
        statusCode = ABORT_STATUS, statusMessage = ABORT_STATUS_MESSAGE,
        headers = mapOf(
            "content-type" to "text/plain; charset=utf-8",
            "connection" to "close",
            RuleMetadataHeaders.SCRIPT_ERROR to errorHeaderValue("aborted by script", ruleName),
        ),
        body = "requestr: flow aborted by script rule \"$ruleName\"", contentType = "text/plain", bodyReplaced = true,
    )

    const val ABORT_STATUS = 499
    const val ABORT_STATUS_MESSAGE = "Aborted By Script"

    /** Legacy-parity in-process runner: Rhino, `optimizationLevel = -1`, `isJavaPrimitiveWrap = false`. */
    fun runInProcess(call: ScriptCall): ScriptOutcome {
        val config = call.config
        if (config.source.isBlank()) return ScriptOutcome(null)
        return runCatching {
            val bridge = bridgeFor(call.message, config.variables, call.responseTimeMs)
            bridge.phase = call.phaseName
            bridge.seedShared(call.shared)
            val result = JsScriptEngine.eval(config.source, PmPrelude.SOURCE, bridge, config.timeoutMs, config.maxInstructions, epilogue = PmPrelude.EPILOGUE)
            call.shared.clear(); call.shared.putAll(bridge.sharedContext)
            ScriptConsoleLog.shared.appendAll(call.flowId, call.ruleName, bridge.consoleOutput, result.error)
            if (!result.ok) {
                Log.w(TAG, "script rule '${call.ruleName}' error: ${result.error}")
                ScriptOutcome(result.error ?: "script failed without an error message")
            } else {
                ScriptOutcome(null, aborted = result.value == "ABORT")
            }
        }.getOrElse {
            Log.w(TAG, "Failed to run script rule '${call.ruleName}': ${it.message}")
            ScriptOutcome("invalid script config: ${it.message}")
        }
    }

    /** Convenience for tests and single-shot callers: no flow state, request phase inferred from the message. */
    fun runInProcess(message: ParsedHttpMessage, config: ScriptConfig, ruleName: String, responseTimeMs: Long = 0L): String? =
        runInProcess(
            ScriptCall(
                message, config, ruleName, responseTimeMs,
                if (message.isRequest) FlowAuditTrail.Phase.REQUEST else FlowAuditTrail.Phase.RESPONSE, "", LinkedHashMap(),
            ),
        ).error

    /**
     * S4 task 32: runs scripts in the isolated `:scripthost` process through [ScriptHostClient].
     * Host failures (deadline, dead binder, open circuit) are reported as script errors so the flow
     * continues unmodified with a FAILED audit record and the error badge -- never a hang.
     */
    class HostRunner(private val client: ScriptHostClient) : ScriptRunner {
        override fun run(call: ScriptCall): ScriptOutcome {
            val original = ScriptContract.MessageDto.fromMessage(call.message)
            val envelope = ScriptContract.ScriptRequestEnvelope(
                phase = call.phaseName, flowId = call.flowId, scriptId = call.ruleName, source = call.config.source,
                timeoutMs = call.config.timeoutMs, maxInstructions = call.config.maxInstructions,
                variables = call.config.variables, shared = call.shared.toMap(), globals = GlobalVariableStore.snapshot(),
                message = original, responseTimeMs = call.responseTimeMs,
            )
            return when (val outcome = client.run(envelope)) {
                is ScriptHostClient.Outcome.HostFailure -> ScriptOutcome(outcome.reason)
                is ScriptHostClient.Outcome.Ok -> {
                    val response = outcome.response
                    call.shared.clear(); call.shared.putAll(response.shared)
                    response.globals.forEach { (k, v) -> GlobalVariableStore.set(k, v) }
                    ScriptConsoleLog.shared.appendAll(call.flowId, call.ruleName, response.console, response.error)
                    if (!response.ok) return ScriptOutcome(response.error ?: "script failed without an error message")
                    val refused = response.message?.applyTo(call.message, original)
                    if (refused != null) ScriptOutcome(refused) else ScriptOutcome(null, aborted = response.aborted)
                }
            }
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
