package com.example.runtime.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CronetInstrumentation.mapEvent] -- the pure mapping seam extracted so W2's
 * `RequestFinishedInfo` -> [com.example.runtime.InstrumentationEvent] logic is testable without
 * a real Cronet provider or device (see [CronetInstrumentation.mapRequestFinishedInfo], which
 * this function backs).
 */
class CronetEventMappingTest {

    @Test
    fun `successful response maps to response_ready with metrics carried through`() {
        val event = CronetInstrumentation.mapEvent(
            url = "https://api.example.com/v1/things",
            method = "GET",
            statusCode = 200,
            headerList = listOf("Content-Type" to "application/json", "Server" to "envoy"),
            sentBytes = 128L,
            receivedBytes = 4096L,
            totalTimeMs = 87L,
            negotiatedProtocol = "h2",
            exceptionMessage = null,
        )
        assertEquals("Cronet", event.adapter)
        assertEquals("response_ready", event.eventType)
        assertEquals("GET", event.method)
        assertEquals("https://api.example.com/v1/things", event.url)
        assertEquals(200, event.statusCode)
        assertEquals("application/json", event.headers?.get("Content-Type"))
        assertEquals(128L, event.requestSizeBytes)
        assertEquals(4096L, event.responseSizeBytes)
        assertEquals(87L, event.latencyMs)
        assertEquals("h2", event.protocol)
        assertNull(event.errorMessage)
        assertEquals("api.example.com", event.host)
    }

    @Test
    fun `failure maps to exception eventType and carries the error message`() {
        val event = CronetInstrumentation.mapEvent(
            url = "https://unreachable.example.com/",
            method = "POST",
            statusCode = null,
            headerList = emptyList(),
            sentBytes = 0L,
            receivedBytes = 0L,
            totalTimeMs = 5_000L,
            negotiatedProtocol = null,
            exceptionMessage = "net::ERR_CONNECTION_TIMED_OUT",
        )
        assertEquals("exception", event.eventType)
        assertEquals("net::ERR_CONNECTION_TIMED_OUT", event.errorMessage)
        assertNull(event.statusCode)
        assertNull(event.protocol)
    }

    @Test
    fun `duplicate header names keep the last value like a flattened map`() {
        val event = CronetInstrumentation.mapEvent(
            url = "https://example.com/",
            method = "GET",
            statusCode = 200,
            headerList = listOf("Set-Cookie" to "a=1", "Set-Cookie" to "b=2"),
            sentBytes = 0L,
            receivedBytes = 0L,
            totalTimeMs = 1L,
            negotiatedProtocol = "http/1.1",
            exceptionMessage = null,
        )
        assertEquals("b=2", event.headers?.get("Set-Cookie"))
    }

    @Test
    fun `malformed url leaves host null instead of throwing`() {
        val event = CronetInstrumentation.mapEvent(
            url = "not a url",
            method = "GET",
            statusCode = 200,
            headerList = emptyList(),
            sentBytes = 0L,
            receivedBytes = 0L,
            totalTimeMs = 1L,
            negotiatedProtocol = null,
            exceptionMessage = null,
        )
        assertNull(event.host)
    }
}
