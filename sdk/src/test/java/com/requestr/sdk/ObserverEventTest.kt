package com.requestr.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the parts of the SDK that don't require an Android runtime
 * (RequestrObserver itself calls android.content.Context/Log and needs Robolectric or an
 * instrumented test to exercise end-to-end; ObserverEvent is pure data and is exercised here).
 */
class ObserverEventTest {

    @Test
    fun `event carries every field it was constructed with`() {
        val event = ObserverEvent(
            timestamp = 1_700_000_000_000L,
            url = "https://api.example.com/v1/users",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            statusCode = 201,
            bodySize = 42L,
            durationMs = 128L,
            source = "sdk"
        )

        assertEquals("https://api.example.com/v1/users", event.url)
        assertEquals("POST", event.method)
        assertEquals(201, event.statusCode)
        assertEquals("application/json", event.headers["Content-Type"])
        assertEquals("sdk", event.source)
        assertTrue(event.bodySize > 0)
    }

    @Test
    fun `statusCode is nullable for in-flight or failed requests`() {
        val event = ObserverEvent(
            timestamp = 0L,
            url = "https://api.example.com/timeout",
            method = "GET",
            headers = emptyMap(),
            statusCode = null,
            bodySize = 0L,
            durationMs = 0L,
            source = "sdk"
        )

        assertEquals(null, event.statusCode)
    }
}
