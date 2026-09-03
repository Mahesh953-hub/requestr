package com.example.interception.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate G1 (master plan §9): 100 concurrent connections against a 64-permit pool -> at most 64
 * active at once, the remainder queued, none silently dropped; over-capacity refusals carry a
 * recorded reason.
 */
class RelayPoolTest {

    @Test
    fun `default parallelism is clamped to 32-64 and scales with processors`() {
        assertEquals(32, RelayPool.defaultParallelism(processors = 1))
        assertEquals(32, RelayPool.defaultParallelism(processors = 16))
        assertEquals(40, RelayPool.defaultParallelism(processors = 20))
        assertEquals(64, RelayPool.defaultParallelism(processors = 32))
        assertEquals(64, RelayPool.defaultParallelism(processors = 128))
    }

    @Test
    fun `accept backoff is exponential with a 100ms ceiling`() {
        assertEquals(0L, RelayPool.acceptBackoffMs(0))
        assertEquals(1L, RelayPool.acceptBackoffMs(1))
        assertEquals(2L, RelayPool.acceptBackoffMs(2))
        assertEquals(64L, RelayPool.acceptBackoffMs(7))
        assertEquals(100L, RelayPool.acceptBackoffMs(8))
        assertEquals(100L, RelayPool.acceptBackoffMs(50))
    }

    @Test
    fun `100 concurrent connections - at most 64 active, rest queued, none dropped`() = runBlocking {
        val metrics = RelayMetrics()
        val pool = RelayPool(parallelism = 64, maxQueued = 256, metrics = metrics, baseDispatcher = Dispatchers.Default)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val completed = AtomicInteger()

        coroutineScope {
            val jobs = (1..100).map {
                launch(Dispatchers.Default) {
                    val r = pool.withPermit {
                        val now = inFlight.incrementAndGet()
                        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        release.await()
                        inFlight.decrementAndGet()
                        true
                    }
                    if (r == true) completed.incrementAndGet()
                }
            }
            // Wait until the pool is saturated: 64 holders + 36 waiters.
            withTimeout(5_000) {
                while (pool.queuedCount < 36) delay(5)
            }
            val snap = metrics.snapshot()
            assertEquals(64, snap.active)
            assertEquals(36, snap.queued)
            assertEquals(0L, snap.rejected)
            release.complete(Unit)
            jobs.forEach { it.join() }
        }
        assertEquals(100, completed.get())
        assertTrue("peak active ${peak.get()} must be <= 64", peak.get() <= 64)
        val end = metrics.snapshot()
        assertEquals(0, end.active)
        assertEquals(0, end.queued)
        assertEquals(100L, end.admitted)
        assertEquals(0L, end.rejected)
    }

    @Test
    fun `over-capacity connections are rejected with QUEUE_FULL, never silently`() = runBlocking {
        val metrics = RelayMetrics()
        val pool = RelayPool(parallelism = 2, maxQueued = 1, metrics = metrics, baseDispatcher = Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val holders = (1..2).map { async(Dispatchers.Default) { pool.withPermit { release.await(); 1 } } }
        withTimeout(2_000) { while (pool.availablePermits > 0) delay(5) }
        val waiter = async(Dispatchers.Default) { pool.withPermit { 2 } }
        withTimeout(2_000) { while (pool.queuedCount < 1) delay(5) }

        val rejected = pool.admit()
        assertTrue(rejected is RelayPool.Admission.Rejected)
        assertEquals(RelayPool.RejectReason.QUEUE_FULL, (rejected as RelayPool.Admission.Rejected).reason)
        assertEquals(1L, metrics.snapshot().rejectReasons[RelayPool.RejectReason.QUEUE_FULL])

        release.complete(Unit)
        holders.awaitAll()
        assertEquals(2, waiter.await())
    }

    @Test
    fun `queue wait timeout is reported as QUEUE_TIMEOUT`() = runBlocking {
        val metrics = RelayMetrics()
        val pool = RelayPool(parallelism = 1, maxQueued = 4, queueTimeoutMs = 50, metrics = metrics, baseDispatcher = Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) { pool.withPermit { release.await() } }
        withTimeout(2_000) { while (pool.availablePermits > 0) delay(5) }
        val admission = pool.admit()
        assertEquals(RelayPool.Admission.Rejected(RelayPool.RejectReason.QUEUE_TIMEOUT), admission)
        assertEquals(0, pool.queuedCount)
        release.complete(Unit)
        holder.await()
        Unit
    }

    @Test
    fun `unbounded mode admits everything immediately but still counts`() = runBlocking {
        val metrics = RelayMetrics()
        val pool = RelayPool(parallelism = 1, bounded = false, metrics = metrics, baseDispatcher = Dispatchers.Default)
        val a = pool.admit()
        val b = pool.admit()
        assertTrue(a is RelayPool.Admission.Admitted && b is RelayPool.Admission.Admitted)
        assertEquals(2, metrics.snapshot().active)
        (a as RelayPool.Admission.Admitted).release()
        (b as RelayPool.Admission.Admitted).release()
        a.release() // double release is a no-op
        assertEquals(0, metrics.snapshot().active)
    }
}
