package com.example.runtime

import android.content.Context
import android.util.Log
import com.example.correlation.CorrelationEngine
import com.example.correlation.ObservationEvent
import com.example.correlation.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runtime instrumentation engine: adapter pattern for Frida/Xposed/SDK.
 *
 * Adapters register themselves; the engine dispatches events and state changes.
 * Each adapter handles one instrumentation plane: HTTP hooks, socket hooks, WebView, etc.
 *
 * R1/R3 wiring: [start] is now actually called (from `MainActivity`, registering the
 * [com.example.runtime.http] adapters), and a background poll loop drains adapter events
 * into [CorrelationEngine] every [POLL_INTERVAL_MS] so `RUNTIME`-plane events genuinely
 * participate in correlation instead of the whole subsystem sitting unreferenced.
 *
 * W1 (runtime-instrumentation scope): adapters now carry bodies/sizes/latency/connection
 * identity (see [InstrumentationEvent]), the engine forwards drained events to any
 * registered [RuntimeCaptureSink] (e.g. [com.example.runtime.RuntimeTrafficSink], which
 * writes real Room rows), and per-adapter event counts + one-line status strings are
 * exposed so the Settings "RUNTIME INSTRUMENTATION" card can show genuine per-adapter
 * activity instead of a single global state enum.
 */
object RuntimeInstrumentationEngine {
    private const val TAG = "RuntimeInstrumentation"
    private const val POLL_INTERVAL_MS = 2_000L

    private val adapters = mutableListOf<InstrumentationAdapter>()
    private val sinks = mutableListOf<RuntimeCaptureSink>()
    private var running = false
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Compose-observable, same rationale as LocalApiServer.isRunningFlow: a plain var
    // never triggers recomposition, so any Settings UI showing this state needs a StateFlow.
    private val _state = MutableStateFlow(InstrumentationState.NOT_STARTED)
    val stateFlow: StateFlow<InstrumentationState> = _state.asStateFlow()
    var state: InstrumentationState
        get() = _state.value
        private set(value) { _state.value = value }

    /** Adapter name -> cumulative count of events drained from it. Real counts, never simulated. */
    private val _eventCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val eventCounts: StateFlow<Map<String, Int>> = _eventCounts.asStateFlow()

    /** Adapter name -> its own one-line status string (see [InstrumentationAdapter.status]). */
    private val _adapterStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val adapterStatuses: StateFlow<Map<String, String>> = _adapterStatuses.asStateFlow()

    /** Register an adapter (OkHttp, Cronet, WebView, etc.) */
    fun registerAdapter(adapter: InstrumentationAdapter) {
        adapters.add(adapter)
        Log.i(TAG, "Registered adapter: ${adapter.name}")
    }

    /**
     * Register a sink that receives every batch of events drained on each poll cycle, in
     * addition to the existing [CorrelationEngine] feed. Used by
     * [com.example.runtime.RuntimeTrafficSink] to persist real Room rows for runtime captures.
     */
    fun registerSink(sink: RuntimeCaptureSink) {
        sinks.add(sink)
    }

    /** Currently registered adapter names, for diagnostics/UI. */
    fun adapterNames(): List<String> = adapters.map { it.name }

    /** Start all registered adapters and begin polling them for events. */
    fun start(context: Context) {
        if (running) return
        running = true
        state = InstrumentationState.INITIALIZING
        for (adapter in adapters) {
            try {
                adapter.start(context)
                Log.i(TAG, "Started: ${adapter.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ${adapter.name}: ${e.message}")
            }
        }
        refreshAdapterStatuses()
        // FAILED means every adapter genuinely failed to start -- not "at least one did".
        state = if (adapters.isEmpty() || adapters.none { it.isActive() }) {
            InstrumentationState.FAILED
        } else {
            InstrumentationState.ACTIVE
        }

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && running) {
                delay(POLL_INTERVAL_MS)
                runCatching { drainToCorrelationEngine() }
                    .onFailure { Log.w(TAG, "Poll failed: ${it.message}") }
                runCatching { refreshAdapterStatuses() }
                    .onFailure { Log.w(TAG, "Status refresh failed: ${it.message}") }
            }
        }
    }

    /** Stop all adapters */
    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        for (adapter in adapters) {
            try { adapter.stop() } catch (_: Exception) {}
        }
        state = InstrumentationState.STOPPED
    }

    /** Poll all adapters for captured events */
    fun pollEvents(): List<InstrumentationEvent> {
        return adapters.flatMap { it.pollEvents() }
    }

    private fun refreshAdapterStatuses() {
        _adapterStatuses.value = adapters.associate { it.name to it.status() }
    }

    /**
     * Drains [pollEvents] and feeds each one into [CorrelationEngine] as a [RuntimeEvent], plus
     * every registered [RuntimeCaptureSink] (real Room persistence), and updates [eventCounts].
     */
    private fun drainToCorrelationEngine() {
        val events = pollEvents()
        if (events.isEmpty()) return
        val perAdapter = events.groupingBy { it.adapter }.eachCount()
        _eventCounts.update { current ->
            val next = current.toMutableMap()
            for ((adapter, count) in perAdapter) {
                next[adapter] = (next[adapter] ?: 0) + count
            }
            next
        }
        for (e in events) {
            CorrelationEngine.ingest(
                RuntimeEvent(
                    eventId = "rt-${e.adapter}-${e.timestamp}-${e.url.hashCode()}",
                    timestamp = e.timestamp,
                    source = ObservationEvent.ObserverSource.RUNTIME,
                    connectionId = e.connectionId,
                    processName = null,
                    pid = null,
                    uid = null,
                    eventType = e.eventType,
                    method = e.method,
                    url = e.url,
                    stackFrames = e.stackFrames ?: emptyList(),
                    adapterType = e.adapter,
                )
            )
        }
        for (sink in sinks) {
            runCatching { sink.onEvents(events) }
                .onFailure { Log.w(TAG, "Sink ${sink::class.simpleName} failed: ${it.message}") }
        }
    }
}

enum class InstrumentationState { NOT_STARTED, INITIALIZING, ACTIVE, FAILED, STOPPED }

/** Interface for a single instrumentation plane */
interface InstrumentationAdapter {
    val name: String
    fun start(context: Context)
    fun stop()
    fun isActive(): Boolean
    fun pollEvents(): List<InstrumentationEvent>

    /**
     * One-line, human-readable status for this adapter, shown verbatim in the Settings
     * "RUNTIME INSTRUMENTATION" card. Adapters that can genuinely fail to initialize (Cronet
     * with no usable provider, WebView on an unsupported device) should override this to
     * report the real reason instead of a bare active/inactive flag.
     */
    fun status(): String = if (isActive()) "active" else "inactive"
}

/** Receives every batch of events an adapter poll cycle drains, for real persistence/telemetry. */
interface RuntimeCaptureSink {
    fun onEvents(events: List<InstrumentationEvent>)
}

/** A captured event from runtime instrumentation */
data class InstrumentationEvent(
    val adapter: String,
    val eventType: String,     // request_started, headers_ready, body_ready, response_ready, exception
    val method: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val statusCode: Int? = null,
    val errorMessage: String? = null,
    val stackFrames: List<com.example.correlation.StackFrame>? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** Real bytes captured for the request body; null when not captured/logged. */
    val requestBody: String? = null,
    /** Real bytes captured for the response body; null when not captured/logged. */
    val responseBody: String? = null,
    val requestSizeBytes: Long = 0,
    val responseSizeBytes: Long = 0,
    val latencyMs: Long = 0,
    /** Negotiated protocol (e.g. "h2", "h3", "http/1.1"), when the adapter can observe it. */
    val protocol: String? = null,
    /** Canonical connection identity (see `com.example.correlation.ConnectionKey`), when known. */
    val connectionId: String? = null,
    /** Resolved host for this request, cached so sinks don't need to re-parse [url]. */
    val host: String? = null,
)
