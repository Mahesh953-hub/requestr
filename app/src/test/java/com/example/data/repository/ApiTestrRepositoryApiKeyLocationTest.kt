package com.example.data.repository

import com.example.data.models.ApiKeyLocation
import com.example.data.models.ApiRequestItem
import com.example.data.models.AuthType
import com.example.data.models.HttpMethod
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Master plan §2.13 — the API-key placement toggle. Previously an [AuthType.API_KEY] credential
 * was always sent as a header; [ApiRequestItem.apiKeyLocation] now lets a saved request send it
 * as a query parameter instead, via [ApiTestrRepository.buildRequestUrl]. Drives a real request
 * at a local [MockWebServer] and inspects the actual wire path/headers rather than only the
 * pure [ApiTestrRepository.buildRequestUrl] helper, so the [ApiTestrRepository.executeRequest]
 * wiring itself is covered too.
 */
class ApiTestrRepositoryApiKeyLocationTest {

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

    private fun apiKeyItem(location: ApiKeyLocation) = ApiRequestItem(
        method = HttpMethod.GET,
        url = server.url("/").toString(),
        queryParams = emptyList(),
        headers = emptyList(),
        authType = AuthType.API_KEY,
        apiKeyHeader = "X-API-Key",
        apiKeyValue = "secret123",
        apiKeyLocation = location
    )

    @Test
    fun `HEADER placement adds the credential as a request header and not the URL`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        ApiTestrRepository.executeRequest(apiKeyItem(ApiKeyLocation.HEADER), followRedirects = true, reqClient = client)

        val recorded = server.takeRequest()
        assertEquals("secret123", recorded.headers["X-API-Key"])
        assertTrue(!recorded.path!!.contains("secret123"))
    }

    @Test
    fun `QUERY_PARAM placement adds the credential to the URL and not as a header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        ApiTestrRepository.executeRequest(apiKeyItem(ApiKeyLocation.QUERY_PARAM), followRedirects = true, reqClient = client)

        val recorded = server.takeRequest()
        assertNull(recorded.headers["X-API-Key"])
        assertTrue(recorded.path!!.contains("X-API-Key=secret123"))
    }

    @Test
    fun `buildRequestUrl folds the query param credential in via HttpUrl so it is percent-encoded`() {
        val item = ApiRequestItem(
            url = "https://example.com/api",
            queryParams = emptyList(),
            authType = AuthType.API_KEY,
            apiKeyHeader = "api key",
            apiKeyValue = "a b",
            apiKeyLocation = ApiKeyLocation.QUERY_PARAM
        )
        val url = ApiTestrRepository.buildRequestUrl(item)
        assertEquals("https://example.com/api?api%20key=a%20b", url?.toString())
    }
}
