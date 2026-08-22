package com.example.data.local

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FIX D regression coverage for [CaptureSessionRegistry], the consolidated replacement for
 * three previously-separate "lazily create-once and reuse a per-tier session row" copies
 * ([com.example.api.LocalApiServer.sdkSessionId], now-removed
 * `GatewayEngine.gatewaySessionId`'s unguarded `private var` cache, and
 * [com.example.runtime.RuntimeTrafficSink]'s original correct-but-duplicated implementation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CaptureSessionRegistryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        CaptureSessionRegistry.resetForTest()
    }

    @Test
    fun `repeated calls for the same tier reuse the same session row instead of inserting duplicates`() = runBlocking {
        val db = RequestrDatabase.get(context)

        val first = CaptureSessionRegistry.openSessionIdFor(db, CaptureTier.SDK, port = 9876)
        val second = CaptureSessionRegistry.openSessionIdFor(db, CaptureTier.SDK, port = 9876)

        assertEquals(first, second)
        val sdkSessions = db.captureSessionDao().observeAll().first().filter { it.tier == CaptureTier.SDK }
        assertEquals("must not insert a second SDK session row", 1, sdkSessions.size)
    }

    @Test
    fun `different tiers get independent session rows`() = runBlocking {
        val db = RequestrDatabase.get(context)

        val sdkId = CaptureSessionRegistry.openSessionIdFor(db, CaptureTier.SDK, port = 1111)
        val gatewayId = CaptureSessionRegistry.openSessionIdFor(db, CaptureTier.GATEWAY, port = 2222)
        val runtimeId = CaptureSessionRegistry.openSessionIdFor(db, CaptureTier.RUNTIME)

        assertTrue(sdkId != gatewayId && gatewayId != runtimeId && sdkId != runtimeId)
    }

    @Test
    fun `concurrent callers for the same never-yet-opened tier converge on a single inserted row`() = runBlocking {
        val db = RequestrDatabase.get(context)

        val ids = coroutineScope {
            (1..20).map {
                async { CaptureSessionRegistry.openSessionIdFor(db, CaptureTier.RUNTIME) }
            }.awaitAll()
        }

        assertEquals("every concurrent caller must observe the same session id", 1, ids.toSet().size)
        val runtimeSessions = db.captureSessionDao().observeAll().first().filter { it.tier == CaptureTier.RUNTIME }
        assertEquals("concurrent callers must not race into inserting duplicate rows", 1, runtimeSessions.size)
    }
}
