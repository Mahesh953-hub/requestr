package com.requestr.sdk

import android.content.Context
import android.util.Log

/**
 * Requestr Observer SDK — zero-dependency instrumentation for any Android app.
 *
 * Usage:
 *   RequestrObserver.init(context)
 *   RequestrObserver.observe(url = "https://api.example.com", method = "POST", headers = ...)
 *
 * The SDK communicates with the Local API (port 9876) if running, or stores events locally
 * and batches them when connectivity returns.
 */
object RequestrObserver {
    private const val TAG = "RequestrObserver"
    private var context: Context? = null
    private var apiBase = "http://127.0.0.1:9876"
    private val pendingEvents = mutableListOf<ObserverEvent>()

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

    /** Flush pending events to the local API */
    fun flush() {
        val ctx = context ?: return
        val events: List<ObserverEvent>
        synchronized(pendingEvents) {
            events = pendingEvents.toList()
            pendingEvents.clear()
        }
        if (events.isEmpty()) return

        kotlinx.coroutines.GlobalScope.launch {
            try {
                val json = org.json.JSONArray()
                for (e in events) {
                    json.put(org.json.JSONObject().apply {
                        put("url", e.url)
                        put("method", e.method)
                        put("headers", org.json.JSONObject(e.headers))
                        put("statusCode", e.statusCode ?: -1)
                        put("bodySize", e.bodySize)
                        put("durationMs", e.durationMs)
                        put("source", e.source)
                        put("timestamp", e.timestamp)
                    })
                }
                val body = """{"events":$json}"""
                val request = okhttp3.Request.Builder()
                    .url("$apiBase/api/observe")
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                    .build()
                okhttp3.OkHttpClient().newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Flush failed, re-queuing ${events.size} events: ${e.message}")
                synchronized(pendingEvents) { pendingEvents.addAll(events) }
            }
        }
    }

    /** Check if the local API is reachable */
    fun isApiReachable(): Boolean {
        return try {
            val response = okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url("$apiBase/api/ping").get().build()
            ).execute()
            response.isSuccessful
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
