package com.example.interception.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the two blocking copy legs of a relayed connection as **structured coroutines** on the
 * relay pool dispatcher (master plan §7 task 7), replacing the raw `Thread` pairs and their
 * `join(60_000)`/`join(30_000)` timeouts.
 *
 * Semantics the old path lacked:
 * - the caller only regains control when **both** legs have finished (or were force-closed), so
 *   a `MITM_DECRYPTED` row can never be written while a leg is still moving bytes;
 * - when the first leg ends the [ConnectionStateMachine] enters `DRAINING` and the other leg gets
 *   [drainTimeoutMs] to finish naturally; after that [onDrainTimeout] runs (the caller closes the
 *   sockets, which unblocks the stuck read) and the eviction is counted in [RelayMetrics];
 * - leg exceptions are captured, never propagated, and reported in [Outcome] so the caller can
 *   decide between `close()` and `fail(reason)` honestly.
 */
object StructuredRelay {

    class Outcome(
        val drainTimedOut: Boolean,
        val errors: List<Throwable>,
        /** True when at least one leg ended by EOF/normal return rather than by exception. */
        val anyLegEndedNormally: Boolean,
    )

    suspend fun run(
        machine: ConnectionStateMachine,
        dispatcher: CoroutineDispatcher,
        metrics: RelayMetrics,
        drainTimeoutMs: Long,
        onDrainTimeout: () -> Unit,
        clientToUpstream: () -> Unit,
        upstreamToClient: () -> Unit,
    ): Outcome {
        val firstDone = CompletableDeferred<Unit>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val normalEnds = AtomicLong()
        var drainTimedOut = false
        coroutineScope {
            val legs = listOf(clientToUpstream, upstreamToClient).map { leg ->
                launch(dispatcher) {
                    try {
                        leg()
                        normalEnds.incrementAndGet()
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        machine.touch()
                        firstDone.complete(Unit)
                    }
                }
            }
            firstDone.await()
            machine.transition(ConnectionStateMachine.State.DRAINING)
            val drained = withTimeoutOrNull(drainTimeoutMs) { legs.forEach { it.join() } } != null
            if (!drained) {
                drainTimedOut = true
                metrics.onEvicted()
                runCatching { onDrainTimeout() }
                legs.forEach { it.join() }
            }
        }
        return Outcome(drainTimedOut, errors.toList(), normalEnds.get() > 0)
    }

    /**
     * Plain byte copy loop for opaque tunnels, using a pooled buffer. Returns when [input] hits
     * EOF; propagates IO exceptions to the caller ([run] captures them).
     */
    fun copy(input: InputStream, output: OutputStream, counter: AtomicLong, buffers: BufferPool, onChunk: (Int) -> Unit = {}) {
        val buf = buffers.acquire()
        try {
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
                onChunk(n)
            }
        } finally {
            buffers.release(buf)
        }
    }

    const val DEFAULT_DRAIN_TIMEOUT_MS = 60_000L
}
