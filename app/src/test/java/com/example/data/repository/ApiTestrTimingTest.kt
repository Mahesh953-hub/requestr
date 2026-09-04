package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.ApiResponseData
import com.example.data.models.HttpMethod
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * S2 task 18 -- API-Testr phase timings come from OkHttp's [CallPhaseTimings] event listener, not
 * from fixed fractions of the total. A [MockWebServer] with an artificial headers delay makes
 * the wait (TTFB) phase dominate, which a `latency * ratio` split can never reproduce.
 */
class ApiTestrTimingTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun item() = ApiRequestItem(
        method = HttpMethod.GET,
        url = server.url("/slow").toString(),
        queryParams = emptyList(),
        headers = emptyList(),
    )

    @Test
    fun `phases are measured, sum within the total and are not fixed ratios of it`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("ok").setHeadersDelay(300, TimeUnit.MILLISECONDS),
        )

        val resp = ApiTestrRepository.executeRequest(item(), followRedirects = true, reqClient = client)

        assertTrue(resp.isSuccess)
        assertNull("plain http has no TLS phase; it must be NULL, not 0 or a ratio", resp.tlsTimeMs)
        assertNotNull("DNS for the mock host is observed", resp.dnsTimeMs)
        assertNotNull("TCP connect is observed", resp.connectTimeMs)
        assertNotNull("send is observed", resp.requestTimeMs)
        val wait = resp.responseTimeMs
        assertNotNull("wait (TTFB) is observed", wait)
        assertNotNull("receive is observed", resp.receiveTimeMs)
        assertTrue("wait must cover the injected 300 ms headers delay, was $wait", wait!! >= 250)

        val measuredSum = listOfNotNull(
            resp.dnsTimeMs, resp.connectTimeMs, resp.tlsTimeMs, resp.requestTimeMs, resp.responseTimeMs, resp.receiveTimeMs,
        ).sum()
        assertTrue(
            "phases ($measuredSum ms) may not exceed the wall-clock total (${resp.latencyMs} ms) by more than scheduling slack",
            measuredSum <= resp.latencyMs + 50,
        )

        // The retired fabrication: dns=15%, connect=20%, tls=30%, request=10%, response=25%.
        val latency = resp.latencyMs
        assertFalse(resp.dnsTimeMs == (latency * 0.15).toLong().coerceAtLeast(4))
        assertFalse(resp.responseTimeMs == (latency * 0.25).toLong().coerceAtLeast(10))
        assertFalse(resp.connectionReused)
    }

    @Test
    fun `a reused connection reports NULL for DNS, connect and TLS instead of inventing them`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("first"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("second"))

        val first = ApiTestrRepository.executeRequest(item(), followRedirects = true, reqClient = client)
        val second = ApiTestrRepository.executeRequest(item(), followRedirects = true, reqClient = client)

        assertNotNull(first.connectTimeMs)
        assertTrue("second call reuses the pooled connection", second.connectionReused)
        assertNull(second.dnsTimeMs)
        assertNull(second.connectTimeMs)
        assertNull(second.tlsTimeMs)
        assertNotNull("send/wait/receive are still measured on a reused connection", second.responseTimeMs)
        assertNotNull(second.receiveTimeMs)
    }

    @Test
    fun `a network failure carries no phase values`() = runBlocking {
        val refused = item().copy(url = "http://127.0.0.1:1/refused")
        val resp = ApiTestrRepository.executeRequest(refused, followRedirects = true, reqClient = client)
        assertFalse(resp.isSuccess)
        assertNull(resp.dnsTimeMs)
        assertNull(resp.connectTimeMs)
        assertNull(resp.tlsTimeMs)
        assertNull(resp.requestTimeMs)
        assertNull(resp.responseTimeMs)
        assertNull(resp.receiveTimeMs)
    }

    @Test
    fun `ApiResponseData defaults no longer look like data`() {
        val blank = ApiResponseData()
        assertEquals("", blank.protocol)
        assertEquals(0L, blank.latencyMs)
        assertEquals(0L, blank.sizeBytes)
        assertTrue(blank.headers.isEmpty())
        assertTrue(blank.headerLines.isEmpty())
        assertNull(blank.dnsTimeMs)
        assertNull(blank.connectTimeMs)
        assertNull(blank.tlsTimeMs)
        assertNull(blank.requestTimeMs)
        assertNull(blank.responseTimeMs)
        assertNull(blank.receiveTimeMs)
    }
}
