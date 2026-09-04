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

    // ── Binary-body persistence (real bytes, not just an empty text placeholder) ────

    @Test
    fun `binary response body within the storage cap persists real bytes and the binary flag`() {
        val jpegLike = ByteArray(2048) { (it % 250).toByte() }
        val response = ParsedHttpMessage(
            isRequest = false,
            method = null,
            statusCode = 200,
            headers = mapOf("content-type" to "image/jpeg"),
            body = com.example.interception.http.HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true,
            rawBodyBytes = jpegLike,
        )
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.emitTransaction(request(), response, 10L, "example.com:443", logRequests = true, logResponses = true)

        val entity = captured ?: throw AssertionError("callback was never invoked")
        assertTrue(entity.isResponseBodyBinary)
        assertTrue("response bytes must be the real captured bytes, not the empty text placeholder",
            entity.responseBodyBytes!!.contentEquals(jpegLike))
        assertEquals(false, entity.responseBodyBytesTruncated)
        assertEquals(jpegLike.size.toLong(), entity.responseSizeBytes)
        assertEquals(null, entity.responseBody)
    }

    @Test
    fun `binary response body exceeding the storage cap is not stored but true size and truncation flag survive`() {
        // S2 task 16: with `bodySpill` on the cap becomes a spill decision; this asserts the
        // flag-off contract, so the flag is forced off for the duration of the test.
        com.example.interception.http.HttpBodyDecoder.spillEnabled = false
        try {
            val huge = ByteArray(com.example.interception.http.HttpBodyDecoder.MAX_STORED_BODY_BYTES + 1)
            val response = ParsedHttpMessage(
                isRequest = false,
                method = null,
                statusCode = 200,
                body = com.example.interception.http.HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
                isBodyBinary = true,
                rawBodyBytes = huge,
            )
            var captured: CapturedTrafficEntity? = null
            val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

            engine.emitTransaction(request(), response, 10L, "example.com:443", logRequests = true, logResponses = true)

            val entity = captured ?: throw AssertionError("callback was never invoked")
            assertTrue(entity.isResponseBodyBinary)
            assertEquals(null, entity.responseBodyBytes)
            assertTrue(entity.responseBodyBytesTruncated)
            assertEquals(huge.size.toLong(), entity.responseSizeBytes)
        } finally {
            com.example.interception.http.HttpBodyDecoder.spillEnabled = true
        }
    }

    @Test
    fun `response logging disabled drops binary bytes and the binary flag too, not just headers and text`() {
        val jpegLike = ByteArray(1024)
        val response = ParsedHttpMessage(
            isRequest = false,
            method = null,
            statusCode = 200,
            body = com.example.interception.http.HttpBodyDecoder.BINARY_BODY_PLACEHOLDER,
            isBodyBinary = true,
            rawBodyBytes = jpegLike,
        )
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.emitTransaction(request(), response, 10L, "example.com:443", logRequests = true, logResponses = false)

        val entity = captured ?: throw AssertionError("callback was never invoked")
        assertEquals(false, entity.isResponseBodyBinary)
        assertEquals(null, entity.responseBodyBytes)
        assertEquals(false, entity.responseBodyBytesTruncated)
    }

    @Test
    fun `no body at all is distinguishable from a stored binary body via isResponseBodyBinary`() {
        val response = response(body = "")
        var captured: CapturedTrafficEntity? = null
        val engine = MitmProxyEngine(onDecryptedTransaction = { captured = it })

        engine.emitTransaction(request(), response, 10L, "example.com:443", logRequests = true, logResponses = true)

        val entity = captured ?: throw AssertionError("callback was never invoked")
        assertEquals(false, entity.isResponseBodyBinary)
        assertEquals(null, entity.responseBodyBytes)
        assertEquals(false, entity.responseBodyBytesTruncated)
        assertEquals(null, entity.responseBody)
        assertEquals(0L, entity.responseSizeBytes)
    }
}
