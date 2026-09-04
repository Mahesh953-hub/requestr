package com.example.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
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
import java.io.IOException
import java.io.InputStream

/**
 * S2 task 16: the body spill store -- inline/spill threshold, SHA-256 + atomic rename, failure
 * semantics (row kept, truncated flag, health counter), lifecycle deletes and the orphan sweep.
 */
@RunWith(RobolectricTestRunner::class)
class BodyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var store: BodyStore

    /** Small inline limit so tests do not allocate megabytes. */
    private val limit = 1024

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        root = tmp.newFolder("captures")
        store = BodyStore.forTest(root, inlineLimit = limit)
    }

    @After
    fun tearDown() {
        PersistenceHealth.reset()
    }

    private fun row(sessionId: Long = 7L, reqBytes: ByteArray? = null, resText: String? = null) = CapturedTrafficEntity(
        sessionId = sessionId,
        method = "POST",
        url = "https://example.test/upload",
        host = "example.test",
        path = "/upload",
        statusCode = 200,
        protocol = "HTTP/1.1",
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        requestBody = null,
        responseBody = resText,
        requestSizeBytes = (reqBytes?.size ?: 0).toLong(),
        responseSizeBytes = (resText?.length ?: 0).toLong(),
        latencyMs = 1,
        timestamp = 1_000L,
        wasDecrypted = true,
        isRequestBodyBinary = reqBytes != null,
        requestBodyBytes = reqBytes,
        captureMethod = CaptureRowMethod.MITM_PROXY,
    )

    @Test
    fun `bodies at or below the inline limit are not spilled`() {
        assertFalse(store.shouldSpill(limit.toLong()))
        assertTrue(store.shouldSpill(limit + 1L))
        val e = row(reqBytes = ByteArray(limit) { 1 })
        assertFalse(store.needsSpill(e))
        val placed = store.place(e, trafficId = 1L)
        assertNotNull(placed.requestBodyBytes)
        assertNull(placed.requestBodyFilePath)
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `over-limit binary body is spilled with SHA-256 and inline column nulled`() {
        val body = ByteArray(limit + 500) { (it % 251).toByte() }
        val e = row(sessionId = 7L, reqBytes = body)
        assertTrue(store.needsSpill(e))
        val placed = store.place(e, trafficId = 42L)
        assertNull(placed.requestBodyBytes)
        assertFalse(placed.requestBodyBytesTruncated)
        assertEquals("7/42.req.bin", placed.requestBodyFilePath)
        assertEquals(BodyStore.sha256Hex(body), placed.requestBodySha256)
        assertEquals(body.size.toLong(), placed.requestBodyStoredBytes)
        assertEquals(body.size.toLong(), placed.requestBodyTotalBytes)
        assertNull(placed.requestBodyPreview)
        assertArrayEquals(body, store.read("7/42.req.bin"))
        assertTrue(store.verify("7/42.req.bin", placed.requestBodySha256!!))
        assertEquals(body.size.toLong(), store.sessionBytes(7L))
        assertEquals(body.size.toLong(), store.totalBytes())
    }

    @Test
    fun `over-limit text body is spilled with a bounded UTF-8 preview`() {
        val text = buildString { repeat(limit) { append("é") } } // 2 bytes each -> 2x limit bytes
        val e = row(resText = text)
        val placed = store.place(e, trafficId = 5L)
        assertNull(placed.responseBody)
        assertEquals("7/5.res.bin", placed.responseBodyFilePath)
        assertEquals(text.toByteArray().size.toLong(), placed.responseBodyTotalBytes)
        val preview = placed.responseBodyPreview!!
        assertTrue(preview.isNotEmpty())
        assertTrue(text.startsWith(preview))
        assertTrue(preview.toByteArray().size <= BodyStore.PREVIEW_BYTES)
        assertEquals(text, String(store.read("7/5.res.bin")!!, Charsets.UTF_8))
    }

    @Test
    fun `sha256 matches the known test vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BodyStore.sha256Hex("abc".toByteArray()),
        )
        val stored = store.spill(1L, 1L, BodyStore.Direction.REQUEST, "abc".toByteArray(), isText = true) as BodyStore.Result.Stored
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", stored.spilled.sha256)
        assertEquals("abc", stored.spilled.preview)
    }

    @Test
    fun `a failed stream leaves no partial file, keeps the row with truncated flag and counts the failure`() {
        val body = ByteArray(limit + 10)
        val failing = object : InputStream() {
            var served = 0
            override fun read(): Int = throw IOException("disk on fire")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 700) throw IOException("disk on fire")
                val n = minOf(len, 350)
                served += n
                return n
            }
        }
        val r = store.spill(7L, 9L, BodyStore.Direction.RESPONSE, failing, isText = false)
        assertTrue(r is BodyStore.Result.Failed)
        assertEquals(0, root.walkTopDown().count { it.isFile }) // temp removed, no target
        assertEquals(1, PersistenceHealth.snapshot().spillFailures)

        // Through place(): unwritable root -> row kept, inline nulled, truncated raised.
        val brokenStore = BodyStore.forTest(File(tmp.newFile("not-a-dir"), "captures"), inlineLimit = limit)
        val placed = brokenStore.place(row(reqBytes = body), trafficId = 3L)
        assertNull(placed.requestBodyBytes)
        assertNull(placed.requestBodyFilePath)
        assertTrue(placed.requestBodyBytesTruncated)
        assertEquals(body.size.toLong(), placed.requestBodyTotalBytes)
        assertEquals(2, PersistenceHealth.snapshot().spillFailures)
    }

    @Test
    fun `deleteBody, deleteSession and deleteAll remove files and report bytes`() {
        val a = ByteArray(limit + 1) { 1 }
        val b = ByteArray(limit + 2) { 2 }
        store.spill(1L, 1L, BodyStore.Direction.REQUEST, a, isText = false)
        store.spill(1L, 2L, BodyStore.Direction.RESPONSE, b, isText = false)
        store.spill(2L, 3L, BodyStore.Direction.RESPONSE, b, isText = false)
        assertEquals(listOf(1L, 2L), store.sessionsOnDisk().sorted())

        assertTrue(store.deleteBody("1/1.req.bin"))
        assertFalse(store.deleteBody("1/1.req.bin"))
        assertFalse(store.deleteBody(null))
        assertNull(store.read("1/1.req.bin"))

        assertEquals(b.size.toLong(), store.deleteSession(1L))
        assertEquals(0L, store.sessionBytes(1L))
        assertEquals(b.size.toLong(), store.deleteAll())
        assertEquals(0L, store.totalBytes())
        assertTrue(store.sessionsOnDisk().isEmpty())
    }

    @Test
    fun `paths that escape the store root are refused`() {
        assertNull(store.file("../../etc/passwd"))
        assertNull(store.read("../x"))
        assertFalse(store.deleteBody("../x"))
    }

    @Test
    fun `orphan sweep deletes unreferenced and temp files and clears references whose file is gone`(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.capturedTrafficDao()
            val body = ByteArray(limit + 1) { 3 }
            // Row A: referenced file present -> untouched.
            val idA = dao.insert(row(sessionId = 1L))
            val a = store.spill(1L, idA, BodyStore.Direction.REQUEST, body, isText = false) as BodyStore.Result.Stored
            dao.update(dao.getById(idA)!!.copy(requestBodyFilePath = a.spilled.relativePath, requestBodySha256 = a.spilled.sha256))
            // Row B: references a file that no longer exists -> reference cleared, truncated raised.
            val idB = dao.insert(row(sessionId = 1L))
            dao.update(dao.getById(idB)!!.copy(responseBodyFilePath = "1/$idB.res.bin"))
            // Orphans: a file for a row that was deleted, a temp file, and a stray session dir.
            store.spill(1L, 999L, BodyStore.Direction.RESPONSE, body, isText = false)
            File(root, "1/.tmp-abc").writeBytes(ByteArray(10))
            store.spill(5L, 1L, BodyStore.Direction.REQUEST, body, isText = false)

            val result = store.sweepOrphans(db)

            assertEquals(3, result.filesRemoved)
            assertEquals(1, result.referencesCleared)
            assertEquals((body.size * 2 + 10).toLong(), result.bytesFreed)
            assertNotNull(store.read(a.spilled.relativePath))
            assertNull(store.file("1/999.res.bin"))
            assertFalse(File(root, "1/.tmp-abc").exists())
            assertFalse(File(root, "5").exists())
            val b = dao.getById(idB)!!
            assertNull(b.responseBodyFilePath)
            assertTrue(b.responseBodyBytesTruncated)
            assertFalse(b.requestBodyBytesTruncated)
            assertEquals(3, PersistenceHealth.snapshot().orphanedSpillFilesRemoved)
        } finally {
            db.close()
        }
    }

    @Test
    fun `previewText never ends inside a multi-byte sequence`() {
        val bytes = "aé".toByteArray() // 61 C3 A9
        assertEquals("a", BodyStore.previewText(bytes.copyOfRange(0, 2)))
        assertEquals("aé", BodyStore.previewText(bytes))
        assertEquals(3L, BodyStore.utf8Length("aé"))
        assertEquals(4L, BodyStore.utf8Length("\uD83D\uDE00")) // U+1F600
    }
}
