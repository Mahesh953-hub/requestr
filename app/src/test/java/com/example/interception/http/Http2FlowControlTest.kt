package com.example.interception.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** S3 task 57 (gate G27): per-connection and per-stream WINDOW_UPDATE accounting, RFC 9113 §6.9. */
class Http2FlowControlTest {

    @Test
    fun `reserve is bounded by the smaller of the stream and connection windows`() {
        val fc = Http2FlowControl()
        fc.openStream(1)
        fc.openStream(3)
        assertEquals(65_535L, fc.sendConnectionWindowSize)
        assertEquals(16_384, fc.reserve(1, 16_384))
        assertEquals(65_535L - 16_384, fc.sendConnectionWindowSize)
        assertEquals(65_535L - 16_384, fc.sendStreamWindowSize(1))
        assertEquals(65_535L, fc.sendStreamWindowSize(3))
        // Stream 3 is limited by what is left on the connection.
        assertEquals(65_535 - 16_384, fc.reserve(3, 100_000))
        assertEquals(0L, fc.sendConnectionWindowSize)
        assertEquals(0, fc.reserve(1, 0))
    }

    @Test
    fun `a blocked writer wakes when the peer sends WINDOW_UPDATE`() {
        val fc = Http2FlowControl(waitTimeoutMs = 5_000)
        fc.openStream(1)
        assertEquals(65_535, fc.reserve(1, 65_535))
        val got = AtomicInteger(-1)
        val started = CountDownLatch(1)
        val t = Thread {
            started.countDown()
            got.set(fc.reserve(1, 1_000))
        }
        t.start()
        started.await()
        Thread.sleep(50)
        assertEquals("writer must still be parked", -1, got.get())
        assertTrue(fc.onWindowUpdate(0, 500))
        assertTrue(fc.onWindowUpdate(1, 500))
        t.join(2_000)
        assertEquals(500, got.get())
    }

    @Test
    fun `reserve throws when the connection closes or the stream vanishes while waiting`() {
        val fc = Http2FlowControl(waitTimeoutMs = 5_000)
        fc.openStream(1)
        fc.reserve(1, 65_535)
        val closer = Thread { Thread.sleep(50); fc.closeStream(1) }
        closer.start()
        try {
            fc.reserve(1, 10)
            fail("expected FlowControlException")
        } catch (_: Http2FlowControl.FlowControlException) {
        }
        closer.join()

        fc.openStream(3)
        fc.reserve(3, 0)
        val fc2 = Http2FlowControl(waitTimeoutMs = 5_000)
        fc2.openStream(1)
        fc2.reserve(1, 65_535)
        Thread { Thread.sleep(50); fc2.close() }.start()
        try {
            fc2.reserve(1, 10)
            fail("expected FlowControlException on close")
        } catch (_: Http2FlowControl.FlowControlException) {
        }
    }

    @Test
    fun `reserve times out instead of hanging forever`() {
        val fc = Http2FlowControl(waitTimeoutMs = 100)
        fc.openStream(1)
        fc.reserve(1, 65_535)
        val start = System.nanoTime()
        try {
            fc.reserve(1, 1)
            fail("expected timeout")
        } catch (_: Http2FlowControl.FlowControlException) {
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 90)
    }

    @Test
    fun `window update increments of zero or beyond 2^31-1 are rejected`() {
        val fc = Http2FlowControl()
        fc.openStream(1)
        assertFalse(fc.onWindowUpdate(0, 0))
        assertFalse(fc.onWindowUpdate(1, 0))
        assertTrue(fc.onWindowUpdate(0, 1))
        assertFalse("overflow past 2^31-1", fc.onWindowUpdate(0, Int.MAX_VALUE))
        assertTrue("unknown stream is ignored", fc.onWindowUpdate(99, 10))
    }

    @Test
    fun `SETTINGS_INITIAL_WINDOW_SIZE change is applied as a delta to every open stream`() {
        val fc = Http2FlowControl()
        fc.openStream(1)
        fc.reserve(1, 10_000)
        assertTrue(fc.onInitialWindowSizeChanged(100_000))
        assertEquals(100_000L - 10_000, fc.sendStreamWindowSize(1))
        assertTrue(fc.onInitialWindowSizeChanged(5_000))
        assertEquals(5_000L - 10_000, fc.sendStreamWindowSize(1)) // may go negative, §6.9.2
        fc.openStream(3)
        assertEquals(5_000L, fc.sendStreamWindowSize(3))
    }

    @Test
    fun `receive side rejects data beyond the advertised window and batches WINDOW_UPDATEs`() {
        val fc = Http2FlowControl(localInitialWindowSize = 65_535)
        fc.openStream(1)
        assertTrue(fc.onDataReceived(1, 60_000))
        assertFalse("stream window exceeded", fc.onDataReceived(1, 6_000))
        fc.openStream(3)
        // Connection window is shared: 60_000 already consumed, 6_000 more overflows it.
        assertFalse("connection window exceeded", fc.onDataReceived(3, 6_000))
        assertTrue(fc.onDataReceived(3, 5_000))

        val fresh = Http2FlowControl(localInitialWindowSize = 65_535)
        fresh.openStream(1)
        assertTrue(fresh.onDataReceived(1, 10_000))
        assertEquals(0 to 0, fresh.consumed(1, 10_000)) // below the half-window threshold
        assertTrue(fresh.onDataReceived(1, 30_000))
        val (conn, stream) = fresh.consumed(1, 30_000)
        assertEquals(40_000, conn)
        assertEquals(40_000, stream)
        // Windows are restored by the credited amount.
        assertTrue(fresh.onDataReceived(1, 60_000))
    }

    @Test
    fun `data on an unknown stream still consumes the connection window`() {
        val fc = Http2FlowControl(localInitialWindowSize = 65_535)
        assertTrue(fc.onDataReceived(7, 65_535))
        assertFalse(fc.onDataReceived(7, 1))
    }
}
