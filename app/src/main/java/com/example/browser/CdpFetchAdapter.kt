package com.example.browser

import android.util.Log
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.interception.RuleEnforcer
import com.example.interception.http.ParsedHttpMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Base64

/**
 * B3/W9: CDP Fetch adapter -- allow/block/modify browser requests AND responses in-flight.
 *
 * Enables the `Fetch` domain with **two** patterns -- `requestStage: "Request"` and
 * `requestStage: "Response"` -- and, on each `Fetch.requestPaused` event, distinguishes the
 * stage by the documented discriminator: a response-stage pause carries
 * `responseStatusCode`/`responseErrorReason` in its params, a request-stage pause does not
 * (see [isResponseStage]). Request-stage handling is unchanged from B3:
 *  - `Fetch.failRequest` (BLOCK)
 *  - `Fetch.continueRequest` with a rewritten `url` (REDIRECT)
 *  - `Fetch.continueRequest` with rewritten `headers`/`postData` (MODIFY_REQUEST)
 *  - `Fetch.continueRequest` unmodified (LOG_ONLY / no matching rule / MODIFY_RESPONSE, which
 *    is deferred to the response-stage pause of the *same* request)
 *
 * Response-stage handling (W9, new): for a `MODIFY_RESPONSE` rule, calls `Fetch.getResponseBody`
 * (only valid while paused in the response stage) to fetch the real body, applies the rule via
 * [com.example.interception.RuleEnforcer.applyResponseModification] -- the exact same rule
 * semantics [com.example.interception.MitmProxyEngine] already uses for MITM/VPN traffic, so
 * there is no separate browser-only modification format -- and issues `Fetch.fulfillRequest`
 * with the mutated status/headers/body. Every other response-stage pause (`LOG_ONLY`, no rule,
 * or any request-stage action already handled) is resumed with `Fetch.continueResponse`
 * unmodified. Redirect status codes (3xx with a `Location` header) skip `getResponseBody`
 * entirely -- CDP returns no body for them and asking anyway would just fail the call for every
 * redirect on the page.
 *
 * Reuses requestr's existing shared rule engine, [RuleEnforcer], and the same
 * `intercept_rules` Room table the MITM/VPN paths already read from. This means a rule the
 * user creates once in Settings/Capture-Setup ("Rules \u0026 Modifiers") applies uniformly whether
 * the traffic was captured via VPN/MITM *or* via a live Chrome CDP session.
 *
 * W8: takes an optional [sessionId] so one adapter instance is scoped to exactly one attached
 * CDP target under `Target.setAutoAttach`'s flattened-session model; `null` keeps this usable
 * against a single-target/legacy connection.
 */
class CdpFetchAdapter(
    private val client: CdpClient,
    private val context: android.content.Context,
    /** W8: the target's flattened-session id, or null for a single-target/legacy connection. */
    private val sessionId: String? = null,
) {
    companion object {
        private const val TAG = "CdpFetchAdapter"

        /**
         * Response-stage discriminator per the CDP `Fetch.requestPaused` spec: a pause is at
         * the response stage iff its params carry `responseStatusCode` or
         * `responseErrorReason`; request-stage pauses carry neither.
         */
        fun isResponseStage(params: JSONObject): Boolean =
            params.has("responseStatusCode") || params.has("responseErrorReason")

        /** 3xx statuses genuinely carry no body worth fetching via `Fetch.getResponseBody`. */
        fun isRedirectStatus(statusCode: Int): Boolean = statusCode in 300..399
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Counters for the Settings UI (best-effort, in-memory). */
    @Volatile var allowedCount: Int = 0
        private set
    @Volatile var blockedCount: Int = 0
        private set
    @Volatile var modifiedCount: Int = 0
        private set
    @Volatile var responseModifiedCount: Int = 0
        private set
    @Volatile var bodiesCapturedCount: Int = 0
        private set

    fun start() {
        val params = JSONObject().apply {
            put(
                "patterns",
                org.json.JSONArray()
                    .put(JSONObject().apply { put("urlPattern", "*"); put("requestStage", "Request") })
                    .put(JSONObject().apply { put("urlPattern", "*"); put("requestStage", "Response") }),
            )
        }
        client.send("Fetch.enable", params, sessionId = sessionId)
        client.on("Fetch.requestPaused", sessionId) { event -> handleRequestPaused(event) }
    }

    fun stop() {
        client.send("Fetch.disable", sessionId = sessionId)
    }

    internal fun handleRequestPaused(event: JSONObject) {
        if (isResponseStage(event)) {
            handleResponseStagePaused(event)
        } else {
            handleRequestStagePaused(event)
        }
    }

    private fun handleRequestStagePaused(event: JSONObject) {
        val fetchRequestId = event.optString("requestId")
        if (fetchRequestId.isEmpty()) return
        val request = event.optJSONObject("request") ?: return
        val url = request.optString("url", "")
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url

        scope.launch {
            try {
                val db = RequestrDatabase.get(context)
                val rule = RuleEnforcer.getMatchingRule(db, url, host)
                when (rule?.action ?: RuleAction.LOG_ONLY) {
                    RuleAction.BLOCK -> {
                        blockedCount++
                        client.send(
                            "Fetch.failRequest",
                            JSONObject().apply {
                                put("requestId", fetchRequestId)
                                put("errorReason", "BlockedByClient")
                            },
                            sessionId = sessionId,
                        )
                        Log.i(TAG, "BLOCK $url (rule=${rule?.name})")
                    }
                    RuleAction.REDIRECT -> {
                        modifiedCount++
                        val target = rule?.replacementValue?.takeIf { it.isNotBlank() } ?: url
                        client.send(
                            "Fetch.continueRequest",
                            JSONObject().apply {
                                put("requestId", fetchRequestId)
                                put("url", target)
                            },
                            sessionId = sessionId,
                        )
                        Log.i(TAG, "REDIRECT $url -> $target")
                    }
                    RuleAction.MODIFY_REQUEST -> {
                        modifiedCount++
                        val continueParams = buildModifiedContinueParams(fetchRequestId, request, rule?.replacementValue)
                        client.send("Fetch.continueRequest", continueParams, sessionId = sessionId)
                        Log.i(TAG, "MODIFY_REQUEST $url")
                    }
                    // MODIFY_RESPONSE is applied at the response-stage pause of this same
                    // request (handleResponseStagePaused), not here -- the request stage has
                    // no response body/headers to modify yet.
                    RuleAction.MODIFY_RESPONSE, RuleAction.LOG_ONLY -> {
                        allowedCount++
                        client.send(
                            "Fetch.continueRequest",
                            JSONObject().apply { put("requestId", fetchRequestId) },
                            sessionId = sessionId,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestPaused (request stage) handling failed: ${e.message}")
                // Fail open: never leave a paused request stuck, always resume it plainly.
                client.send("Fetch.continueRequest", JSONObject().apply { put("requestId", fetchRequestId) }, sessionId = sessionId)
            }
        }
    }

    private fun handleResponseStagePaused(event: JSONObject) {
        val fetchRequestId = event.optString("requestId")
        if (fetchRequestId.isEmpty()) return
        val request = event.optJSONObject("request") ?: return
        val url = request.optString("url", "")
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val statusCode = event.optInt("responseStatusCode", 200)
        val responseHeadersJson = event.optJSONArray("responseHeaders")

        scope.launch {
            try {
                val db = RequestrDatabase.get(context)
                val rule = RuleEnforcer.getMatchingRule(db, url, host)
                if (rule?.action != RuleAction.MODIFY_RESPONSE) {
                    client.send(
                        "Fetch.continueResponse",
                        JSONObject().apply { put("requestId", fetchRequestId) },
                        sessionId = sessionId,
                    )
                    return@launch
                }

                val headers = mutableMapOf<String, String>()
                if (responseHeadersJson != null) {
                    for (i in 0 until responseHeadersJson.length()) {
                        val h = responseHeadersJson.optJSONObject(i) ?: continue
                        headers[h.optString("name", "")] = h.optString("value", "")
                    }
                }

                var body = ""
                var base64Encoded = false
                if (!isRedirectStatus(statusCode)) {
                    val bodyResult = getResponseBody(fetchRequestId)
                    if (bodyResult != null) {
                        body = bodyResult.first
                        base64Encoded = bodyResult.second
                        bodiesCapturedCount++
                    }
                }

                val decodedBody = if (base64Encoded) {
                    runCatching { String(Base64.getDecoder().decode(body), Charsets.UTF_8) }.getOrDefault(body)
                } else {
                    body
                }

                val parsed = ParsedHttpMessage(
                    isRequest = false,
                    method = null,
                    url = url,
                    host = host ?: url,
                    statusCode = statusCode,
                    headers = headers,
                    body = decodedBody,
                )
                RuleEnforcer.applyResponseModification(parsed, rule.replacementValue ?: "")
                responseModifiedCount++

                client.send("Fetch.fulfillRequest", buildFulfillParams(fetchRequestId, parsed), sessionId = sessionId)
                Log.i(TAG, "MODIFY_RESPONSE $url (rule=${rule.name})")
            } catch (e: Exception) {
                Log.w(TAG, "requestPaused (response stage) handling failed: ${e.message}")
                // Fail open: never leave a paused response stuck, always resume it plainly.
                client.send(
                    "Fetch.continueResponse",
                    JSONObject().apply { put("requestId", fetchRequestId) },
                    sessionId = sessionId,
                )
            }
        }
    }

    /**
     * `Fetch.getResponseBody` -- valid only while paused in the response stage. Returns
     * `(body, base64Encoded)` or null when the call fails (e.g. the pause already expired).
     * A plain callback-to-suspend bridge since [CdpClient.send]'s callback API predates
     * coroutines being used inside this class.
     */
    private suspend fun getResponseBody(fetchRequestId: String): Pair<String, Boolean>? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            client.send(
                "Fetch.getResponseBody",
                JSONObject().apply { put("requestId", fetchRequestId) },
                sessionId = sessionId,
            ) { result, error ->
                if (error != null || result == null) {
                    cont.resumeWith(Result.success(null))
                } else {
                    cont.resumeWith(Result.success(result.optString("body", "") to result.optBoolean("base64Encoded", false)))
                }
            }
        }

    /** Builds `Fetch.fulfillRequest` params from a mutated [ParsedHttpMessage]. */
    private fun buildFulfillParams(fetchRequestId: String, response: ParsedHttpMessage): JSONObject {
        val headerArray = org.json.JSONArray()
        response.headers.forEach { (k, v) -> headerArray.put(JSONObject().apply { put("name", k); put("value", v) }) }
        return JSONObject().apply {
            put("requestId", fetchRequestId)
            put("responseCode", response.statusCode)
            put("responseHeaders", headerArray)
            put("body", Base64.getEncoder().encodeToString(response.body.toByteArray(Charsets.UTF_8)))
        }
    }

    /**
     * Builds `Fetch.continueRequest` params applying a MODIFY_REQUEST rule's
     * `{"addHeaders":{...},"removeHeaders":[...],"bodyReplace":"..."}` config -- the same JSON
     * shape [RuleEnforcer.applyRequestModification] uses for MITM traffic, reused here so rule
     * authors don't need to learn a second config format for CDP-observed requests.
     */
    private fun buildModifiedContinueParams(fetchRequestId: String, request: JSONObject, config: String?): JSONObject {
        val headersJson = request.optJSONObject("headers")
        val headers = mutableMapOf<String, String>()
        headersJson?.keys()?.forEach { key -> headers[key] = headersJson.optString(key, "") }
        var body: String? = request.optString("postData", "").ifEmpty { null }

        if (!config.isNullOrBlank()) {
            runCatching {
                val cfg = JSONObject(config)
                if (cfg.has("addHeaders")) {
                    val add = cfg.getJSONObject("addHeaders")
                    add.keys().forEach { key -> headers[key] = add.optString(key, "") }
                }
                if (cfg.has("removeHeaders")) {
                    val remove = cfg.getJSONArray("removeHeaders")
                    for (i in 0 until remove.length()) headers.remove(remove.getString(i))
                }
                if (cfg.has("bodyReplace")) {
                    body = cfg.getString("bodyReplace")
                }
            }.onFailure { Log.w(TAG, "Failed to apply MODIFY_REQUEST config: ${it.message}") }
        }

        val headerArray = org.json.JSONArray()
        headers.forEach { (k, v) -> headerArray.put(JSONObject().apply { put("name", k); put("value", v) }) }

        return JSONObject().apply {
            put("requestId", fetchRequestId)
            put("headers", headerArray)
            if (body != null) {
                put("postData", Base64.getEncoder().encodeToString(body!!.toByteArray(Charsets.UTF_8)))
            }
        }
    }
}
