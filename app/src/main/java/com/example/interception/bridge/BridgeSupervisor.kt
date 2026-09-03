package com.example.interception.bridge

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production consumer for native bridge events. Receives JSON event strings from
 * the JNI event callback, parses them into typed [BridgeEvent]s, and dispatches to
 * registered [BridgeEventListener]s.
 *
 * Lifecycle: created and installed by [com.example.interception.tun.Tun2ProxyBridge]
 * at bridge start, cleared/[close]d at bridge stop.
 *
 * Defect C (S0 gaps audit): [onNativeEvent] is called directly from the native tun2proxy
 * packet thread via JNI. Parsing JSON and iterating/invoking every listener synchronously on
 * that thread would block native packet processing on however long Kotlin-side listener code
 * takes to run -- unbounded, from the native thread's point of view. Instead, [onNativeEvent]
 * only offers the raw JSON onto a bounded, nonblocking [queue] and returns immediately; a
 * dedicated daemon [worker] thread owned by this instance drains the queue and does the actual
 * parse + listener dispatch off the native thread. If the queue is ever full (dispatch falling
 * behind ingestion), the event is dropped and counted via [eventsOverflowed] rather than
 * blocking the native thread -- a bounded, observable data-loss policy instead of an unbounded
 * wait.
 */
class BridgeSupervisor {
    private val listeners = CopyOnWriteArrayList<BridgeEventListener>()
    private val eventsReceived = AtomicLong(0)
    private val eventsDropped = AtomicLong(0)
    private val eventsOverflowed = AtomicLong(0)

    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    /** Dedicated worker draining [queue] and dispatching off the native JNI callback thread. */
    private val worker: Thread = Thread(::runDispatchLoop, "requestr-bridge-dispatch").apply {
        isDaemon = true
    }

    companion object {
        private const val TAG = "BridgeSupervisor"

        /** Bounded capacity for the nonblocking event queue between JNI callback and dispatch. */
        private const val QUEUE_CAPACITY = 256

        /** How long the dispatch worker blocks per poll waiting for the next queued event. */
        private const val POLL_TIMEOUT_MS = 100L

        /** Bound on how long [close] waits for the dispatch worker to exit. */
        private const val CLOSE_JOIN_TIMEOUT_MS = 250L
    }

    init {
        worker.start()
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

    /**
     * Called from JNI with a raw JSON event string. Never blocks, never throws: only offers
     * onto the bounded [queue] for the dispatch worker to handle off-thread. If [running] is
     * false (this supervisor has been [close]d), the event is silently discarded -- there is no
     * worker left to drain it.
     */
    fun onNativeEvent(json: String) {
        eventsReceived.incrementAndGet()
        if (!running.get()) return
        if (!queue.offer(json)) {
            eventsOverflowed.incrementAndGet()
            Log.w(TAG, "Event queue full (capacity=$QUEUE_CAPACITY); dropping event")
        }
    }

    /** Runs on [worker]: drains [queue] and dispatches each event until [running] is false. */
    private fun runDispatchLoop() {
        while (running.get()) {
            val json = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            dispatch(json)
        }
    }

    /** Parses [json] and fans it out to every registered listener. Never throws. */
    private fun dispatch(json: String) {
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

    /**
     * Stops the dispatch worker, joining it with a bounded timeout. Idempotent. Does NOT clear
     * [listeners] -- callers that also want that should call [clearListeners] explicitly (kept
     * separate so a caller that is merely re-confirming shutdown doesn't accidentally lose
     * listeners it still needs, e.g. while retrying [close] after a prior timeout).
     */
    fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.interrupt()
        worker.join(CLOSE_JOIN_TIMEOUT_MS)
        if (worker.isAlive) {
            Log.w(TAG, "Dispatch worker still alive after ${CLOSE_JOIN_TIMEOUT_MS}ms join timeout")
        }
    }

    fun eventsReceived(): Long = eventsReceived.get()
    fun eventsDropped(): Long = eventsDropped.get()
    fun eventsOverflowed(): Long = eventsOverflowed.get()

    fun resetMetrics() {
        eventsReceived.set(0)
        eventsDropped.set(0)
        eventsOverflowed.set(0)
    }
}
