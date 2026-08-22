package com.example.interception

import com.example.data.local.CapturedTrafficEntity
import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX C regression coverage: [MitmProxyEngine.emitTransaction] used to read
 * `SettingsRepository.settings.value.requestLoggingEnabled`/`responseLoggingEnabled` directly,
 * which silently defaults to `true` in any process where `SettingsRepository.init()` never ran
 * (i.e. exactly the failure mode already fixed for `RequestrVpnService` via
 * `SettingsDataStore.requestLogging.first()`). The engine now takes the resolved booleans as
 * parameters -- [handleClient] is the only real caller and reads them via [SettingsDataStore]
 * once per connection -- so this test drives [emitTransaction] directly with both toggle
 * combinations and asserts persisted bodies/headers actually honour them.
 */
class MitmProxyEngineEmitTransactionTest {

    private fun request(body: String = "req-body") = ParsedHttpMessage(
        isRequest = true,
        method = HttpMethod.POST,
        url = "https://example.com/api",
        path = "/api",
        host = "example.com",
        headers = mapOf("x-request" to "1"),
        body = body,
    )

    private fun response(body: String = "resp-body") = ParsedHttpMessage(
        isRequest = false,
        method = null,
        statusCode = 200,
        headers = mapOf("x-response" to "1"),
        body = body,
    )

    @Test
    fun `logging both enabled persists request and response headers and bodies`() {
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.emitTransaction(request(), response(), 42L, "example.com:443", logRequests = true, logResponses = true)

        val entity = captured ?: throw AssertionError("callback was never invoked")
        assertEquals(mapOf("x-request" to "1"), entity.requestHeaders)
        assertEquals(mapOf("x-response" to "1"), entity.responseHeaders)
        assertEquals("req-body", entity.requestBody)
        assertEquals("resp-body", entity.responseBody)
    }

    @Test
    fun `request logging disabled drops request headers and body but keeps the response`() {
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.emitTransaction(request(), response(), 42L, "example.com:443", logRequests = false, logResponses = true)

        val entity = captured ?: throw AssertionError("callback was never invoked")
        assertTrue("request headers must be dropped when logRequests=false", entity.requestHeaders.isEmpty())
        assertEquals(null, entity.requestBody)
        assertEquals(mapOf("x-response" to "1"), entity.responseHeaders)
        assertEquals("resp-body", entity.responseBody)
    }

    @Test
    fun `response logging disabled drops response headers and body but keeps the request`() {
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.emitTransaction(request(), response(), 42L, "example.com:443", logRequests = true, logResponses = false)

        val entity = captured ?: throw AssertionError("callback was never invoked")
        assertEquals(mapOf("x-request" to "1"), entity.requestHeaders)
        assertEquals("req-body", entity.requestBody)
        assertTrue("response headers must be dropped when logResponses=false", entity.responseHeaders.isEmpty())
        assertEquals(null, entity.responseBody)
    }
}
