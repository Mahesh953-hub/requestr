package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.headerValues
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Master plan §2.8 — per-saved-request mock mode. When [ApiRequestItem.mockEnabled] is set,
 * [ApiTestrRepository.executeRequest] must return the canned `mock*` fields WITHOUT dialing the
 * network at all -- asserted here via [MockWebServer.requestCount] staying at zero even though a
 * real server is listening and would happily answer. The response is tagged with
 * [com.example.data.models.ApiResponseData.isMocked] so callers can detect a mock without
 * matching on any magic string.
 */
class ApiTestrRepositoryMockModeTest {

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

    @Test
    fun `mock mode returns the canned fields and never touches the network`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("real response, should never be seen"))
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = server.url("/").toString(),
            mockEnabled = true,
            mockStatusCode = 201,
            mockStatusMessage = "Created",
            mockHeaders = listOf(KeyValueItem(key = "X-Mock", value = "yes", isEnabled = true)),
            mockBody = "{\"ok\":true}"
        )

        val resp = ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        assertEquals(201, resp.statusCode)
        assertEquals("Created", resp.statusMessage)
        assertTrue(resp.isMocked)
        assertEquals("mock", resp.protocol)
        assertEquals("yes", resp.headers["X-Mock"])
        assertEquals("{\"ok\":true}", resp.body)
        assertTrue(resp.isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `mock mode honors mockDelayMs before returning`() = runBlocking {
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = server.url("/").toString(),
            mockEnabled = true,
            mockDelayMs = 80L
        )

        val start = System.currentTimeMillis()
        ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("expected at least ~80ms delay, took ${elapsed}ms", elapsed >= 70L)
    }

    @Test
    fun `mock mode with a 500 status code reports isSuccess false`() = runBlocking {
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = server.url("/").toString(),
            mockEnabled = true,
            mockStatusCode = 500
        )

        val resp = ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        assertFalse(resp.isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `mock mode defaults a blank status message to OK`() = runBlocking {
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = server.url("/").toString(),
            mockEnabled = true,
            mockStatusMessage = ""
        )

        val resp = ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        assertEquals("OK", resp.statusMessage)
    }

    @Test
    fun `mock mode preserves repeated Set-Cookie rows`() = runBlocking {
        val item = ApiRequestItem(
            mockEnabled = true,
            mockHeaders = listOf(
                KeyValueItem(key = "Set-Cookie", value = "session=abc; Path=/; HttpOnly"),
                KeyValueItem(key = "Set-Cookie", value = "prefs=dark; Expires=Wed, 21 Oct 2030 07:28:00 GMT"),
            ),
        )

        val resp = ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        assertEquals(2, resp.headerValues("Set-Cookie").size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `live response preserves repeated Set-Cookie lines including Expires comma`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "session=abc; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "prefs=dark; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Secure")
                .setBody("ok")
        )
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = server.url("/").toString(),
        )

        val resp = ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        assertEquals(
            listOf(
                "session=abc; Path=/; HttpOnly",
                "prefs=dark; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Secure",
            ),
            resp.headerValues("Set-Cookie"),
        )
    }

}
