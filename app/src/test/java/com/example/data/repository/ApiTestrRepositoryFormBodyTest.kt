package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
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
 * Master plan §2.9 — [ApiTestrRepository.executeRequest] (the internal test-seam overload, see
 * its own doc comment) used to send [RequestBodyType.FORM] / [RequestBodyType.MULTIPART] as the
 * raw `bodyContent` string with no real encoding at all, completely ignoring
 * [ApiRequestItem.formData]. These drive a real request at a local [MockWebServer] and inspect
 * exactly what hit the wire, proving the real `FormBody`/`MultipartBody` construction.
 */
class ApiTestrRepositoryFormBodyTest {

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
    fun `FORM body url-encodes only enabled fields as application-x-www-form-urlencoded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = server.url("/").toString(),
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.FORM,
            formData = listOf(
                KeyValueItem(key = "a", value = "1", isEnabled = true),
                KeyValueItem(key = "b", value = "two words", isEnabled = true),
                KeyValueItem(key = "c", value = "disabled", isEnabled = false)
            )
        )

        ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        val recorded = server.takeRequest()
        assertEquals("application/x-www-form-urlencoded", recorded.headers["Content-Type"])
        assertEquals("a=1&b=two%20words", recorded.body.readUtf8())
    }

    @Test
    fun `MULTIPART body sends only enabled parts with multipart-form-data boundary`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = server.url("/").toString(),
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.MULTIPART,
            formData = listOf(
                KeyValueItem(key = "field1", value = "value1", isEnabled = true),
                KeyValueItem(key = "field2", value = "value2", isEnabled = false)
            )
        )

        ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        val recorded = server.takeRequest()
        val contentType = recorded.headers["Content-Type"] ?: ""
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"field1\""))
        assertTrue(body.contains("value1"))
        assertFalse(body.contains("field2"))
    }

    @Test
    fun `MULTIPART body with zero enabled parts falls back to an empty typed body instead of crashing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = server.url("/").toString(),
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.MULTIPART,
            formData = listOf(KeyValueItem(key = "", value = "", isEnabled = true))
        )

        ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        val recorded = server.takeRequest()
        // toRequestBody() appends "; charset=utf-8" itself when the media type has none, same
        // as any other empty-body case in this file -- not something buildMultipartBody adds.
        assertEquals("multipart/form-data; charset=utf-8", recorded.headers["Content-Type"])
        assertEquals("", recorded.body.readUtf8())
    }
}
