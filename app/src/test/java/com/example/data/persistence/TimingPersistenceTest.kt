package com.example.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.interception.toUiModel
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TransactionTimingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S2 task 18 -- a phase that was not observed round-trips as NULL through the persistence queue
 * and `transaction_timings`, and only becomes the UI model's `-1` "not measured" sentinel at the
 * very last mapping step. Nothing in between may backfill a number.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TimingPersistenceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var queue: PersistenceQueue

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        queue = PersistenceQueue.forTest(db, Dispatchers.IO.limitedParallelism(1))
    }

    @After
    fun tearDown() {
        runBlocking { queue.close() }
        db.close()
        PersistenceHealth.reset()
    }

    private fun tx() = CapturedTrafficEntity(
        sessionId = 1L,
        method = "GET",
        url = "https://timing.test/",
        host = "timing.test",
        path = "/",
        statusCode = 200,
        protocol = "HTTP/1.1",
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        requestBody = null,
        responseBody = null,
        requestSizeBytes = 0,
        responseSizeBytes = 0,
        latencyMs = 80,
        timestamp = 1_000L,
        wasDecrypted = true,
        captureMethod = CaptureRowMethod.MITM_PROXY,
    )

    @Test
    fun `NULL phases survive the round trip as NULL and measured ones keep their value`(): Unit = runBlocking {
        val timings = TransactionTimingEntity(
            trafficId = 0L,
            dnsMs = null, // reused connection
            connectMs = null,
            tlsMs = null,
            sendMs = 0, // measured zero is a value, distinct from NULL
            waitMs = 61,
            receiveMs = 12,
            totalMs = 80,
            queuedAt = 1_000L,
            firstByteAt = 1_061L,
            lastByteAt = 1_073L,
        )
        val id = queue.insertTransaction(tx(), timings = timings).await()
        queue.awaitIdle()

        val row = db.transactionTimingDao().forTraffic(id)
        assertNotNull(row)
        assertNull(row!!.dnsMs)
        assertNull(row.connectMs)
        assertNull(row.tlsMs)
        assertEquals(0L, row.sendMs)
        assertEquals(61L, row.waitMs)
        assertEquals(12L, row.receiveMs)
        assertEquals(80L, row.totalMs)
        assertEquals(1_061L, row.firstByteAt)

        val item = db.capturedTrafficDao().getById(id)!!.toUiModel(row)
        assertEquals(-1L, item.dnsMs)
        assertEquals(-1L, item.connectMs)
        assertEquals(-1L, item.tlsMs)
        assertEquals(61L, item.waitMs)
        assertEquals(12L, item.downloadMs)
    }

    @Test
    fun `a transaction without timings has no timing row and maps to not-measured`(): Unit = runBlocking {
        val id = queue.insertTransaction(tx()).await()
        queue.awaitIdle()
        assertNull(db.transactionTimingDao().forTraffic(id))
        assertEquals(0, db.transactionTimingDao().getAll().size)
        val item = db.capturedTrafficDao().getById(id)!!.toUiModel(null)
        assertEquals(-1L, item.dnsMs)
        assertEquals(-1L, item.waitMs)
        assertEquals(-1L, item.downloadMs)
    }
}
