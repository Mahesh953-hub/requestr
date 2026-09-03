package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TransactionTimingEntity
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.relay.ConnectionTimingTracker
import com.example.interception.relay.UpstreamSetupTimings
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2 task 18 -- the HTTP/1.x response leg hands the tracker's measured phases to the timed
 * delivery callback, which takes precedence over the plain one; without a tracker (or for a
 * request the tracker never saw) the transaction is delivered with `null` timings, never
 * invented ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineTimingTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun request() = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://timing.example/api", host = "timing.example",
    )

    private fun response() = ParsedHttpMessage(
        isRequest = false, method = null, statusCode = 200, statusMessage = "OK",
        headers = mapOf("content-type" to "text/plain"), body = "ok",
    )

    @Test
    fun `response leg emits the tracker's measured phases through the timed callback`() {
        var captured: CapturedTrafficEntity? = null
        var timings: TransactionTimingEntity? = null
        var plainCalls = 0
        val engine = MitmProxyEngine(
            onDecryptedTransaction = { plainCalls++ },
            onDecryptedTransactionTimed = { e, t -> captured = e; timings = t },
        )
        var now = 10_000L
        val tracker = ConnectionTimingTracker(clock = { now })
        tracker.attachSetup(UpstreamSetupTimings(dnsMs = 3, connectMs = 21, tlsMs = 44))
        val req = request()
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(req to 10_000L) }
        tracker.onRequestSent(req, startedAt = 10_000L, sentAt = 10_002L)
        now = 10_050L; tracker.markFirstResponseByte()
        now = 10_070L

        engine.handleParsedResponse(
            db, response(), ByteArray(0), ByteArrayOutputStream(), Any(), pending,
            "timing.example", 443, true, true, MitmProxyEngine.ConnectionProtocolState(), tracker,
        )

        assertNotNull(captured)
        assertEquals("timed callback takes precedence over the plain one", 0, plainCalls)
        val t = timings ?: throw AssertionError("measured phases must reach the callback")
        assertEquals(3L, t.dnsMs)
        assertEquals(21L, t.connectMs)
        assertEquals(44L, t.tlsMs)
        assertEquals(2L, t.sendMs)
        assertEquals(48L, t.waitMs)
        assertEquals(20L, t.receiveMs)
        assertEquals(70L, t.totalMs)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `without a tracker the transaction is delivered with null timings`() {
        var delivered = false
        var timings: TransactionTimingEntity? = TransactionTimingEntity(trafficId = 99)
        val engine = MitmProxyEngine(onDecryptedTransactionTimed = { _, t -> delivered = true; timings = t })
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }

        engine.handleParsedResponse(
            db, response(), ByteArray(0), ByteArrayOutputStream(), Any(), pending,
            "timing.example", 443, true, true, MitmProxyEngine.ConnectionProtocolState(),
        )

        assertTrue(delivered)
        assertNull(timings)
    }

    @Test
    fun `plain callback still works when no timed callback is wired`() {
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })
        val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>().apply { add(request() to System.currentTimeMillis()) }
        engine.handleParsedResponse(
            db, response(), ByteArray(0), ByteArrayOutputStream(), Any(), pending,
            "timing.example", 443, true, true, MitmProxyEngine.ConnectionProtocolState(), ConnectionTimingTracker(),
        )
        assertEquals(200, captured?.statusCode)
    }
}
