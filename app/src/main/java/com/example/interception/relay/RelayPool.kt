package com.example.interception.relay

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded relay execution for every gateway session (master plan §7 task 7, gate G1).
 *
 * - [parallelism] is `n = min(64, max(32, 2 x availableProcessors))` ([defaultParallelism]): the
 *   number of connections relaying at once. [dispatcher] is `Dispatchers.IO.limitedParallelism`
 *   sized for their blocking copy legs (see the property doc), so relay threads are capped too.
 * - [admit] hands out one permit per connection. With [bounded] on, at most [parallelism]
 *   connections are active; the next [maxQueued] wait (suspending, up to [queueTimeoutMs]) and
 *   anything beyond that is **rejected with a recorded reason** -- never silently dropped. With
 *   [bounded] off (flag `relayPoolBounded=false`) every connection is admitted immediately; the
 *   dispatcher and metrics still apply.
 * - [acceptBackoffMs] gives the accept loop an exponential backoff (1 ms doubling to a 100 ms
 *   ceiling) after `accept()` failures or rejections, so a saturated pool does not spin.
 */
class RelayPool(
    val parallelism: Int = defaultParallelism(),
    val maxQueued: Int = DEFAULT_MAX_QUEUED,
    private val queueTimeoutMs: Long = DEFAULT_QUEUE_TIMEOUT_MS,
    val bounded: Boolean = true,
    val metrics: RelayMetrics = RelayMetrics.global,
    baseDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val buffers: BufferPool = BufferPool(),
) {
    enum class RejectReason { QUEUE_FULL, QUEUE_TIMEOUT }

    sealed class Admission {
        /** Holds one permit; [release] exactly once when the connection is done. */
        class Admitted internal constructor(private val pool: RelayPool) : Admission() {
            private val released = java.util.concurrent.atomic.AtomicBoolean(false)

            fun release() {
                if (released.compareAndSet(false, true)) pool.releasePermit()
            }
        }

        data class Rejected(val reason: RejectReason) : Admission()
    }

    /**
     * Thread bound for the relay legs. Every admitted connection runs two blocking copy legs
     * (client->upstream and upstream->client) plus a short-lived admission/handshake coroutine,
     * so the dispatcher allows `2 x parallelism + LEG_HEADROOM` blocking tasks: exactly enough for
     * every permit holder to have both legs running at once. Anything less would let a saturated
     * pool starve one leg of a connection whose other leg is blocked waiting on it.
     */
    @Suppress("OPT_IN_USAGE")
    val dispatcher: CoroutineDispatcher =
        if (bounded) baseDispatcher.limitedParallelism(2 * parallelism + LEG_HEADROOM) else baseDispatcher

    private val permits = Semaphore(parallelism)
    private val waiting = AtomicInteger()

    /** Connections currently waiting for a permit. */
    val queuedCount: Int get() = waiting.get()

    /** Free permits right now (only meaningful when [bounded]). */
    val availablePermits: Int get() = permits.availablePermits

    /**
     * Acquires a relay permit. Fast path when one is free; otherwise queues (bounded by
     * [maxQueued] and [queueTimeoutMs]). Never throws for pool reasons.
     */
    suspend fun admit(): Admission {
        if (!bounded) {
            metrics.onAdmitted()
            return Admission.Admitted(this)
        }
        if (permits.tryAcquire()) {
            metrics.onAdmitted()
            return Admission.Admitted(this)
        }
        // Reserve a wait slot atomically; back out if the queue is already full.
        while (true) {
            val q = waiting.get()
            if (q >= maxQueued) {
                metrics.onRejected(RejectReason.QUEUE_FULL)
                return Admission.Rejected(RejectReason.QUEUE_FULL)
            }
            if (waiting.compareAndSet(q, q + 1)) break
        }
        metrics.onQueued()
        try {
            val got = withTimeoutOrNull(queueTimeoutMs) { permits.acquire(); true } ?: false
            if (!got) {
                metrics.onRejected(RejectReason.QUEUE_TIMEOUT)
                return Admission.Rejected(RejectReason.QUEUE_TIMEOUT)
            }
            metrics.onAdmitted()
            return Admission.Admitted(this)
        } finally {
            waiting.decrementAndGet()
            metrics.onDequeued()
        }
    }

    /** Runs [block] under a permit, or returns null with the rejection recorded. */
    suspend fun <T> withPermit(block: suspend () -> T): T? {
        val admission = admit()
        val admitted = admission as? Admission.Admitted ?: return null
        try {
            return block()
        } finally {
            admitted.release()
        }
    }

    private fun releasePermit() {
        if (bounded) permits.release()
        metrics.onReleased()
    }

    companion object {
        const val DEFAULT_MAX_QUEUED = 256
        const val DEFAULT_QUEUE_TIMEOUT_MS = 30_000L
        const val MIN_PARALLELISM = 32
        const val MAX_PARALLELISM = 64
        const val ACCEPT_BACKOFF_CEILING_MS = 100L
        const val LEG_HEADROOM = 4

        fun defaultParallelism(processors: Int = Runtime.getRuntime().availableProcessors()): Int =
            minOf(MAX_PARALLELISM, maxOf(MIN_PARALLELISM, 2 * processors))

        /** Exponential accept backoff: 1, 2, 4, ... capped at 100 ms. Zero failures -> no delay. */
        fun acceptBackoffMs(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return 0L
            val shift = minOf(consecutiveFailures - 1, 7)
            return minOf(ACCEPT_BACKOFF_CEILING_MS, 1L shl shift)
        }

        @Volatile
        private var shared: RelayPool? = null

        /** Process-wide pool shared by every gateway/engine; [bounded] applies on first creation. */
        fun shared(bounded: Boolean = true): RelayPool =
            shared ?: synchronized(this) {
                shared ?: RelayPool(bounded = bounded).also { shared = it }
            }

        /** Test hook: forget the shared instance. */
        internal fun resetSharedForTest() {
            shared = null
        }
    }
}
