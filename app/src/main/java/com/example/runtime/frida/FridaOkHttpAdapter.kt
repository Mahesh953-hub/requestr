package com.example.runtime.frida

import android.content.Context
import com.example.runtime.InstrumentationAdapter
import com.example.runtime.InstrumentationEvent
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [InstrumentationAdapter] that plugs the reflection-only Frida OkHttp `Builder.build` hook
 * ([OkHttpBuilderHookScript]) into [com.example.runtime.RuntimeInstrumentationEngine] the same
 * way [com.example.runtime.http.OkHttpAdapter]/[com.example.runtime.http.CronetAdapter] already
 * do -- register it via `RuntimeInstrumentationEngine.registerAdapter(...)`, and the engine's
 * existing poll loop drains [pollEvents] into both [com.example.correlation.CorrelationEngine]
 * and every registered [com.example.runtime.RuntimeCaptureSink] (e.g.
 * [com.example.runtime.RuntimeTrafficSink], which turns any event with a non-null `url` into a
 * real Room row) -- no new sink type was needed for this to reach real persistence.
 *
 * Pre-arm buffering: [FridaClient.addListener] is registered from [init], not from [start] --
 * Frida's injection is asynchronous (spawn/attach, then the script's own `Java.perform` callback
 * runs on its own schedule), so capture events can legitimately arrive before [start] returns or
 * before anything has called [pollEvents] yet. Those events are queued into [buffered]
 * regardless of [active]; [pollEvents] only refuses to *drain* the queue while inactive (matching
 * every other adapter's `if (!active) return emptyList()` contract) -- it never drops what's
 * already buffered, so the first successful poll after arming returns everything queued up to
 * that point, not just what arrives afterward.
 */
class FridaOkHttpAdapter(
    private val target: FridaClient.FridaTarget,
) : InstrumentationAdapter {

    override val name = "OkHttp(Frida)"

    private val buffered = ConcurrentLinkedQueue<InstrumentationEvent>()
    private var active = false
    private var attachAttempted = false
    private var lastError: String? = null

    private val listener: (FridaEvent) -> Unit = { onFridaEvent(it) }

    init {
        FridaClient.addListener(listener)
    }

    override fun start(context: Context) {
        attachAttempted = true
        active = try {
            FridaClient.startOkHttpHook(context, target)
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    override fun stop() {
        active = false
        // stop() owns teardown of the underlying Frida process too — not just a local flag flip.
        // Without this, FridaClient's frida-server/script processes keep running after the adapter
        // reports stopped. The listener is deliberately NOT removed here: it was registered once
        // in init (the pre-arm buffering contract) and must survive a stop()→start() cycle so a
        // later restart still receives and buffers events instead of being deaf after one stop.
        FridaClient.stop()
    }

    override fun isActive() = active

    override fun status(): String = when {
        !attachAttempted -> "not started"
        active -> "attached to $target -- frida.state=${FridaClient.state} -- buffered=${buffered.size}"
        else -> "failed to attach ($target) -- frida.state=${FridaClient.state}" +
            (lastError?.let { " -- $it" } ?: "")
    }

    override fun pollEvents(): List<InstrumentationEvent> {
        if (!active) return emptyList()
        val drained = mutableListOf<InstrumentationEvent>()
        while (true) drained.add(buffered.poll() ?: break)
        return drained
    }

    private fun onFridaEvent(event: FridaEvent) {
        parseInstrumentationEvent(event)?.let { buffered.add(it) }
    }

    /**
     * Maps one [OkHttpBuilderHookScript]-emitted [FridaEvent] into an [InstrumentationEvent].
     * Only `response_ready`/`exception` carry request/response data -- `hook_installed`/`armed`/
     * `hook_error` are diagnostics the script sends about itself, not traffic, and are ignored
     * here (never throws: any malformed/unexpected payload is dropped, not surfaced as a crash).
     */
    internal fun parseInstrumentationEvent(event: FridaEvent): InstrumentationEvent? {
        if (event.type != "response_ready" && event.type != "exception") return null
        return try {
            val json = JSONObject(event.data)
            val url = json.optStringOrNull("url")
            InstrumentationEvent(
                adapter = name,
                eventType = event.type,
                method = json.optStringOrNull("method"),
                url = url,
                headers = json.optJSONObject("requestHeaders")?.toStringMap(),
                statusCode = if (json.has("statusCode") && !json.isNull("statusCode")) json.optInt("statusCode") else null,
                errorMessage = json.optStringOrNull("errorMessage"),
                timestamp = if (json.has("timestamp")) json.optLong("timestamp") else System.currentTimeMillis(),
                requestBody = json.optStringOrNull("requestBody"),
                responseBody = json.optStringOrNull("responseBody"),
                requestSizeBytes = json.optStringOrNull("requestBody")?.toByteArray()?.size?.toLong() ?: 0L,
                responseSizeBytes = json.optStringOrNull("responseBody")?.toByteArray()?.size?.toLong() ?: 0L,
                latencyMs = json.optLong("latencyMs", 0L),
                protocol = json.optStringOrNull("protocol"),
                host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() },
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.toStringMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = optString(k, "")
        }
        return map
    }
}
