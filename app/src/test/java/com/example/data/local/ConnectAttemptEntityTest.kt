package com.example.data.local

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [ConnectAttemptEntity] + [ConnectAttemptDao] against a real in-memory Room database --
 * the persisted record every CONNECT tunnel establishment now produces (see ConnectControl.kt),
 * replacing what used to be a blind, un-recorded byte relay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ConnectAttemptEntityTest {

    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RequestrDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and read back a BLOCKED attempt`() = runBlocking {
        val id = db.connectAttemptDao().insert(
            ConnectAttemptEntity(
                sessionId = 1L,
                targetHost = "blocked.example",
                targetPort = 443,
                disposition = "BLOCKED",
                failureReason = "Blocked by rule for blocked.example",
            )
        )
        val loaded = db.connectAttemptDao().getById(id)
        assertEquals("blocked.example", loaded?.targetHost)
        assertEquals("BLOCKED", loaded?.disposition)
        assertEquals("Blocked by rule for blocked.example", loaded?.failureReason)
        assertNull(loaded?.redirectTarget)
    }

    @Test
    fun `observeBySession only returns rows for that session`() = runBlocking {
        db.connectAttemptDao().insert(
            ConnectAttemptEntity(sessionId = 1L, targetHost = "a.example", targetPort = 443, disposition = "ALLOWED_OPAQUE")
        )
        db.connectAttemptDao().insert(
            ConnectAttemptEntity(sessionId = 2L, targetHost = "b.example", targetPort = 443, disposition = "ALLOWED_OPAQUE")
        )
        val session1 = db.connectAttemptDao().observeBySession(1L).first()
        assertEquals(1, session1.size)
        assertEquals("a.example", session1.first().targetHost)
    }

    @Test
    fun `MITM_DECRYPTED row carries negotiatedAlpn and byte counts`() = runBlocking {
        val id = db.connectAttemptDao().insert(
            ConnectAttemptEntity(
                sessionId = 1L,
                targetHost = "mitm.example",
                targetPort = 443,
                disposition = "MITM_DECRYPTED",
                negotiatedAlpn = "h2",
                bytesToClient = 4096,
                bytesToUpstream = 512,
                finishedAt = 123456789L,
            )
        )
        val loaded = db.connectAttemptDao().getById(id)
        assertEquals("h2", loaded?.negotiatedAlpn)
        assertEquals(4096L, loaded?.bytesToClient)
        assertEquals(512L, loaded?.bytesToUpstream)
        assertEquals(123456789L, loaded?.finishedAt)
    }

    @Test
    fun `REDIRECTED row carries the redirect target`() = runBlocking {
        val id = db.connectAttemptDao().insert(
            ConnectAttemptEntity(
                sessionId = 1L,
                targetHost = "old.example",
                targetPort = 443,
                disposition = "REDIRECTED",
                redirectTarget = "new.example:8443",
            )
        )
        val loaded = db.connectAttemptDao().getById(id)
        assertEquals("new.example:8443", loaded?.redirectTarget)
    }

    @Test
    fun `sourceIp is populated for gateway-proxied CONNECTs`() = runBlocking {
        val id = db.connectAttemptDao().insert(
            ConnectAttemptEntity(
                sessionId = 1L,
                targetHost = "remote-target.example",
                targetPort = 443,
                sourceIp = "192.168.1.42",
                disposition = "ALLOWED_OPAQUE",
            )
        )
        val loaded = db.connectAttemptDao().getById(id)
        assertEquals("192.168.1.42", loaded?.sourceIp)
    }

    @Test
    fun `update overwrites byte counts and finishedAt after relay completes`() = runBlocking {
        val entity = ConnectAttemptEntity(
            id = 0,
            sessionId = 1L,
            targetHost = "example.com",
            targetPort = 443,
            disposition = "ALLOWED_OPAQUE",
        )
        val id = db.connectAttemptDao().insert(entity)
        val inserted = db.connectAttemptDao().getById(id)!!
        db.connectAttemptDao().update(inserted.copy(bytesToClient = 100, bytesToUpstream = 50, finishedAt = 42L))
        val updated = db.connectAttemptDao().getById(id)
        assertEquals(100L, updated?.bytesToClient)
        assertEquals(50L, updated?.bytesToUpstream)
        assertEquals(42L, updated?.finishedAt)
    }
}
