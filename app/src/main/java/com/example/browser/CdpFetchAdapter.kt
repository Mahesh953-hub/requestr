package com.example.browser

import android.util.Log
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowKey
import com.example.interception.rules.GatewayOutcome
import com.example.interception.rules.RulePipeline
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotStore
import com.example.interception.rules.stages.MockStage
import com.example.interception.rules.stages.RewriteStage
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
 * [RewriteStage.applyResponse] -- the exact same rule
 * semantics [com.example.interception.MitmProxyEngine] already uses for MITM/VPN traffic, so
 * there is no separate browser-only modification format -- and issues `Fetch.fulfillRequest`
 * with the mutated status/headers/body. Every other response-stage pause (`LOG_ONLY`, no rule,
 * or any request-stage action already handled) is resumed with `Fetch.continueResponse`
 * unmodified. Redirect status codes (3xx with a `Location` header) skip `getResponseBody`
 * entirely -- CDP returns no body for them and asking anyway would just fail the call for every
 * redirect on the page.
 *
 * S4 task 26: reuses requestr's shared rule pipeline ([RulePipeline] over the immutable
 * [RuleSnapshot] the MITM/VPN paths already read from; zero DAO calls). This means a rule the
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
    /** S4 task 26: the immutable rule snapshot every decision reads; a test seam over [RuleSnapshotStore]. */
    private val ruleSnapshot: () -> RuleSnapshot = { RuleSnapshotStore.current },
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

    /** Request-stage MODIFY_REQUEST enforcement only; see [redirectedCount] / [responseModifiedCount]. */
    @Volatile var modifiedCount: Int = 0
        private set

    /**
     * v6.1 Domain B: REDIRECT enforcement counted separately from [modifiedCount] so the Settings
     * card's three rule-enforcement tiles (Blocked / Modified / Redirected) each report a real,
     * stage-attributed number instead of folding redirects into "modified". Both are request-stage
     * only; response-stage enforcement is [responseModifiedCount].
     */
    @Volatile var redirectedCount: Int = 0
        private set
    @Volatile var responseModifiedCount: Int = 0
        private set

    @Volatile var bodiesCapturedCount: Int = 0
        private set

    /**
     * W9: fired after every counter increment above so [BrowserBridgeManager] can aggregate
     * counts across every attached target's adapter into one Settings-visible total, without
     * this class depending on Compose or exposing its own `StateFlow`.
     */
    var onCountersChanged: (() -> Unit)? = null

    private fun notifyCountersChanged() {
        onCountersChanged?.invoke()
    }

    /**
     * W9: fired whenever [Fetch.getResponseBody] genuinely returns a body, keyed by the CDP
     * `networkId` field on the same `Fetch.requestPaused` event -- which the protocol defines
     * as equal to the `Network.requestWillBeSent.requestId` for the same underlying network
     * transaction when one exists. [BrowserBridgeManager] wires this to
     * [CdpNetworkAdapter.onResponseBodyCaptured] so the real body/size reach the [HttpEvent]
     * that adapter already emits, instead of leaving `responseBody`/`responseSize` hardcoded.
     */
    var onResponseBodyCaptured: ((networkId: String, body: String, sizeBytes: Long) -> Unit)? = null

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

    /** S4 task 26: the matcher input for a CDP-observed request. */
    private fun flowKeyFor(url: String, host: String, request: JSONObject): FlowKey {
        val headersJson = request.optJSONObject("headers")
        val headers = mutableMapOf<String, String>()
        headersJson?.keys()?.forEach { key -> headers[key.lowercase()] = headersJson.optString(key, "") }
        val port = runCatching { java.net.URI(url) }.getOrNull()?.let { uri ->
            if (uri.port > 0) uri.port else if (uri.scheme.equals("http", ignoreCase = true)) 80 else 443
        } ?: 443
        return FlowKey(
            host = host, port = port, url = url,
            method = request.optString("method", "").ifEmpty { null },
            headers = headers,
        )
    }

    /** A [ParsedHttpMessage] view of the paused CDP request so the rewrite stage can act on it. */
    private fun parsedRequest(url: String, host: String, request: JSONObject): ParsedHttpMessage {
        val headersJson = request.optJSONObject("headers")
        val headers = linkedMapOf<String, String>()
        headersJson?.keys()?.forEach { key -> headers[key] = headersJson.optString(key, "") }
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        return ParsedHttpMessage(
            isRequest = true,
            method = HttpMethod.entries.firstOrNull { it.name == request.optString("method", "GET").uppercase() },
            url = url,
            path = uri?.rawPath?.ifEmpty { "/" } ?: "/",
            host = host,
            port = uri?.port?.takeIf { it > 0 } ?: 443,
            headers = headers,
            body = request.optString("postData", ""),
        )
    }

    private fun handleRequestStagePaused(event: JSONObject) {
        val fetchRequestId = event.optString("requestId")
        if (fetchRequestId.isEmpty()) return
        val request = event.optJSONObject("request") ?: return
        val url = request.optString("url", "")
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url

        scope.launch {
            try {
                // S4 task 26: one immutable-snapshot decision per paused request (no DAO).
                val decision = RulePipeline.evaluate(ruleSnapshot(), flowKeyFor(url, host, request))
                val gateway = decision.gateway
                when {
                    gateway.blocks -> {
                        blockedCount++
                        notifyCountersChanged()
                        client.send(
                            "Fetch.failRequest",
                            JSONObject().apply {
                                put("requestId", fetchRequestId)
                                put("errorReason", "BlockedByClient")
                            },
                            sessionId = sessionId,
                        )
                        Log.i(TAG, "BLOCK $url (rule=${(gateway as? GatewayOutcome.Apply)?.rule?.name})")
                    }
                    decision.mock != null -> {
                        // Master plan §1.5. CDP has a first-class primitive for this:
                        // Fetch.fulfillRequest answers the paused request from the debugger
                        // without the browser ever opening an upstream connection, which is
                        // exactly the "skip the real call, synthesize a response instead"
                        // semantic Gap #11 describes. Contrast response rewrites, handled in
                        // handleResponseStagePaused, which necessarily run after the real
                        // response has already arrived.
                        val mockRule = decision.mock!!
                        val mock = MockStage.build(mockRule.config, url, request.optString("method", "").ifEmpty { null })
                        client.send(
                            "Fetch.fulfillRequest",
                            JSONObject().apply {
                                put("requestId", fetchRequestId)
                                put("responseCode", mock.statusCode)
                                put(
                                    "responseHeaders",
                                    org.json.JSONArray().also { arr ->
                                        mock.headers.forEach { (name, value) ->
                                            arr.put(JSONObject().apply { put("name", name); put("value", value) })
                                        }
                                    },
                                )
                                // CDP requires the body base64-encoded, always.
                                put(
                                    "body",
                                    Base64.getEncoder()
                                        .encodeToString(mock.replacedBodyBytes ?: mock.body.toByteArray(Charsets.UTF_8)),
                                )
                            },
                            sessionId = sessionId,
                        )
                        Log.i(TAG, "MOCK $url -> ${mock.statusCode} (rule=${mockRule.rule.name})")
                    }
                    decision.rewrite.redirection != null && decision.rewrite.rules.none { it.config.requestModifications.isNotEmpty() || it.config.replaceRequest != null } -> {
                        redirectedCount++
                        notifyCountersChanged()
                        val parsed = parsedRequest(url, host, request)
                        RewriteStage.applyRequest(parsed, decision.rewrite)
                        val target = parsed.url.takeIf { it.isNotBlank() } ?: url
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
                    decision.rewrite.hasRequestWork -> {
                        modifiedCount++
                        notifyCountersChanged()
                        val parsed = parsedRequest(url, host, request)
                        val hadBody = request.has("postData")
                        RewriteStage.applyRequest(parsed, decision.rewrite)
                        client.send(
                            "Fetch.continueRequest",
                            buildContinueParams(fetchRequestId, parsed, includeUrl = parsed.url != url, includeBody = hadBody || parsed.body.isNotEmpty()),
                            sessionId = sessionId,
                        )
                        Log.i(TAG, "REWRITE request $url")
                    }
                    // Response rewrites are applied at the response-stage pause of this same
                    // request (handleResponseStagePaused), not here -- the request stage has
                    // no response body/headers to modify yet.
                    // GATEWAY FORCE_DECRYPT/BYPASS/SUSPEND are CONNECT-level dispositions (see
                    // ConnectControl) with no meaning at the Fetch.requestPaused stage -- CDP
                    // already observes decrypted browser traffic. Scripts, chaos, body crypto,
                    // breakpoints and throttling are only wired into the TLS-MITM path, so they
                    // fall through to plain allow here.
                    else -> {
                        allowedCount++
                        notifyCountersChanged()
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
        // Per the CDP spec, `networkId` on a Fetch.requestPaused event equals the
        // Network.requestWillBeSent.requestId for the same transaction when the Network
        // domain also observed it -- this is the join key CdpNetworkAdapter.applyResponseBody
        // expects. Absent when no matching Network.requestWillBeSent fired.
        val networkId = event.optString("networkId", "").ifEmpty { null }

        scope.launch {
            try {
                val decision = RulePipeline.evaluate(ruleSnapshot(), flowKeyFor(url, host, request))
                if (!decision.rewrite.hasResponseWork) {
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
                        notifyCountersChanged()
                    }
                }

                val decodedBody = if (base64Encoded) {
                    runCatching { String(Base64.getDecoder().decode(body), Charsets.UTF_8) }.getOrDefault(body)
                } else {
                    body
                }

                if (networkId != null && decodedBody.isNotEmpty()) {
                    onResponseBodyCaptured?.invoke(networkId, decodedBody, decodedBody.toByteArray(Charsets.UTF_8).size.toLong())
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
                RewriteStage.applyResponse(parsed, decision.rewrite)
                responseModifiedCount++
                notifyCountersChanged()

                client.send("Fetch.fulfillRequest", buildFulfillParams(fetchRequestId, parsed), sessionId = sessionId)
                Log.i(TAG, "REWRITE response $url (rules=${decision.rewrite.rules.joinToString { it.rule.name }})")
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
     * Builds `Fetch.continueRequest` params from a rewritten request: the (possibly rewritten)
     * header set, the new URL when a redirection changed it, and the body when the rule
     * replaced it or the original carried one. Header names keep the browser's casing.
     */
    private fun buildContinueParams(fetchRequestId: String, request: ParsedHttpMessage, includeUrl: Boolean, includeBody: Boolean): JSONObject {
        val headerArray = org.json.JSONArray()
        request.headers.forEach { (k, v) -> headerArray.put(JSONObject().apply { put("name", k); put("value", v) }) }
        return JSONObject().apply {
            put("requestId", fetchRequestId)
            if (includeUrl) put("url", request.url)
            put("headers", headerArray)
            if (includeBody) {
                val bytes = request.replacedBodyBytes ?: request.body.toByteArray(Charsets.UTF_8)
                put("postData", Base64.getEncoder().encodeToString(bytes))
            }
        }
    }
}
