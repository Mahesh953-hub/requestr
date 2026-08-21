package com.example.runtime

import android.content.Context
import android.util.Log

/**
 * Runtime instrumentation engine: adapter pattern for Frida/Xposed/SDK.
 *
 * Adapters register themselves; the engine dispatches events and state changes.
 * Each adapter handles one instrumentation plane: HTTP hooks, socket hooks, WebView, etc.
 */
object RuntimeInstrumentationEngine {
    private const val TAG = "RuntimeInstrumentation"

    private val adapters = mutableListOf<InstrumentationAdapter>()
    private var running = false

    var state: InstrumentationState = InstrumentationState.NOT_STARTED
        private set

    /** Register an adapter (OkHttp, Cronet, WebView, etc.) */
    fun registerAdapter(adapter: InstrumentationAdapter) {
        adapters.add(adapter)
        Log.i(TAG, "Registered adapter: ${adapter.name}")
    }

    /** Start all registered adapters */
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
    }

    /** Stop all adapters */
    fun stop() {
        running = false
        for (adapter in adapters) {
            try { adapter.stop() } catch (_: Exception) {}
        }
        state = InstrumentationState.STOPPED
    }

    /** Poll all adapters for captured events */
    fun pollEvents(): List<InstrumentationEvent> {
        return adapters.flatMap { it.pollEvents() }
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
