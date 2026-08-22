package com.example.runtime.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [WebViewInstrumentation.parseBridgePayload] -- the pure JSON->
 * [com.example.runtime.InstrumentationEvent] mapper for the MAIN-world JS hook's
 * `window.requestrBridge.postMessage(...)` payload (see `assets/webview_hook.js`).
 *
 * Robolectric is only needed here because `org.json.JSONObject` is an unmocked stub under a
 * plain JVM unit test (see `app/build.gradle.kts`'s `testOptions.unitTests` comment on the
 * QUIC parser tests for the same constraint) -- no Android UI/WebView is exercised.
 */
@RunWith(RobolectricTestRunner::class)
class WebViewCaptureMappingTest {

    @Test
    fun `full fetch payload maps to a response_ready event with bodies`() {
        val raw = """
            {
              "url": "https://example.com/api/data",
              "method": "POST",
              "requestHeaders": {"Content-Type": "application/json"},
              "status": 201,
              "requestBody": "{\"a\":1}",
              "responseBody": "{\"ok\":true}",
              "durationMs": 42,
              "initiator": "fetch @ app.js:10",
              "via": "fetch"
            }
        """.trimIndent()
        val event = WebViewInstrumentation.parseBridgePayload(raw)
        requireNotNull(event)
        assertEquals("WebView", event.adapter)
        assertEquals("response_ready", event.eventType)
        assertEquals("POST", event.method)
        assertEquals("https://example.com/api/data", event.url)
        assertEquals(201, event.statusCode)
        assertEquals("application/json", event.headers?.get("Content-Type"))
        assertEquals("{\"a\":1}", event.requestBody)
        assertEquals("{\"ok\":true}", event.responseBody)
        assertEquals(42L, event.latencyMs)
        assertEquals("example.com", event.host)
        assertNull(event.errorMessage)
    }

    @Test
    fun `missing status maps to null statusCode not zero`() {
        val raw = """{"url": "https://example.com/", "method": "GET"}"""
        val event = WebViewInstrumentation.parseBridgePayload(raw)
        requireNotNull(event)
        assertNull(event.statusCode)
    }

    @Test
    fun `error field maps to exception eventType`() {
        val raw = """
            {"url": "https://example.com/x", "method": "GET", "status": null, "error": "TypeError: Failed to fetch"}
        """.trimIndent()
        val event = WebViewInstrumentation.parseBridgePayload(raw)
        requireNotNull(event)
        assertEquals("exception", event.eventType)
        assertEquals("TypeError: Failed to fetch", event.errorMessage)
    }

    @Test
    fun `malformed json returns null instead of throwing`() {
        val event = WebViewInstrumentation.parseBridgePayload("{not json")
        assertNull(event)
    }

    @Test
    fun `blank url maps to null url so the sink can filter it out`() {
        val raw = """{"url": "", "method": "GET"}"""
        val event = WebViewInstrumentation.parseBridgePayload(raw)
        requireNotNull(event)
        assertNull(event.url)
    }

    @Test
    fun `xhr payload without via field still maps correctly`() {
        val raw = """
            {"url": "https://api.example.com/ping", "method": "GET", "status": 200, "requestHeaders": {}}
        """.trimIndent()
        val event = WebViewInstrumentation.parseBridgePayload(raw)
        requireNotNull(event)
        assertTrue(event.headers!!.isEmpty())
        assertEquals("api.example.com", event.host)
    }
}
