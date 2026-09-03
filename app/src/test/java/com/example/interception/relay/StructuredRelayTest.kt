package com.example.interception.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StructuredRelayTest {

    @Test
    fun `returns only after both legs finished and the machine is draining`() = runBlocking {
        val m = ConnectionStateMachine()
        m.transition(ConnectionStateMachine.State.DETECTED)
        m.transition(ConnectionStateMachine.State.RELAYING)
        val metrics = RelayMetrics()
        val secondLegDone = AtomicBoolean(false)
        val outcome = StructuredRelay.run(
            machine = m,
            dispatcher = Dispatchers.IO,
            metrics = metrics,
            drainTimeoutMs = 5_000,
            onDrainTimeout = { throw AssertionError("must not time out") },
            clientToUpstream = { /* ends immediately */ },
            upstreamToClient = { Thread.sleep(150); secondLegDone.set(true) },
        )
        assertTrue(secondLegDone.get())
        assertFalse(outcome.drainTimedOut)
        assertTrue(outcome.anyLegEndedNormally)
        assertEquals(ConnectionStateMachine.State.DRAINING, m.state)
        assertEquals(0L, metrics.snapshot().evicted)
    }

    @Test
    fun `a stalled leg is force-closed at the drain deadline and counted as evicted`() = runBlocking {
        val m = ConnectionStateMachine()
        m.transition(ConnectionStateMachine.State.DETECTED)
        m.transition(ConnectionStateMachine.State.RELAYING)
        val metrics = RelayMetrics()
        val stall = CountDownLatch(1)
        val outcome = StructuredRelay.run(
            machine = m,
            dispatcher = Dispatchers.IO,
            metrics = metrics,
            drainTimeoutMs = 100,
            onDrainTimeout = { stall.countDown() }, // "closing the socket" unblocks the read
            clientToUpstream = { },
            upstreamToClient = { stall.await(10, TimeUnit.SECONDS); throw IOException("socket closed") },
        )
        assertTrue(outcome.drainTimedOut)
        assertEquals(1L, metrics.snapshot().evicted)
        assertEquals(1, outcome.errors.size)
        assertTrue(outcome.anyLegEndedNormally)
    }

    @Test
    fun `both legs failing before any byte is reported as not ended normally`() = runBlocking {
        val m = ConnectionStateMachine()
        val outcome = StructuredRelay.run(
            machine = m, dispatcher = Dispatchers.IO, metrics = RelayMetrics(), drainTimeoutMs = 1_000,
            onDrainTimeout = {},
            clientToUpstream = { throw IOException("reset") },
            upstreamToClient = { throw IOException("refused") },
        )
        assertFalse(outcome.anyLegEndedNormally)
        assertEquals(2, outcome.errors.size)
    }

    @Test
    fun `copy moves every byte through a pooled buffer and counts it`() {
        val pool = BufferPool(bufferSize = 16)
        val data = ByteArray(1000) { it.toByte() }
        val out = java.io.ByteArrayOutputStream()
        val counter = java.util.concurrent.atomic.AtomicLong()
        StructuredRelay.copy(data.inputStream(), out, counter, pool)
        assertEquals(1000L, counter.get())
        assertTrue(data.contentEquals(out.toByteArray()))
        assertEquals(1, pool.pooledCount)
    }
}
