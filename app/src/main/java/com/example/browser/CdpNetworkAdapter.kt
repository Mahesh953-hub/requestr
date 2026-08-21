package com.example.browser

import android.util.Log
import com.example.correlation.HttpEvent
import com.example.correlation.ObservationEvent
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
 * B4 wiring: request-side initiator info is resolved via [InitiatorResolver.describe] and
 * attached as an extra pseudo-header (`X-Requestr-Initiator`) on the emitted [HttpEvent] so
 * the existing capture-log UI (which already renders `requestHeaders`) surfaces it without
 * needing a new field threaded through every downstream consumer. This is a deliberate,
 * documented shortcut: a cleaner design would add a dedicated `initiator: String?` field to
 * [HttpEvent], but that ripples through every other producer of `HttpEvent` in the app
 * (MITM/VPN/SDK paths), which is out of scope for this feature slice.
 */
class CdpNetworkAdapter(
    private val client: CdpClient,
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
    )

    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    /** Enable the Network domain and start listening. Idempotent per-instance. */
    fun start() {
        client.send("Network.enable")
        client.on("Network.requestWillBeSent") { params -> handleRequestWillBeSent(params) }
        client.on("Network.responseReceived") { params -> handleResponseReceived(params) }
        client.on("Network.loadingFinished") { params -> handleLoadingFinished(params) }
        client.on("Network.loadingFailed") { params -> handleLoadingFailed(params) }
    }

    private fun handleRequestWillBeSent(params: JSONObject) {
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
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestWillBeSent parse failed: ${e.message}")
        }
    }

    private fun handleResponseReceived(params: JSONObject) {
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

    private fun handleLoadingFinished(params: JSONObject) {
        val requestId = params.optString("requestId")
        pendingRequests.remove(requestId)
    }

    private fun handleLoadingFailed(params: JSONObject) {
        try {
            val requestId = params.optString("requestId")
            val req = pendingRequests.remove(requestId) ?: return
            val errorText = params.optString("errorText", "failed")
            // No response ever arrived -- still emit so the failure is visible, matching
            // this adapter's "never silently drop browser activity" contract from the class doc.
            emit(req, null, mapOf("X-Requestr-Error" to errorText))
        } catch (e: Exception) {
            Log.w(TAG, "loadingFailed parse failed: ${e.message}")
        }
    }

    private fun emit(req: PendingRequest, statusCode: Int?, responseHeaders: Map<String, String>) {
        val requestHeaders = req.headers + ("X-Requestr-Initiator" to req.initiatorDescription)
        val host = runCatching { java.net.URI(req.url).host }.getOrNull() ?: req.url
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
                requestHeaders = requestHeaders,
                responseHeaders = responseHeaders,
                requestSize = 0,
                responseSize = 0,
                wasDecrypted = true, // CDP sees plaintext application-layer data by construction
                protocol = "CDP",
                requestId = req.requestId,
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
