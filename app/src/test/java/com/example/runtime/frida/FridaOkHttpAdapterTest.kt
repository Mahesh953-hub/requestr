package com.example.runtime.frida

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [FridaOkHttpAdapter]'s event parsing + pre-arm buffering contract, and that it plugs
 * into [com.example.runtime.RuntimeInstrumentationEngine] as a real
 * [com.example.runtime.InstrumentationAdapter] whose drained events reach a registered
 * [com.example.runtime.RuntimeCaptureSink] -- the actual [FridaClient]/frida-server process
 * lifecycle is exercised on-device, not here.
 */
@RunWith(RobolectricTestRunner::class)
class FridaOkHttpAdapterTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @org.junit.After
    fun tearDown() {
        com.example.runtime.RuntimeInstrumentationEngine.stop()
    }

    private val sampleResponseJson = """
        {
          "type": "response_ready",
          "url": "https://api.example.com/v1/things",
          "method": "GET",
          "statusCode": 200,
          "protocol": "h2",
          "requestHeaders": {"Authorization": "Bearer x"},
          "responseHeaders": {"Content-Type": "application/json"},
          "requestBody": "",
          "responseBody": "{\"ok\":true}",
          "latencyMs": 42,
          "timestamp": 1234567890
        }
    """.trimIndent()

    @Test
    fun `parses a real response_ready event into an InstrumentationEvent`() {
        val adapter = FridaOkHttpAdapter(FridaClient.FridaTarget.Spawn("com.example.target"))
        val parsed = adapter.parseInstrumentationEvent(FridaEvent(type = "response_ready", data = sampleResponseJson))

        requireNotNull(parsed)
        assertEquals("OkHttp(Frida)", parsed.adapter)
        assertEquals("response_ready", parsed.eventType)
        assertEquals("https://api.example.com/v1/things", parsed.url)
        assertEquals("api.example.com", parsed.host)
        assertEquals("GET", parsed.method)
        assertEquals(200, parsed.statusCode)
        assertEquals("h2", parsed.protocol)
        assertEquals("{\"ok\":true}", parsed.responseBody)
        assertEquals(42L, parsed.latencyMs)
        assertEquals(1234567890L, parsed.timestamp)
        assertEquals("Bearer x", parsed.headers?.get("Authorization"))
    }

    @Test
    fun `ignores diagnostic events like hook_installed and armed`() {
        val adapter = FridaOkHttpAdapter(FridaClient.FridaTarget.Pid(1))
        assertNull(adapter.parseInstrumentationEvent(FridaEvent(type = "hook_installed", data = "{\"className\":\"x\"}")))
        assertNull(adapter.parseInstrumentationEvent(FridaEvent(type = "armed", data = "{\"hooked\":true}")))
    }

    @Test
    fun `malformed json is dropped rather than thrown`() {
        val adapter = FridaOkHttpAdapter(FridaClient.FridaTarget.Pid(1))
        assertNull(adapter.parseInstrumentationEvent(FridaEvent(type = "response_ready", data = "not json")))
    }

    @Test
    fun `pollEvents while inactive returns empty but does not drop buffered events`() {
        val adapter = FridaOkHttpAdapter(FridaClient.FridaTarget.Pid(1))
        FridaOkHttpAdapterTestHooks.deliver(adapter, FridaEvent(type = "response_ready", data = sampleResponseJson))

        assertTrue(adapter.pollEvents().isEmpty()) // inactive: refuses to drain, but must not drop it

        FridaOkHttpAdapterTestHooks.forceActive(adapter)
        val drained = adapter.pollEvents()
        assertEquals(1, drained.size)
        assertEquals("api.example.com", drained.first().host)

        // Second poll: the queue was actually drained, not just peeked.
        assertTrue(adapter.pollEvents().isEmpty())
    }

    @Test
    fun `start against an unavailable frida-server fails cleanly and reports it via status`() {
        val adapter = FridaOkHttpAdapter(FridaClient.FridaTarget.Spawn("com.example.target"))
        // No su / frida-server available in this test sandbox: start() must fail without throwing.
        adapter.start(context)

        assertTrue(!adapter.isActive())
        assertTrue(adapter.status().contains("failed to attach"))
    }

    @Test
    fun `registering with RuntimeInstrumentationEngine after it is already running starts the adapter immediately`() {
        val adapter = FridaOkHttpAdapter(FridaClient.FridaTarget.Pid(1))

        com.example.runtime.RuntimeInstrumentationEngine.start(context)
        com.example.runtime.RuntimeInstrumentationEngine.registerAdapter(adapter)

        // Even though attach fails in this sandbox, the late-registration path must have
        // actually invoked adapter.start(context) rather than leaving it untouched.
        assertTrue(adapter.status() != "not started")
    }
}

/** Reaches into [FridaOkHttpAdapter]'s private members for tests, without making the class `open`. */
private object FridaOkHttpAdapterTestHooks {
    fun deliver(adapter: FridaOkHttpAdapter, event: FridaEvent) {
        val method = FridaOkHttpAdapter::class.java.getDeclaredMethod("onFridaEvent", FridaEvent::class.java)
        method.isAccessible = true
        method.invoke(adapter, event)
    }

    fun forceActive(adapter: FridaOkHttpAdapter) {
        val field = FridaOkHttpAdapter::class.java.getDeclaredField("active")
        field.isAccessible = true
        field.setBoolean(adapter, true)
    }
}
