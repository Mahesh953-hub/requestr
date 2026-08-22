package com.example.runtime

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FIX B regression coverage: [RuntimeTrafficSink.persist] used to have a stale comment in place
 * of `connectionId = e.connectionId` claiming the column had not landed yet -- it had (Room
 * schema v9, [com.example.data.local.CapturedTrafficEntity.connectionId]) -- so every
 * runtime-plane row silently dropped its connection lineage key. This confirms a runtime event
 * carrying a `connectionId` actually persists it, and is retrievable via the same exact-join
 * query [com.example.correlation.graph.ConnectionLineage] relies on for every other producer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuntimeTrafficSinkTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a runtime event carrying a connectionId persists it on the captured_traffic row`() = runBlocking {
        val sink = RuntimeTrafficSink(context)
        val connectionId = "6|4|10.0.0.1:51000\u003e93.184.216.34:443"

        val event = InstrumentationEvent(
            adapter = "cronet",
            eventType = "response_ready",
            method = "GET",
            url = "https://example.com/path",
            headers = emptyMap(),
            statusCode = 200,
            timestamp = System.currentTimeMillis(),
            connectionId = connectionId,
            host = "example.com",
        )

        sink.onEvents(listOf(event))

        val db = RequestrDatabase.get(context)
        val dao = db.capturedTrafficDao()
        val deadline = System.currentTimeMillis() + 5000
        var rows = dao.getByConnectionId(connectionId)
        while (rows.isEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
            rows = dao.getByConnectionId(connectionId)
        }

        assertTrue("expected the persisted row to be findable via getByConnectionId", rows.isNotEmpty())
        assertEquals(connectionId, rows.first().connectionId)
        assertEquals("https://example.com/path", rows.first().url)
    }
}
