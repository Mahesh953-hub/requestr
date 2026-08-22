package com.requestr.sdk

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Requestr Observer SDK — zero-dependency instrumentation for any Android app.
 *
 * Usage:
 *   RequestrObserver.init(context, pairingToken)
 *   RequestrObserver.observe(url = "https://api.example.com", method = "POST", headers = ...)
 *
 * The SDK communicates with the Local API (port 9876, see LocalApiServer's `/api/observe`
 * endpoint in the requestr app) if running, or stores events locally and batches them when
 * connectivity returns. Independent of the requestr `:app` module — this is the module a
 * *third-party* app embeds to have its own outbound traffic show up inside requestr.
 *
 * Task 7 expands the surface with [observeDetailed] (bodies, TLS session info, HTTP stack tag)
 * and [recordTiming] (DNS/connect/TLS handshake timing keyed by the OkHttp [Call] instance) so
 * [OkHttpInterceptor], [HttpUrlConnectionWrapper], [CronetInterceptor], and [KtorInterceptor]
 * all funnel into the same batching/auth/retry machinery as the original [observe] call.
 */
object RequestrObserver {
    private const val TAG = "RequestrObserver"
    private var context: Context? = null
    private var apiBase = "http://127.0.0.1:9876"
    private var pairingToken: String? = null
    private val pendingEvents = mutableListOf<ObserverEvent>()

    /** DNS/connect/TLS timing recorded by [RequestrEventListener], keyed by the OkHttp [Call]
     * instance it was observed on (identity-keyed map -- OkHttp's RealCall doesn't override
     * equals/hashCode, so this is a safe, exact per-request correlation with no false merges
     * across concurrent calls to the same URL). Consumed (and removed) by
     * [RequestrOkHttpInterceptor] once the call completes. */
    private val pendingTiming = ConcurrentHashMap<Call, TimingBreakdown>()

    // Own SupervisorJob-scoped CoroutineScope rather than GlobalScope: flush() calls from a
    // dying host Activity/Process would otherwise leak a bare GlobalScope coroutine with no
    // way for the host app to cancel it.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    /**
     * @param pairingToken the token copied from requestr's Settings > SDK Pairing screen
     *   (revealed via `GET /api/pairing-info` while "Show Pairing Token" is on). Sent as
     *   `Authorization: Bearer <pairingToken>` on every [flush] POST -- `/api/observe` rejects
     *   requests without it (401).
     */
    fun init(ctx: Context, pairingToken: String, apiBaseUrl: String = "http://127.0.0.1:9876") {
        context = ctx.applicationContext
        apiBase = apiBaseUrl
        this.pairingToken = pairingToken
        Log.i(TAG, "Observer initialized, API base: $apiBase")
    }

    /** Emit an observation event. Batched if API unreachable. */
    fun observe(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        statusCode: Int? = null,
        bodySize: Long = 0,
        durationMs: Long = 0
    ) {
        enqueue(
            ObserverEvent(
                timestamp = System.currentTimeMillis(),
                url = url,
                method = method,
                headers = headers,
                statusCode = statusCode,
                bodySize = bodySize,
                durationMs = durationMs,
                source = "sdk",
            )
        )
    }

    /**
     * Emit a fully-detailed observation event -- used by the automatic HTTP-stack instrumenters
     * ([OkHttpInterceptor], [HttpUrlConnectionWrapper], [CronetInterceptor], [KtorInterceptor])
     * rather than by hand-written [observe] calls, since it captures request/response bodies,
     * TLS session metadata, and which HTTP stack produced the event.
     */
    fun observeDetailed(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        statusCode: Int? = null,
        requestBody: ByteArray? = null,
        responseBody: ByteArray? = null,
        durationMs: Long = 0,
        httpStack: String? = null,
        tlsVersion: String? = null,
        tlsCipher: String? = null,
        serverCertSubject: String? = null,
        isDecrypted: Boolean = true,
        stackFrames: List<String> = emptyList(),
        timing: TimingBreakdown? = null,
    ) {
        enqueue(
            ObserverEvent(
                timestamp = System.currentTimeMillis(),
                url = url,
                method = method,
                headers = headers,
                statusCode = statusCode,
                bodySize = (requestBody?.size ?: 0).toLong() + (responseBody?.size ?: 0).toLong(),
                durationMs = durationMs,
                source = "sdk",
                requestBodyBase64 = requestBody?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                responseBodyBase64 = responseBody?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                httpStack = httpStack,
                tlsVersion = tlsVersion,
                tlsCipher = tlsCipher,
                serverCertSubject = serverCertSubject,
                isDecrypted = isDecrypted,
                stackFrames = stackFrames,
                dnsMs = timing?.dnsMs,
                connectMs = timing?.connectMs,
                tlsMs = timing?.tlsMs,
            )
        )
    }

    /** Records one leg of DNS/connect/TLS timing for [call], keyed by call identity. Consumed by
     * [consumeTiming]. Safe to call from any thread ([RequestrEventListener]'s callbacks run on
     * OkHttp's internal dispatcher thread, not necessarily the caller's thread). */
    fun recordTiming(call: Call, phase: String, ms: Long) {
        val breakdown = pendingTiming.getOrPut(call) { TimingBreakdown() }
        when (phase) {
            "dns" -> breakdown.dnsMs = ms
            "connect" -> breakdown.connectMs = ms
            "tls" -> breakdown.tlsMs = ms
        }
    }

    /** Overload used by [RequestrEventListener], which only has a URL string in scope for some
     * simpler test doubles -- kept for source compatibility, but [recordTiming] with a [Call] is
     * the real per-request-correlated path used in production. */
    fun recordTiming(url: String, phase: String, ms: Long) {
        // No call identity available here -- best-effort, not correlated to a specific in-flight
        // request. Intentionally a no-op beyond logging: attaching this to the wrong concurrent
        // request would be worse than dropping it.
        Log.d(TAG, "timing (uncorrelated): $phase=$ms ms for $url")
    }

    /** Consumes (removes) the timing breakdown recorded for [call], if any. */
    fun consumeTiming(call: Call): TimingBreakdown? = pendingTiming.remove(call)

    private fun enqueue(event: ObserverEvent) {
        synchronized(pendingEvents) { pendingEvents.add(event) }
        flush()
    }

    /** Flush pending events to the local API. */
    fun flush() {
        if (context == null) return
        val events: List<ObserverEvent>
        synchronized(pendingEvents) {
            events = pendingEvents.toList()
            pendingEvents.clear()
        }
        if (events.isEmpty()) return

        scope.launch {
            try {
                val json = JSONArray()
                for (e in events) {
                    json.put(JSONObject().apply {
                        put("url", e.url)
                        put("method", e.method)
                        put("headers", JSONObject(e.headers))
                        put("statusCode", e.statusCode ?: -1)
                        put("bodySize", e.bodySize)
                        put("durationMs", e.durationMs)
                        put("source", e.source)
                        put("timestamp", e.timestamp)
                        e.requestBodyBase64?.let { put("requestBody", it) }
                        e.responseBodyBase64?.let { put("responseBody", it) }
                        e.httpStack?.let { put("httpStack", it) }
                        e.tlsVersion?.let { put("tlsVersion", it) }
                        e.tlsCipher?.let { put("tlsCipher", it) }
                        e.serverCertSubject?.let { put("serverCertSubject", it) }
                        put("isDecrypted", e.isDecrypted)
                        if (e.stackFrames.isNotEmpty()) put("stackFrames", JSONArray(e.stackFrames))
                        e.dnsMs?.let { put("dnsMs", it) }
                        e.connectMs?.let { put("connectMs", it) }
                        e.tlsMs?.let { put("tlsMs", it) }
                    })
                }
                val body = """{"events":$json}"""
                val requestBuilder = Request.Builder()
                    .url("$apiBase/api/observe")
                    .post(body.toRequestBody("application/json".toMediaType()))
                pairingToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
                client.newCall(requestBuilder.build()).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Flush failed, re-queuing ${events.size} events: ${e.message}")
                synchronized(pendingEvents) { pendingEvents.addAll(events) }
            }
        }
    }

    /** Check if the local API is reachable. Blocking -- call off the main thread. */
    fun isApiReachable(): Boolean {
        return try {
            val response = client.newCall(
                Request.Builder().url("$apiBase/api/ping").get().build()
            ).execute()
            response.use { it.isSuccessful }
        } catch (_: Exception) { false }
    }
}

/** DNS/connect/TLS-handshake timing for a single request, in milliseconds. Any leg may be null
 * if that phase wasn't observed (e.g. connection reuse skips DNS+connect+TLS entirely). */
class TimingBreakdown(
    var dnsMs: Long? = null,
    var connectMs: Long? = null,
    var tlsMs: Long? = null,
)

data class ObserverEvent(
    val timestamp: Long,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val statusCode: Int?,
    val bodySize: Long,
    val durationMs: Long,
    val source: String,
    // Task 7 expanded fields -- all optional so the original 8-arg constructor call sites
    // (this file's own [observe] and ObserverEventTest) keep compiling unchanged.
    val requestBodyBase64: String? = null,
    val responseBodyBase64: String? = null,
    val httpStack: String? = null,
    val tlsVersion: String? = null,
    val tlsCipher: String? = null,
    val serverCertSubject: String? = null,
    val isDecrypted: Boolean = true,
    val stackFrames: List<String> = emptyList(),
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
)
