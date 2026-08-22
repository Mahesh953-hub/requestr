package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FIX 6: [RequestrObserver.flush] used to call `execute().close()` and treat ANY completed HTTP
 * exchange (including 401/5xx) as success -- since [RequestrObserver.pendingEvents] is cleared
 * *before* the network call runs, a non-2xx response meant the batch was gone for good with no
 * observable trace. These tests drive a real [MockWebServer] through 200/401/500 responses and
 * assert: success clears the queue and reports [FlushStatus.Success]; 401 re-queues the batch and
 * reports [FlushStatus.Unauthorized]; 500 re-queues the batch and reports [FlushStatus.ServerError].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RequestrObserverFlushTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        clearPendingEvents()
        resetFlushStatus()
    }

    @After
    fun tearDown() {
        server.shutdown()
        clearPendingEvents()
        resetFlushStatus()
    }

    @Test
    fun `successful flush clears the queue and reports Success`() {
        server.enqueue(MockResponse().setResponseCode(200))
        RequestrObserver.init(context, pairingToken = "good-token", apiBaseUrl = server.url("/").toString().trimEnd('/'))

        RequestrObserver.observe(url = "https://example.com/a")

        val status = awaitFlushStatus()
        assertTrue("expected Success, got $status", status is FlushStatus.Success)
        assertEquals(0, pendingEventCount())
    }

    @Test
    fun `401 response re-queues the batch and reports Unauthorized instead of dropping it`() {
        server.enqueue(MockResponse().setResponseCode(401))
        RequestrObserver.init(context, pairingToken = "stale-token", apiBaseUrl = server.url("/").toString().trimEnd('/'))

        RequestrObserver.observe(url = "https://example.com/b")

        val status = awaitFlushStatus()
        assertTrue("expected Unauthorized, got $status", status is FlushStatus.Unauthorized)
        assertEquals(401, (status as FlushStatus.Unauthorized).httpCode)
        // The event must still be sitting in pendingEvents, not silently discarded.
        assertEquals(1, pendingEventCount())
    }

    @Test
    fun `500 response re-queues the batch and reports ServerError instead of dropping it`() {
        server.enqueue(MockResponse().setResponseCode(500))
        RequestrObserver.init(context, pairingToken = "good-token", apiBaseUrl = server.url("/").toString().trimEnd('/'))

        RequestrObserver.observe(url = "https://example.com/c")

        val status = awaitFlushStatus()
        assertTrue("expected ServerError, got $status", status is FlushStatus.ServerError)
        assertEquals(500, (status as FlushStatus.ServerError).httpCode)
        assertEquals(1, pendingEventCount())
    }

    @Test
    fun `unreachable server re-queues the batch and reports NetworkError`() {
        server.shutdown()
        RequestrObserver.init(context, pairingToken = "good-token", apiBaseUrl = "http://127.0.0.1:1")

        RequestrObserver.observe(url = "https://example.com/d")

        val status = awaitFlushStatus()
        assertTrue("expected NetworkError, got $status", status is FlushStatus.NetworkError)
        assertEquals(1, pendingEventCount())
    }

    // -- reflection helpers -------------------------------------------------------------------
    // Same rationale/technique as OkHttpInterceptorTest's RecordedEventCapture: pendingEvents has
    // no public accessor, and flush() runs on a background coroutine, so tests poll rather than
    // race it.

    private fun clearPendingEvents() {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(RequestrObserver) as MutableList<ObserverEvent>
        synchronized(list) { list.clear() }
    }

    private fun resetFlushStatus() {
        val field = RequestrObserver::class.java.getDeclaredField("_lastFlushStatus")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(RequestrObserver) as kotlinx.coroutines.flow.MutableStateFlow<FlushStatus>
        stateFlow.value = FlushStatus.Idle
    }

    private fun pendingEventCount(): Int {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(RequestrObserver) as List<ObserverEvent>
        return synchronized(list) { list.size }
    }

    private fun awaitFlushStatus(): FlushStatus {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val status = RequestrObserver.lastFlushStatus.value
            if (status !is FlushStatus.Idle) return status
            Thread.sleep(50)
        }
        return RequestrObserver.lastFlushStatus.value
    }
}
