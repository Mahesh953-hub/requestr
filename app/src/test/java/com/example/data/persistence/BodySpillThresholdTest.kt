package com.example.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.HeaderKind
import com.example.data.local.RequestrDatabase
import com.example.data.local.TransactionHeaderEntity
import com.example.interception.http.HttpBodyDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S2 task 16 / gate G2 (body spill threshold): through the persistence actor, a body above the
 * inline limit lands in `filesDir/captures/<session>/<id>.<req|res>.bin` with the row carrying
 * the reference columns and no inline copy; a body at the limit stays inline; with no store
 * attached (flag off) nothing changes; and the decoder cap hands spillable bodies through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BodySpillThresholdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var queue: PersistenceQueue
    private lateinit var root: File
    private val limit = 2048

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        queue = PersistenceQueue.forTest(db, Dispatchers.IO.limitedParallelism(1))
        root = tmp.newFolder("captures")
    }

    @After
    fun tearDown() {
        runBlocking { queue.close() }
        db.close()
        PersistenceHealth.reset()
        HttpBodyDecoder.spillEnabled = true
    }

    private fun tx(reqBytes: ByteArray? = null, resText: String? = null, sessionId: Long = 3L) = CapturedTrafficEntity(
        sessionId = sessionId,
        method = "POST",
        url = "https://spill.test/",
        host = "spill.test",
        path = "/",
        statusCode = 200,
        protocol = "HTTP/1.1",
        requestHeaders = mapOf("Host" to "spill.test"),
        responseHeaders = emptyMap(),
        requestBody = null,
        responseBody = resText,
        requestSizeBytes = (reqBytes?.size ?: 0).toLong(),
        responseSizeBytes = (resText?.length ?: 0).toLong(),
        latencyMs = 5,
        timestamp = 1_000L,
        wasDecrypted = true,
        isRequestBodyBinary = reqBytes != null,
        requestBodyBytes = reqBytes,
        captureMethod = CaptureRowMethod.MITM_PROXY,
    )

    @Test
    fun `over-limit body goes to the file, row keeps the reference and its children`(): Unit = runBlocking {
        queue.attachBodyStore(BodyStore.forTest(root, inlineLimit = limit))
        val body = ByteArray(limit * 3) { (it * 7).toByte() }
        val text = "x".repeat(limit + 1)
        val handle = queue.insertTransaction(
            tx(reqBytes = body, resText = text),
            headers = listOf(TransactionHeaderEntity(trafficId = 0, kind = HeaderKind.REQUEST, ordinal = 0, name = "Host", value = "spill.test")),
        )
        val id = handle.await()
        queue.awaitIdle()

        val row = db.capturedTrafficDao().getById(id)!!
        assertNull(row.requestBodyBytes)
        assertNull(row.responseBody)
        assertEquals("3/$id.req.bin", row.requestBodyFilePath)
        assertEquals("3/$id.res.bin", row.responseBodyFilePath)
        assertEquals(BodyStore.sha256Hex(body), row.requestBodySha256)
        assertEquals(body.size.toLong(), row.requestBodyStoredBytes)
        assertEquals(body.size.toLong(), row.requestBodyTotalBytes)
        assertEquals((limit + 1).toLong(), row.responseBodyTotalBytes)
        assertEquals(text, row.responseBodyPreview)
        assertFalse(row.requestBodyBytesTruncated)
        assertFalse(row.responseBodyBytesTruncated)
        assertTrue(row.isRequestBodyBinary)
        assertArrayEquals(body, File(root, "3/$id.req.bin").readBytes())
        assertEquals(text, File(root, "3/$id.res.bin").readText())
        assertEquals(1, db.transactionHeaderDao().count())
        assertEquals(1, db.capturedTrafficDao().getAll().size)
        assertEquals(0, PersistenceHealth.snapshot().spillFailures)
    }

    @Test
    fun `body at the inline limit stays in the row and writes no file`(): Unit = runBlocking {
        queue.attachBodyStore(BodyStore.forTest(root, inlineLimit = limit))
        val body = ByteArray(limit) { 9 }
        val id = queue.insertTransaction(tx(reqBytes = body)).await()
        queue.awaitIdle()
        val row = db.capturedTrafficDao().getById(id)!!
        assertArrayEquals(body, row.requestBodyBytes)
        assertNull(row.requestBodyFilePath)
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `without a store attached bodies are persisted inline as before`(): Unit = runBlocking {
        val body = ByteArray(limit * 3) { 1 }
        val id = queue.insertTransaction(tx(reqBytes = body)).await()
        queue.awaitIdle()
        val row = db.capturedTrafficDao().getById(id)!!
        assertArrayEquals(body, row.requestBodyBytes)
        assertNull(row.requestBodyFilePath)
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `attaching a store queues one startup orphan sweep on the actor`(): Unit = runBlocking {
        File(root, "9").mkdirs()
        File(root, "9/123.req.bin").writeBytes(ByteArray(limit + 1))
        File(root, "9/.tmp-old").writeBytes(ByteArray(3))
        val store = BodyStore.forTest(root, inlineLimit = limit)
        queue.attachBodyStore(store)
        queue.attachBodyStore(store) // idempotent
        queue.awaitIdle()
        assertFalse(File(root, "9/123.req.bin").exists())
        assertFalse(File(root, "9/.tmp-old").exists())
        assertEquals(2, PersistenceHealth.snapshot().orphanedSpillFilesRemoved)
    }

    @Test
    fun `a spill failure keeps the row, raises truncated and counts one failure`(): Unit = runBlocking {
        val unwritable = File(tmp.newFile("file-not-dir"), "captures")
        queue.attachBodyStore(BodyStore.forTest(unwritable, inlineLimit = limit))
        val body = ByteArray(limit + 1) { 2 }
        val id = queue.insertTransaction(tx(reqBytes = body)).await()
        queue.awaitIdle()
        val row = db.capturedTrafficDao().getById(id)!!
        assertNotNull(row)
        assertNull(row.requestBodyBytes)
        assertNull(row.requestBodyFilePath)
        assertTrue(row.requestBodyBytesTruncated)
        assertEquals(body.size.toLong(), row.requestBodyTotalBytes)
        assertEquals(1, PersistenceHealth.snapshot().spillFailures)
        assertEquals(0, PersistenceHealth.snapshot().failedWrites)
    }

    @Test
    fun `decoder clamp passes spillable bodies through only while spilling is enabled`() {
        val big = ByteArray(HttpBodyDecoder.MAX_STORED_BODY_BYTES + 1)
        HttpBodyDecoder.spillEnabled = true
        val on = HttpBodyDecoder.clampForStorage(big)
        assertNotNull(on.bytes)
        assertFalse(on.truncated)

        HttpBodyDecoder.spillEnabled = false
        val off = HttpBodyDecoder.clampForStorage(big)
        assertNull(off.bytes)
        assertTrue(off.truncated)

        HttpBodyDecoder.spillEnabled = true
        val small = HttpBodyDecoder.clampForStorage(ByteArray(10))
        assertEquals(10, small.bytes!!.size)
        assertTrue(BodyStore.MAX_SPILLABLE_BYTES > HttpBodyDecoder.MAX_STORED_BODY_BYTES)
        assertEquals(BodyStore.MAX_SPILLABLE_BYTES, HttpBodyDecoder.MAX_SPILLABLE_BODY_BYTES)
    }
}
