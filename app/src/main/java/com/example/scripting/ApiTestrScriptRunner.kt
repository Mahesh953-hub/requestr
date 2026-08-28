package com.example.scripting

import com.example.data.env.Environment
import com.example.data.models.ApiRequestItem
import com.example.data.models.ApiResponseData
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem

/**
 * Outcome of [ApiTestrScriptRunner.runPreRequestScript]. [request] is either the original
 * [ApiRequestItem] unchanged (blank/absent script) or a copy with whatever `pm.request.*`
 * mutations the script made applied. [envUpdates] is the full env-variable map after the run --
 * callers write it back onto the active [Environment] via
 * [com.example.data.env.EnvironmentRepository.updateEnvironment].
 */
data class PreScriptOutcome(
    val request: ApiRequestItem,
    val envUpdates: Map<String, String>,
    val testResults: List<PmTestResult> = emptyList(),
    val consoleOutput: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Outcome of [ApiTestrScriptRunner.runPostResponseScript]. [response] mirrors [PreScriptOutcome]'s
 * `request`: the original [ApiResponseData] unless the script mutated `pm.response.*`, in which
 * case a copy with those mutations applied (mainly useful for a script that rewrites `code`/
 * `body` before chain-extraction/assertions run against it downstream).
 */
data class PostScriptOutcome(
    val response: ApiResponseData,
    val envUpdates: Map<String, String>,
    val testResults: List<PmTestResult> = emptyList(),
    val consoleOutput: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Domain glue between [ApiTestrRepository]/collection runs and the generic [JsScriptEngine] +
 * [PmPrelude] `pm.*` shim -- the `SCRIPT_REWRITE`-analog for ApiTestr's own pre-request/
 * post-response scripts (master plan §1.8), as opposed to
 * [com.example.interception.RuleEnforcer.applyScriptRewrite]'s live-MITM `SCRIPT_REWRITE` rule
 * action (§1.11), which shares the same [JsScriptEngine]/[PmPrelude] but wires a different
 * [PmBridgeBase] subclass backed by a live [com.example.interception.http.ParsedHttpMessage].
 */
object ApiTestrScriptRunner {

    /**
     * Runs [script] before [request] is sent. A blank/whitespace-only script is a no-op (skips
     * the Rhino engine entirely) so every already-saved request with the placeholder comment
     * default keeps behaving exactly as before this feature existed.
     */
    fun runPreRequestScript(script: String, request: ApiRequestItem, env: Environment): PreScriptOutcome {
        if (script.isBlank()) {
            return PreScriptOutcome(request = request, envUpdates = env.variables)
        }

        var url = request.url
        var methodName = request.method.name
        var body = request.bodyContent
        val headers = request.headers.toMutableList()

        val bridge = object : PmBridgeBase(env.variables) {
            override fun requestGetUrl(): String = url
            override fun requestSetUrl(value: String) { url = value }
            override fun requestGetMethod(): String = methodName
            override fun requestSetMethod(value: String) { methodName = value }
            override fun requestGetHeader(name: String): String? =
                headers.lastOrNull { it.isEnabled && it.key.equals(name, ignoreCase = true) }?.value
            override fun requestSetHeader(name: String, value: String) {
                val idx = headers.indexOfFirst { it.key.equals(name, ignoreCase = true) }
                if (idx >= 0) headers[idx] = headers[idx].copy(value = value, isEnabled = true)
                else headers.add(KeyValueItem(key = name, value = value, isEnabled = true))
            }
            override fun requestRemoveHeader(name: String) {
                headers.removeAll { it.key.equals(name, ignoreCase = true) }
            }
            override fun requestHeaderNames(): Array<String> =
                headers.filter { it.isEnabled }.map { it.key }.toTypedArray()
            override fun requestGetBody(): String = body
            override fun requestSetBody(value: String) { body = value }

            // pm.response isn't meaningful before the request is even sent -- return inert
            // values rather than throwing, so a script that merely *checks* `pm.response` (e.g.
            // shared boilerplate that runs in both pre/post hooks) doesn't blow up either.
            override fun responseGetStatusCode(): Int = 0
            override fun responseSetStatusCode(value: Int) {}
            override fun responseGetStatusMessage(): String = ""
            override fun responseGetHeader(name: String): String? = null
            override fun responseSetHeader(name: String, value: String) {}
            override fun responseRemoveHeader(name: String) {}
            override fun responseHeaderNames(): Array<String> = emptyArray()
            override fun responseGetBody(): String = ""
            override fun responseSetBody(value: String) {}
            override fun responseGetResponseTimeMs(): Long = 0
        }

        val result = JsScriptEngine.eval(script, PmPrelude.SOURCE, bridge)
        val mutatedMethod = HttpMethod.entries.firstOrNull { it.name.equals(methodName, ignoreCase = true) }
            ?: request.method
        val mutatedRequest = request.copy(
            url = url,
            method = mutatedMethod,
            headers = headers,
            bodyContent = body,
        )
        return PreScriptOutcome(
            request = mutatedRequest,
            envUpdates = bridge.finalEnvVars,
            testResults = bridge.recordedTests,
            consoleOutput = bridge.consoleOutput,
            error = result.error,
        )
    }

    /**
     * Runs [script] after [response] is received for [request]. Same blank-script no-op as
     * [runPreRequestScript].
     */
    fun runPostResponseScript(
        script: String,
        request: ApiRequestItem,
        response: ApiResponseData,
        env: Environment,
    ): PostScriptOutcome {
        if (script.isBlank()) {
            return PostScriptOutcome(response = response, envUpdates = env.variables)
        }

        var statusCode = response.statusCode
        var body = response.body
        val headers = response.headers.toMutableMap()

        val bridge = object : PmBridgeBase(env.variables) {
            // The request already went out -- pm.request is read-only history here, and its
            // setters are no-ops rather than errors (matches Postman: mutating pm.request in a
            // post-response script has no effect on a request that already left the wire).
            override fun requestGetUrl(): String = request.url
            override fun requestSetUrl(value: String) {}
            override fun requestGetMethod(): String = request.method.name
            override fun requestSetMethod(value: String) {}
            override fun requestGetHeader(name: String): String? =
                request.headers.lastOrNull { it.isEnabled && it.key.equals(name, ignoreCase = true) }?.value
            override fun requestSetHeader(name: String, value: String) {}
            override fun requestRemoveHeader(name: String) {}
            override fun requestHeaderNames(): Array<String> =
                request.headers.filter { it.isEnabled }.map { it.key }.toTypedArray()
            override fun requestGetBody(): String = request.bodyContent
            override fun requestSetBody(value: String) {}

            override fun responseGetStatusCode(): Int = statusCode
            override fun responseSetStatusCode(value: Int) { statusCode = value }
            override fun responseGetStatusMessage(): String = response.statusMessage
            override fun responseGetHeader(name: String): String? =
                headers.entries.lastOrNull { it.key.equals(name, ignoreCase = true) }?.value
            override fun responseSetHeader(name: String, value: String) {
                val existingKey = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
                if (existingKey != null) headers[existingKey] = value else headers[name] = value
            }
            override fun responseRemoveHeader(name: String) {
                headers.keys.removeAll { it.equals(name, ignoreCase = true) }
            }
            override fun responseHeaderNames(): Array<String> = headers.keys.toTypedArray()
            override fun responseGetBody(): String = body
            override fun responseSetBody(value: String) { body = value }
            override fun responseGetResponseTimeMs(): Long = response.responseTimeMs
        }

        val result = JsScriptEngine.eval(script, PmPrelude.SOURCE, bridge)
        val mutatedResponse = response.copy(statusCode = statusCode, body = body, headers = headers)
        return PostScriptOutcome(
            response = mutatedResponse,
            envUpdates = bridge.finalEnvVars,
            testResults = bridge.recordedTests,
            consoleOutput = bridge.consoleOutput,
            error = result.error,
        )
    }
}
