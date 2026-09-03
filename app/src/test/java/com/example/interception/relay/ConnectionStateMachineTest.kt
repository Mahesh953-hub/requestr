package com.example.interception.relay

import com.example.interception.relay.ConnectionStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ConnectionStateMachineTest {

    @Test
    fun `happy path walks accepted to closed and fires terminal once`() {
        val terminals = mutableListOf<ConnectionStateMachine.Terminal>()
        val m = ConnectionStateMachine(onTerminal = { terminals += it })
        assertEquals(State.ACCEPTED, m.state)
        assertTrue(m.transition(State.DETECTED))
        assertTrue(m.transition(State.TLS))
        assertTrue(m.transition(State.RELAYING))
        assertTrue(m.transition(State.DRAINING))
        assertTrue(m.close())
        assertEquals(State.CLOSED, m.state)
        assertTrue(m.isTerminal)
        assertEquals(listOf<ConnectionStateMachine.Terminal>(ConnectionStateMachine.Terminal.Closed), terminals)
        // Second terminal is ignored.
        assertFalse(m.fail("late"))
        assertEquals(1, terminals.size)
        assertEquals(State.CLOSED, m.state)
    }

    @Test
    fun `plaintext sessions skip TLS and draining is optional`() {
        val m = ConnectionStateMachine()
        assertTrue(m.transition(State.DETECTED))
        assertTrue(m.transition(State.RELAYING))
        assertTrue(m.close())
        assertEquals(State.CLOSED, m.state)
    }

    @Test
    fun `illegal edges are refused and leave the state untouched`() {
        val m = ConnectionStateMachine()
        assertFalse(m.transition(State.TLS))
        assertFalse(m.transition(State.RELAYING))
        assertFalse(m.transition(State.CLOSED))
        assertFalse(m.transition(State.FAILED))
        assertEquals(State.ACCEPTED, m.state)
        m.transition(State.DETECTED)
        m.transition(State.RELAYING)
        assertFalse(m.transition(State.DETECTED))
        assertFalse(m.transition(State.TLS))
        assertEquals(State.RELAYING, m.state)
        m.transition(State.DRAINING)
        assertFalse(m.transition(State.RELAYING))
    }

    @Test
    fun `fail from any non-terminal state records the reason`() {
        for (target in listOf(State.ACCEPTED, State.DETECTED, State.TLS, State.RELAYING, State.DRAINING)) {
            val m = ConnectionStateMachine()
            val path = listOf(State.DETECTED, State.TLS, State.RELAYING, State.DRAINING)
            for (s in path) {
                if (m.state == target) break
                m.transition(s)
            }
            assertEquals(target, m.state)
            assertTrue(m.fail("boom"))
            assertEquals(State.FAILED, m.state)
            assertEquals(ConnectionStateMachine.Terminal.Failed("boom"), m.terminal)
        }
    }

    @Test
    fun `terminal callback fires exactly once under a close-fail race`() {
        val fired = AtomicInteger()
        val m = ConnectionStateMachine(onTerminal = { fired.incrementAndGet() })
        m.transition(State.DETECTED); m.transition(State.RELAYING)
        val start = CountDownLatch(1)
        val threads = (1..8).map { i ->
            Thread {
                start.await()
                if (i % 2 == 0) m.close() else m.fail("race $i")
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, fired.get())
        assertTrue(m.isTerminal)
    }

    @Test
    fun `deadlines are measured against an injected clock`() {
        var now = 1_000L
        val m = ConnectionStateMachine(idleDeadlineMs = 100, totalDeadlineMs = 1_000, clock = { now })
        m.transition(State.DETECTED)
        assertNull(m.checkDeadlines())
        // Idle only applies while relaying/draining.
        now += 500
        assertNull(m.checkDeadlines())
        m.transition(State.RELAYING)
        now += 150
        assertEquals(ConnectionStateMachine.DeadlineBreach.Idle, m.checkDeadlines())
        m.touch()
        assertNull(m.checkDeadlines())
        now += 400 // total elapsed 1050 > 1000
        assertEquals(ConnectionStateMachine.DeadlineBreach.Total, m.checkDeadlines())
        m.close()
        assertNull(m.checkDeadlines())
    }

    @Test
    fun `phase timestamps expose durations for measured timings`() {
        var now = 0L
        val m = ConnectionStateMachine(clock = { now })
        now = 10; m.transition(State.DETECTED)
        now = 30; m.transition(State.TLS)
        now = 75; m.transition(State.RELAYING)
        assertEquals(20L, m.durationBetween(State.DETECTED, State.TLS))
        assertEquals(45L, m.durationBetween(State.TLS, State.RELAYING))
        assertNull(m.durationBetween(State.RELAYING, State.CLOSED))
        assertEquals(0L, m.acceptedAt)
    }
}
