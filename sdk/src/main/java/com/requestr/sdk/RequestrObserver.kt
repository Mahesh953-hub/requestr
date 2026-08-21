package com.requestr.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Requestr Observer SDK — zero-dependency instrumentation for any Android app.
 *
 * Usage:
 *   RequestrObserver.init(context)
 *   RequestrObserver.observe(url = "https://api.example.com", method = "POST", headers = ...)
 *
 * The SDK communicates with the Local API (port 9876, see LocalApiServer's `/api/observe`
 * endpoint in the requestr app) if running, or stores events locally and batches them when
 * connectivity returns. Independent of the requestr `:app` module — this is the module a
 * *third-party* app embeds to have its own outbound traffic show up inside requestr.
 */
object RequestrObserver {
    private const val TAG = "RequestrObserver"
    private var context: Context? = null
    private var apiBase = "http://127.0.0.1:9876"
    private val pendingEvents = mutableListOf<ObserverEvent>()

    // Own SupervisorJob-scoped CoroutineScope rather than GlobalScope: flush() calls from a
    // dying host Activity/Process would otherwise leak a bare GlobalScope coroutine with no
    // way for the host app to cancel it.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    fun init(ctx: Context, apiBaseUrl: String = "http://127.0.0.1:9876") {
        context = ctx.applicationContext
        apiBase = apiBaseUrl
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
        val event = ObserverEvent(
            timestamp = System.currentTimeMillis(),
            url = url,
            method = method,
            headers = headers,
            statusCode = statusCode,
            bodySize = bodySize,
            durationMs = durationMs,
            source = "sdk"
        )
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
                    })
                }
                val body = """{"events":$json}"""
                val request = Request.Builder()
                    .url("$apiBase/api/observe")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
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

data class ObserverEvent(
    val timestamp: Long,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val statusCode: Int?,
    val bodySize: Long,
    val durationMs: Long,
    val source: String,
)
