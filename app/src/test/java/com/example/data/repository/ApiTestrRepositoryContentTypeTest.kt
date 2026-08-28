package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master plan §2.10 — [generateCodeSnippet] now auto-adds a `Content-Type` header matching
 * [BodyContentType.mediaTypeFor] when the saved request has none, and never overwrites one the
 * user already set. Exercised through the cURL branch since its `-H` rendering makes the final
 * header list easy to assert against directly.
 */
class ApiTestrRepositoryContentTypeTest {

    private fun curlFor(bodyType: RequestBodyType, headers: List<KeyValueItem> = emptyList()): String =
        ApiTestrRepository.generateCodeSnippet(
            ApiRequestItem(
                method = HttpMethod.POST,
                url = "https://example.com/api",
                queryParams = emptyList(),
                headers = headers,
                bodyType = bodyType,
                bodyContent = "payload"
            ),
            "cURL"
        )

    @Test
    fun `JSON body auto-adds application-json content type`() {
        assertTrue(curlFor(RequestBodyType.JSON).contains("-H 'Content-Type: application/json'"))
    }

    @Test
    fun `RAW body auto-adds text-plain content type`() {
        assertTrue(curlFor(RequestBodyType.RAW).contains("-H 'Content-Type: text/plain'"))
    }

    @Test
    fun `GRAPHQL body auto-adds application-graphql content type`() {
        assertTrue(curlFor(RequestBodyType.GRAPHQL).contains("-H 'Content-Type: application/graphql'"))
    }

    @Test
    fun `FORM body auto-adds x-www-form-urlencoded content type`() {
        assertTrue(curlFor(RequestBodyType.FORM).contains("-H 'Content-Type: application/x-www-form-urlencoded'"))
    }

    @Test
    fun `BodyContentType mediaTypeFor still maps MULTIPART to multipart-form-data even though no branch renders it as a header anymore`() {
        // Code-review fix (§2.9 follow-up, cURL, then follow-up-of-follow-up covering
        // Python - Requests / JavaScript - fetch / Kotlin - OkHttp / Go) -- every branch that
        // builds a real formData-driven body now computes its own real, boundary-bearing
        // Content-Type for MULTIPART via its own language mechanism instead, and drops the
        // shared, boundary-less auto one this file would otherwise show (see
        // ApiTestrRepositoryGeneratorFormBodyTest for a per-language assertion of that). The one
        // deliberately-unfixed branch, "Python - httpx", never renders ANY header at all (not
        // just Content-Type -- see its implementation), so there's no branch left to exercise
        // this header's raw pass-through against. [BodyContentType.mediaTypeFor] itself is
        // unchanged, which this asserts directly.
        assertEquals("multipart/form-data", BodyContentType.mediaTypeFor(RequestBodyType.MULTIPART))
    }

    @Test
    fun `MULTIPART body never gets a hand-written Content-Type header in the cURL export`() {
        assertTrue(!curlFor(RequestBodyType.MULTIPART).contains("Content-Type"))
    }

    @Test
    fun `NONE body never gets an auto content type`() {
        assertTrue(!curlFor(RequestBodyType.NONE).contains("Content-Type"))
    }

    @Test
    fun `an explicit user-set content type header is kept and not duplicated`() {
        val curl = curlFor(
            RequestBodyType.JSON,
            headers = listOf(KeyValueItem(key = "Content-Type", value = "application/vnd.custom+json", isEnabled = true))
        )
        assertTrue(curl.contains("-H 'Content-Type: application/vnd.custom+json'"))
        assertEquals(1, Regex("Content-Type").findAll(curl).count())
        assertTrue(!curl.contains("application/json'"))
    }

    @Test
    fun `an explicit content type header is matched case-insensitively`() {
        val curl = curlFor(
            RequestBodyType.JSON,
            headers = listOf(KeyValueItem(key = "content-type", value = "application/xml", isEnabled = true))
        )
        assertTrue(curl.contains("-H 'content-type: application/xml'"))
        assertEquals(1, Regex("(?i)content-type").findAll(curl).count())
    }
}
