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
 */
object RuntimeInstrumentationEngine {
    private const val TAG = "RuntimeInstrumentation"
    private const val POLL_INTERVAL_MS = 2_000L

    private val adapters = mutableListOf<InstrumentationAdapter>()
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

    /** Register an adapter (OkHttp, Cronet, WebView, etc.) */
    fun registerAdapter(adapter: InstrumentationAdapter) {
        adapters.add(adapter)
        Log.i(TAG, "Registered adapter: ${adapter.name}")
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
        state = if (adapters.any { it.isActive() }) InstrumentationState.ACTIVE
                else InstrumentationState.FAILED

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && running) {
                delay(POLL_INTERVAL_MS)
                runCatching { drainToCorrelationEngine() }
                    .onFailure { Log.w(TAG, "Poll failed: ${it.message}") }
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

    /** Drains [pollEvents] and feeds each one into [CorrelationEngine] as a [RuntimeEvent]. */
    private fun drainToCorrelationEngine() {
        val events = pollEvents()
        for (e in events) {
            CorrelationEngine.ingest(
                RuntimeEvent(
                    eventId = "rt-${e.adapter}-${e.timestamp}-${e.url.hashCode()}",
                    timestamp = e.timestamp,
                    source = ObservationEvent.ObserverSource.RUNTIME,
                    connectionId = null,
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
)
