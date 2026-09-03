package com.example.interception.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Defect C (S0 gaps audit) coverage: [BridgeSupervisor.onNativeEvent] must never block the
 * calling (simulated JNI) thread -- it only offers onto a bounded queue that a dedicated
 * dispatch worker drains off-thread. These tests run on a plain JVM thread standing in for the
 * native tun2proxy callback thread.
 *
 * Uses Robolectric (like [NativeBridgeEventsTest]) so [BridgeEvent.parse]'s
 * `org.json.JSONObject` usage is the real Android implementation rather than the throwing
 * unit-test stub -- otherwise every event would be parsed as [BridgeEvent.Unknown] and never
 * reach a listener at all.
 */
@RunWith(RobolectricTestRunner::class)
class BridgeSupervisorTest {

    private fun sessionStartJson(sessionId: Long) =
        """{"type":"session_start","session_id":$sessionId,"src":"10.0.0.1:1","dst":"1.2.3.4:80","is_tcp":true}"""

    @Test
    fun `dispatches a valid event to registered listeners off the calling thread`() {
        val sup = BridgeSupervisor()
        val latch = CountDownLatch(1)
        var received: BridgeEvent? = null
        var dispatchThreadName: String? = null
        sup.addListener(object : BridgeEventListener {
            override fun onBridgeEvent(event: BridgeEvent) {
                received = event
                dispatchThreadName = Thread.currentThread().name
                latch.countDown()
            }
        })

        sup.onNativeEvent(sessionStartJson(42))

        assertTrue("listener must fire within bound", latch.await(2, TimeUnit.SECONDS))
        assertTrue(received is BridgeEvent.SessionStart)
        assertEquals(42L, (received as BridgeEvent.SessionStart).sessionId)
        assertEquals("requestr-bridge-dispatch", dispatchThreadName)
        assertTrue(dispatchThreadName != Thread.currentThread().name)

        sup.close()
    }

    @Test
    fun `unparseable event increments eventsDropped without reaching listeners`() {
        val sup = BridgeSupervisor()
        var listenerFired = false
        sup.addListener(object : BridgeEventListener {
            override fun onBridgeEvent(event: BridgeEvent) {
                listenerFired = true
            }
        })

        sup.onNativeEvent("{not json")

        // Give the worker a moment to drain the queue.
        val deadline = System.currentTimeMillis() + 2000
        while (sup.eventsDropped() == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals(1L, sup.eventsDropped())
        assertTrue(!listenerFired)

        sup.close()
    }

    @Test
    fun `onNativeEvent never blocks the caller even when many events are offered rapidly`() {
        val sup = BridgeSupervisor()
        val start = System.currentTimeMillis()
        // Offer far more events than the queue capacity in a tight loop -- onNativeEvent must
        // return immediately every time (offer, never block), even once the queue is full and
        // events start overflowing.
        repeat(2000) { i -> sup.onNativeEvent(sessionStartJson(i.toLong())) }
        val elapsed = System.currentTimeMillis() - start

        assertTrue("onNativeEvent must never block the calling thread, took ${elapsed}ms", elapsed < 2000)
        assertEquals(2000L, sup.eventsReceived())

        sup.close()
    }

    @Test
    fun `resetMetrics clears received dropped and overflowed counters`() {
        val sup = BridgeSupervisor()
        sup.onNativeEvent("{not json")
        val deadline = System.currentTimeMillis() + 2000
        while (sup.eventsDropped() == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(sup.eventsReceived() > 0)
        assertTrue(sup.eventsDropped() > 0)

        sup.resetMetrics()

        assertEquals(0L, sup.eventsReceived())
        assertEquals(0L, sup.eventsDropped())
        assertEquals(0L, sup.eventsOverflowed())

        sup.close()
    }

    @Test
    fun `close stops the dispatch worker and is idempotent`() {
        val sup = BridgeSupervisor()
        sup.close()
        sup.close() // must not throw a second time

        // After close(), onNativeEvent must be a safe no-op (no worker left to drain it).
        sup.onNativeEvent(sessionStartJson(1))
        assertEquals(1L, sup.eventsReceived())
    }

    @Test
    fun `clearListeners removes listeners without needing close`() {
        val sup = BridgeSupervisor()
        var fired = false
        sup.addListener(object : BridgeEventListener {
            override fun onBridgeEvent(event: BridgeEvent) {
                fired = true
            }
        })
        sup.clearListeners()
        sup.onNativeEvent(sessionStartJson(1))

        Thread.sleep(300)
        assertTrue(!fired)

        sup.close()
    }
}
