package com.example.interception.gateway

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import com.example.interception.http.HttpBodyDecoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Item 4 / gap A: [GatewayEngine]'s T2 LAN-proxy relay used to force-decode raw request/response
 * bytes as UTF-8 with zero binary detection, and never populated any of
 * [com.example.data.local.CapturedTrafficEntity]'s binary-body columns. [GatewayEngine.persistTransaction]
 * is now wired through [HttpBodyDecoder.forStorage] exactly like
 * [com.example.interception.MitmProxyEngine.emitTransaction]/[com.example.interception.CaptureEngine]'s
 * private persist path, producing the same three-state contract (text/no-body, binary stored,
 * binary exceeded cap). Made `internal` specifically so this test can call it directly without
 * standing up a real [java.net.Socket]/[java.net.ServerSocket] pair.
 *
 * Robolectric: [GatewayEngine.persistTransaction] resolves `RequestrDatabase.get(context)`
 * itself (the real production singleton), same reason [HttpProxyEngineConnectTest] uses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class GatewayEnginePersistTransactionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** S2 task 15: writes go through the persistence actor, so drain it before reading. */
    private fun latestRow() = runBlocking {
        com.example.data.persistence.PersistenceQueue.get(context).awaitIdle()
        RequestrDatabase.get(context).capturedTrafficDao().observeAll().first().first()
    }

    @Test
    fun `text request and response bodies are stored as plain text with no binary flags`() = runBlocking {
        GatewayEngine.init(context)
        GatewayEngine.persistTransaction(
            method = "POST",
            rawUrl = "http://example.test/api",
            statusCode = 200,
            latencyMs = 12L,
            reqHeaders = mapOf("Content-Type" to "application/json"),
            respHeaders = mapOf("Content-Type" to "application/json"),
            reqBodyBytes = "{\"ping\":true}".toByteArray(Charsets.UTF_8),
            respBodyBytes = "{\"pong\":true}".toByteArray(Charsets.UTF_8),
            sourceIp = "192.168.1.50",
            connectionId = "test-text",
        )

        val row = latestRow()
        assertEquals("{\"ping\":true}", row.requestBody)
        assertEquals("{\"pong\":true}", row.responseBody)
        assertFalse(row.isRequestBodyBinary)
        assertFalse(row.isResponseBodyBinary)
        assertNull(row.requestBodyBytes)
        assertNull(row.responseBodyBytes)
        assertFalse(row.requestBodyBytesTruncated)
        assertFalse(row.responseBodyBytesTruncated)
        assertEquals("{\"ping\":true}".toByteArray(Charsets.UTF_8).size.toLong(), row.requestSizeBytes)
        assertEquals("{\"pong\":true}".toByteArray(Charsets.UTF_8).size.toLong(), row.responseSizeBytes)
    }

    @Test
    fun `binary response body is stored byte-exact, never corrupted via a UTF-8 round trip`() = runBlocking {
        GatewayEngine.init(context)
        // Every byte 0..255 once -- guaranteed invalid UTF-8.
        val binaryBytes = ByteArray(256) { it.toByte() }

        GatewayEngine.persistTransaction(
            method = "GET",
            rawUrl = "http://example.test/image.png",
            statusCode = 200,
            latencyMs = 5L,
            reqHeaders = emptyMap(),
            respHeaders = mapOf("Content-Type" to "image/png"),
            reqBodyBytes = ByteArray(0),
            respBodyBytes = binaryBytes,
            sourceIp = null,
            connectionId = "test-binary",
        )

        val row = latestRow()
        assertNull(row.requestBody)
        assertNull(row.responseBody)
        assertFalse(row.isRequestBodyBinary)
        assertTrue(row.isResponseBodyBinary)
        assertArrayEquals(binaryBytes, row.responseBodyBytes)
        assertFalse(row.responseBodyBytesTruncated)
        assertEquals(256L, row.responseSizeBytes)
    }

    @Test
    fun `binary response body exceeding the storage cap is dropped but the true size is kept`() = runBlocking {
        // S2 task 16: with `bodySpill` on the cap becomes a spill decision; this asserts the
        // flag-off contract, so the rollout flag is turned off for the duration of the test
        // (PersistenceQueue.get mirrors the flag into HttpBodyDecoder.spillEnabled).
        val flags = com.example.data.persistence.InterceptorFlags.get(context)
        flags.setBodySpill(false)
        HttpBodyDecoder.spillEnabled = false
        try {
            GatewayEngine.init(context)
            val oversized = ByteArray(HttpBodyDecoder.MAX_STORED_BODY_BYTES + 1) { 0xFF.toByte() }

            GatewayEngine.persistTransaction(
                method = "GET",
                rawUrl = "http://example.test/huge.bin",
                statusCode = 200,
                latencyMs = 5L,
                reqHeaders = emptyMap(),
                respHeaders = emptyMap(),
                reqBodyBytes = ByteArray(0),
                respBodyBytes = oversized,
                sourceIp = null,
                connectionId = "test-oversized",
            )

            val row = latestRow()
            assertTrue(row.isResponseBodyBinary)
            assertNull("bytes must be dropped, not a truncated prefix", row.responseBodyBytes)
            assertTrue(row.responseBodyBytesTruncated)
            assertEquals((HttpBodyDecoder.MAX_STORED_BODY_BYTES + 1).toLong(), row.responseSizeBytes)
        } finally {
            flags.setBodySpill(true)
            HttpBodyDecoder.spillEnabled = true
        }
    }
}
