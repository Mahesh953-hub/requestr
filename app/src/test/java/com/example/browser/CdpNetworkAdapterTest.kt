package com.example.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [CdpNetworkAdapter] (W7): verifies `initiator`/`initiatorStackFrames` land as
 * first-class [com.example.correlation.HttpEvent] fields instead of the old
 * `X-Requestr-Initiator` pseudo-header hack, by feeding raw CDP JSON straight into the
 * package-internal `handle*` methods (no real WebSocket/CdpClient connection needed).
 */
@RunWith(RobolectricTestRunner::class)
class CdpNetworkAdapterTest {

    private fun jsonOf(raw: String) = JSONObject(raw)

    @Test
    fun `script initiator is exposed as a first-class field, not a request header`() {
        val events = mutableListOf<com.example.correlation.HttpEvent>()
        val adapter = CdpNetworkAdapter(CdpClient("localhost", 9222)) { events.add(it) }

        adapter.handleRequestWillBeSent(
            jsonOf(
                """
                {
                  "requestId": "req-1",
                  "timestamp": 100.0,
                  "request": {"method": "GET", "url": "https://example.com/api", "headers": {"Accept": "*/*"}},
                  "initiator": {
                    "type": "script",
                    "stack": {"callFrames": [{"functionName": "fetchData", "url": "app.js", "lineNumber": 41, "columnNumber": 3, "scriptId": "1"}]}
                  }
                }
                """.trimIndent()
            )
        )
        adapter.handleResponseReceived(
            jsonOf(
                """
                {
                  "requestId": "req-1",
                  "response": {"status": 200, "url": "https://example.com/api", "headers": {"Content-Type": "application/json"}}
                }
                """.trimIndent()
            )
        )

        assertEquals(1, events.size)
        val event = events.first()
        assertTrue(event.initiator!!.startsWith("script: fetchData"))
        assertEquals(1, event.initiatorStackFrames.size)
        assertEquals("fetchData", event.initiatorStackFrames.first().methodName)
        assertEquals("app.js", event.initiatorStackFrames.first().fileName)
        // The old hack would have injected this pseudo-header -- confirm it's gone.
        assertTrue(!event.requestHeaders.containsKey("X-Requestr-Initiator"))
    }

    @Test
    fun `parser initiator has no stack frames but still has a description`() {
        val events = mutableListOf<com.example.correlation.HttpEvent>()
        val adapter = CdpNetworkAdapter(CdpClient("localhost", 9222)) { events.add(it) }

        adapter.handleRequestWillBeSent(
            jsonOf(
                """
                {
                  "requestId": "req-2",
                  "timestamp": 200.0,
                  "request": {"method": "GET", "url": "https://example.com/img.png", "headers": {}},
                  "initiator": {"type": "parser", "url": "https://example.com/index.html", "lineNumber": 9}
                }
                """.trimIndent()
            )
        )
        adapter.handleResponseReceived(
            jsonOf(
                """
                {"requestId": "req-2", "response": {"status": 200, "url": "https://example.com/img.png", "headers": {}}}
                """.trimIndent()
            )
        )

        val event = events.first()
        assertEquals("parser: index.html:10", event.initiator)
        assertTrue(event.initiatorStackFrames.isEmpty())
    }

    @Test
    fun `loadingFailed emits without ever seeing a response, with unknown initiator`() {
        val events = mutableListOf<com.example.correlation.HttpEvent>()
        val adapter = CdpNetworkAdapter(CdpClient("localhost", 9222)) { events.add(it) }

        adapter.handleRequestWillBeSent(
            jsonOf(
                """
                {"requestId": "req-3", "timestamp": 300.0, "request": {"method": "GET", "url": "https://example.com/x", "headers": {}}}
                """.trimIndent()
            )
        )
        adapter.handleLoadingFailed(jsonOf("""{"requestId": "req-3", "errorText": "net::ERR_FAILED"}"""))

        val event = events.first()
        assertEquals("unknown", event.initiator)
    }
}
