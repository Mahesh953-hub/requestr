package com.example.interception.bridge

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Production consumer for native bridge events. Receives JSON event strings from
 * the JNI event callback, parses them into typed [BridgeEvent]s, and dispatches to
 * registered [BridgeEventListener]s.
 *
 * Lifecycle: created and installed by [com.example.interception.tun.Tun2ProxyBridge]
 * at bridge start, cleared at bridge stop. Thread-safe: the JNI callback fires from
 * the native tun2proxy thread; listeners are invoked on that thread.
 *
 * Overflow policy: if a listener throws, the exception is caught and logged, never
 * propagated back to the native packet path. The [eventsDropped] counter tracks
 * events that could not be parsed or where all listeners failed.
 */
class BridgeSupervisor {
    private val listeners = CopyOnWriteArrayList<BridgeEventListener>()
    private val eventsReceived = AtomicLong(0)
    private val eventsDropped = AtomicLong(0)

    companion object {
        private const val TAG = "BridgeSupervisor"
    }

    fun addListener(listener: BridgeEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: BridgeEventListener) {
        listeners.remove(listener)
    }

    fun clearListeners() {
        listeners.clear()
    }

    /** Called from JNI with a raw JSON event string. Never blocks, never throws. */
    fun onNativeEvent(json: String) {
        eventsReceived.incrementAndGet()
        val event = BridgeEvent.parse(json)
        if (event is BridgeEvent.Unknown) {
            eventsDropped.incrementAndGet()
            Log.w(TAG, "Unparseable event: ${event.reason}")
            return
        }
        for (listener in listeners) {
            try {
                listener.onBridgeEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Listener ${listener::class.simpleName} threw: ${e.message}")
            }
        }
    }

    fun eventsReceived(): Long = eventsReceived.get()
    fun eventsDropped(): Long = eventsDropped.get()

    fun resetMetrics() {
        eventsReceived.set(0)
        eventsDropped.set(0)
    }
}
