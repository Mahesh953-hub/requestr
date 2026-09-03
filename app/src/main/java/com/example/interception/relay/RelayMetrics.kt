package com.example.interception.relay

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Live counters for the bounded relay pool (master plan §7 task 7), consumed by
 * `RelayHealthCard` through [snapshot]. Every field is a lock-free atomic so the relay copy loops
 * can bump them per read without contention; [snapshot] is a best-effort consistent view (the
 * card renders a readout, not an invariant).
 *
 * `active` counts connections currently holding a relay permit, `queued` those waiting for one,
 * `rejected` those refused because the wait queue was full or the wait timed out (never
 * silently -- each rejection carries a [RelayPool.RejectReason] into the reject histogram),
 * `evicted` connections whose drain deadline expired and were force-closed by the pool.
 */
class RelayMetrics {
    private val active = AtomicInteger()
    private val peakActive = AtomicInteger()
    private val queued = AtomicInteger()
    private val peakQueued = AtomicInteger()
    private val admitted = AtomicLong()
    private val rejected = AtomicLong()
    private val evicted = AtomicLong()
    private val completed = AtomicLong()
    private val failed = AtomicLong()
    private val bytesToUpstream = AtomicLong()
    private val bytesToClient = AtomicLong()
    private val rejectReasons = java.util.concurrent.ConcurrentHashMap<RelayPool.RejectReason, AtomicLong>()

    internal fun onQueued() {
        val q = queued.incrementAndGet()
        peakQueued.accumulateAndGet(q) { a, b -> maxOf(a, b) }
    }

    internal fun onDequeued() {
        queued.decrementAndGet()
    }

    internal fun onAdmitted() {
        admitted.incrementAndGet()
        val a = active.incrementAndGet()
        peakActive.accumulateAndGet(a) { x, y -> maxOf(x, y) }
    }

    internal fun onReleased() {
        active.decrementAndGet()
    }

    internal fun onRejected(reason: RelayPool.RejectReason) {
        rejected.incrementAndGet()
        rejectReasons.getOrPut(reason) { AtomicLong() }.incrementAndGet()
    }

    fun onEvicted() {
        evicted.incrementAndGet()
    }

    fun onCompleted() {
        completed.incrementAndGet()
    }

    fun onFailed() {
        failed.incrementAndGet()
    }

    fun addBytesToUpstream(n: Long) {
        if (n > 0) bytesToUpstream.addAndGet(n)
    }

    fun addBytesToClient(n: Long) {
        if (n > 0) bytesToClient.addAndGet(n)
    }

    fun snapshot(): Snapshot = Snapshot(
        active = active.get(),
        peakActive = peakActive.get(),
        queued = queued.get(),
        peakQueued = peakQueued.get(),
        admitted = admitted.get(),
        rejected = rejected.get(),
        evicted = evicted.get(),
        completed = completed.get(),
        failed = failed.get(),
        bytesToUpstream = bytesToUpstream.get(),
        bytesToClient = bytesToClient.get(),
        rejectReasons = rejectReasons.mapValues { it.value.get() },
    )

    fun reset() {
        active.set(0); peakActive.set(0); queued.set(0); peakQueued.set(0)
        admitted.set(0); rejected.set(0); evicted.set(0); completed.set(0); failed.set(0)
        bytesToUpstream.set(0); bytesToClient.set(0)
        rejectReasons.clear()
    }

    data class Snapshot(
        val active: Int,
        val peakActive: Int,
        val queued: Int,
        val peakQueued: Int,
        val admitted: Long,
        val rejected: Long,
        val evicted: Long,
        val completed: Long,
        val failed: Long,
        val bytesToUpstream: Long,
        val bytesToClient: Long,
        val rejectReasons: Map<RelayPool.RejectReason, Long>,
    ) {
        val hasPressure: Boolean get() = queued > 0 || rejected > 0 || evicted > 0
    }

    companion object {
        /**
         * Process-wide instance the engines feed and the UI reads. The pool is one per process
         * (both engines and the plaintext delegate share it), so a single registry is the honest
         * view; tests construct their own [RelayMetrics].
         */
        val global: RelayMetrics = RelayMetrics()
    }
}
