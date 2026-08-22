package com.example.browser

import android.util.Log
import com.example.correlation.HttpEvent
import com.example.correlation.ObservationEvent
import com.example.correlation.StackFrame
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * B2: CDP Network adapter.
 *
 * Subscribes to `Network.requestWillBeSent` / `Network.responseReceived` /
 * `Network.loadingFinished` / `Network.loadingFailed` on a connected [CdpClient] and turns
 * the pairs of request/response events into [HttpEvent]s with
 * `source = ObservationEvent.ObserverSource.BROWSER`.
 *
 * CDP reports request and response as two separate, asynchronous events keyed by the same
 * `requestId`, so this adapter buffers the request side in [pendingRequests] until the
 * matching response (or a `loadingFailed`) arrives, then emits one complete [HttpEvent].
 * A request that only ever sees `requestWillBeSent` (e.g. page navigated away, CDP session
 * dropped mid-flight) is emitted anyway from `loadingFinished`/`loadingFailed` if partial
 * data is present, so browser activity isn't silently dropped -- this favors an event with a
 * placeholder/absent status code over never emitting one at all.
 *
 * B4/W7 wiring: request-side initiator info is resolved via [InitiatorResolver.describe] /
 * [InitiatorResolver.resolveStack] and attached to [HttpEvent]'s first-class `initiator` /
 * `initiatorStackFrames` fields, rather than smuggled in as a pseudo request header (the
 * previous `X-Requestr-Initiator` shortcut this replaces).
 *
 * W9 wiring: `Network.requestId` and `Fetch.requestPaused.networkId` are the same identifier
 * per the CDP spec when both domains observe the same transaction, so
 * [BrowserBridgeManager] feeds every body [CdpFetchAdapter] genuinely captures via
 * `Fetch.getResponseBody` into [applyResponseBody] here, keyed by that shared id. This is
 * best-effort: the two domains give no ordering guarantee between `Fetch.requestPaused`
 * (response stage) and `Network.responseReceived`, so a body that arrives after [emit] has
 * already fired for that request is simply not attached -- `responseBody`/`responseSize` fall
 * back to their previous `null`/`0` values rather than blocking or re-emitting.
 */
class CdpNetworkAdapter(
    private val client: CdpClient,
    /** W8: the target's flattened-session id, or null for a single-target/legacy connection. */
    private val sessionId: String? = null,
    private val onEvent: (HttpEvent) -> Unit,
) {
    companion object {
        private const val TAG = "CdpNetworkAdapter"
    }

    private data class PendingRequest(
        val requestId: String,
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val timestamp: Long,
        val initiatorDescription: String,
        val initiatorStackFrames: List<StackFrame> = emptyList(),
    )

    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    /** requestId -> (body, sizeBytes), populated by [applyResponseBody] ahead of [emit]. */
    private val capturedResponseBodies = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * W9: record a response body [CdpFetchAdapter] captured via `Fetch.getResponseBody` for the
     * network transaction identified by [networkId] (== `Network.requestId`), so [emit] can
     * attach the real body/size instead of the previous hardcoded `null`/`0`. Safe to call
     * before or after [handleRequestWillBeSent]; a call for a request that already emitted (or
     * never existed on the Network domain) is a harmless no-op.
     */
    fun applyResponseBody(networkId: String, body: String, sizeBytes: Long) {
        if (networkId.isEmpty()) return
        capturedResponseBodies[networkId] = body to sizeBytes
    }

    /** Enable the Network domain and start listening. Idempotent per-instance. */
    fun start() {
        client.send("Network.enable", sessionId = sessionId)
        client.on("Network.requestWillBeSent", sessionId) { params -> handleRequestWillBeSent(params) }
        client.on("Network.responseReceived", sessionId) { params -> handleResponseReceived(params) }
        client.on("Network.loadingFinished", sessionId) { params -> handleLoadingFinished(params) }
        client.on("Network.loadingFailed", sessionId) { params -> handleLoadingFailed(params) }
    }

    internal fun handleRequestWillBeSent(params: JSONObject) {
        try {
            val requestId = params.optString("requestId")
            val request = params.optJSONObject("request") ?: return
            val headers = jsonObjectToMap(request.optJSONObject("headers"))
            val initiator = params.optJSONObject("initiator")
            pendingRequests[requestId] = PendingRequest(
                requestId = requestId,
                method = request.optString("method", "GET"),
                url = request.optString("url", ""),
                headers = headers,
                timestamp = (params.optDouble("timestamp", 0.0) * 1000).toLong()
                    .let { if (it > 0) it else System.currentTimeMillis() },
                initiatorDescription = InitiatorResolver.describe(initiator),
                initiatorStackFrames = InitiatorResolver.resolveStack(initiator),
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestWillBeSent parse failed: ${e.message}")
        }
    }

    internal fun handleResponseReceived(params: JSONObject) {
        try {
            val requestId = params.optString("requestId")
            val response = params.optJSONObject("response") ?: return
            val req = pendingRequests[requestId] ?: PendingRequest(
                requestId = requestId,
                method = "GET",
                url = response.optString("url", ""),
                headers = emptyMap(),
                timestamp = System.currentTimeMillis(),
                initiatorDescription = "unknown",
            )
            val responseHeaders = jsonObjectToMap(response.optJSONObject("headers"))
            emit(req, response.optInt("status", -1).let { if (it >= 0) it else null }, responseHeaders)
            // Keep the entry until loadingFinished so body-size accounting (if ever added)
            // has somewhere to land; loadingFinished/loadingFailed clears it.
        } catch (e: Exception) {
            Log.w(TAG, "responseReceived parse failed: ${e.message}")
        }
    }

    internal fun handleLoadingFinished(params: JSONObject) {
        val requestId = params.optString("requestId")
        pendingRequests.remove(requestId)
        capturedResponseBodies.remove(requestId)
    }

    internal fun handleLoadingFailed(params: JSONObject) {
        try {
            val requestId = params.optString("requestId")
            val req = pendingRequests.remove(requestId) ?: return
            val errorText = params.optString("errorText", "failed")
            // No response ever arrived -- still emit so the failure is visible, matching
            // this adapter's "never silently drop browser activity" contract from the class doc.
            emit(req, null, mapOf("X-Requestr-Error" to errorText))
            capturedResponseBodies.remove(requestId)
        } catch (e: Exception) {
            Log.w(TAG, "loadingFailed parse failed: ${e.message}")
        }
    }

    private fun emit(req: PendingRequest, statusCode: Int?, responseHeaders: Map<String, String>) {
        val host = runCatching { java.net.URI(req.url).host }.getOrNull() ?: req.url
        val capturedBody = capturedResponseBodies[req.requestId]
        onEvent(
            HttpEvent(
                eventId = "cdp-net-${req.requestId}-${req.timestamp}",
                timestamp = req.timestamp,
                source = ObservationEvent.ObserverSource.BROWSER,
                connectionId = req.requestId,
                method = req.method,
                url = req.url,
                host = host ?: req.url,
                statusCode = statusCode,
                requestHeaders = req.headers,
                responseHeaders = responseHeaders,
                requestSize = 0,
                responseSize = capturedBody?.second ?: 0,
                wasDecrypted = true, // CDP sees plaintext application-layer data by construction
                protocol = "CDP",
                requestId = req.requestId,
                initiator = req.initiatorDescription,
                initiatorStackFrames = req.initiatorStackFrames,
                responseBody = capturedBody?.first,
            )
        )
    }

    private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { key -> map[key] = obj.optString(key, "") }
        return map
    }
}
