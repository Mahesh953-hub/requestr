package com.example.interception.relay

import com.example.data.models.HttpMethod
import com.example.data.persistence.PersistenceHealth
import com.example.interception.http.ParsedHttpMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** S2 task 18 -- the HTTP/1.x relay's per-connection phase collector. */
class ConnectionTimingTrackerTest {

    private var now = 1_000L
    private val clock = { now }

    @Before
    fun setUp() = PersistenceHealth.reset()

    @After
    fun tearDown() = PersistenceHealth.reset()

    private fun request(path: String = "/a") = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://h.example$path", host = "h.example",
    )

    @Test
    fun `send, wait and receive come from the observed instants and setup attaches to the first transaction only`() {
        val tracker = ConnectionTimingTracker(clock)
        tracker.attachSetup(UpstreamSetupTimings(dnsMs = 7, connectMs = 30, tlsMs = 55))

        val r1 = request("/1")
        tracker.onRequestSent(r1, startedAt = 1_000, sentAt = 1_004)
        now = 1_100; tracker.markFirstResponseByte()
        now = 1_105; tracker.markFirstResponseByte() // second chunk: ignored
        now = 1_160
        val t1 = tracker.completeResponse(r1)
        assertNotNull(t1)
        assertEquals(7L, t1!!.dnsMs)
        assertEquals(30L, t1.connectMs)
        assertEquals(55L, t1.tlsMs)
        assertEquals(4L, t1.sendMs)
        assertEquals(96L, t1.waitMs)
        assertEquals(60L, t1.receiveMs)
        assertEquals(160L, t1.totalMs)
        assertEquals(1_000L, t1.queuedAt)
        assertEquals(1_100L, t1.firstByteAt)
        assertEquals(1_160L, t1.lastByteAt)

        val r2 = request("/2")
        tracker.onRequestSent(r2, startedAt = 1_200, sentAt = 1_201)
        now = 1_230; tracker.markFirstResponseByte()
        now = 1_240
        val t2 = tracker.completeResponse(r2)!!
        assertNull("reused connection: no DNS", t2.dnsMs)
        assertNull("reused connection: no connect", t2.connectMs)
        assertNull("reused connection: no TLS", t2.tlsMs)
        assertEquals(29L, t2.waitMs)
        assertEquals(10L, t2.receiveMs)
    }

    @Test
    fun `a response whose request was never recorded gets no timings and drops stale records`() {
        val tracker = ConnectionTimingTracker(clock)
        tracker.onRequestSent(request("/stale"), startedAt = 1, sentAt = 2)
        now = 50; tracker.markFirstResponseByte()
        assertNull(tracker.completeResponse(request("/other")))
        assertEquals("pairing drifted: stale records are dropped", 0, tracker.inFlight)
        assertNull("first-byte mark is consumed either way", tracker.completeResponse(request("/x")))
    }

    @Test
    fun `no first byte observed means wait and receive are NULL, never zero`() {
        val tracker = ConnectionTimingTracker(clock)
        val r = request()
        tracker.onRequestSent(r, startedAt = 10, sentAt = 12)
        now = 40
        val t = tracker.completeResponse(r)!!
        assertNull(t.waitMs)
        assertNull(t.receiveMs)
        assertNull(t.firstByteAt)
        assertEquals(2L, t.sendMs)
        assertEquals(30L, t.totalMs)
    }

    @Test
    fun `a negative phase is stored as NULL and counted as a timing underflow`() {
        val tracker = ConnectionTimingTracker(clock)
        val r = request()
        tracker.onRequestSent(r, startedAt = 500, sentAt = 520)
        now = 510; tracker.markFirstResponseByte() // first byte "before" the request finished sending
        now = 600
        val t = tracker.completeResponse(r)!!
        assertNull(t.waitMs)
        assertEquals(90L, t.receiveMs)
        assertEquals(100L, t.totalMs)
        assertEquals(1L, PersistenceHealth.snapshot().timingUnderflows)
    }

    @Test
    fun `takeSetup hands the setup phases out exactly once`() {
        val tracker = ConnectionTimingTracker(clock)
        tracker.attachSetup(UpstreamSetupTimings(1, 2, 3))
        assertEquals(UpstreamSetupTimings(1, 2, 3), tracker.takeSetup())
        assertNull(tracker.takeSetup())
        assertNull(ConnectionTimingTracker.phaseOrNull(null, 5L))
        assertEquals(5L, ConnectionTimingTracker.phaseOrNull(10L, 15L))
    }
}
