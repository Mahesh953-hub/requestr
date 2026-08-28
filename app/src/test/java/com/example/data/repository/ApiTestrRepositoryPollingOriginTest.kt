package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.AuthType
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 async-polling credential-leak guard: unit coverage for the two pure pieces of the fix in
 * [ApiTestrRepository.executeRequestWithScripts] — [ApiTestrRepository.isSameOrigin] (the
 * same-origin test) and [ApiTestrRepository.buildPollRequest] (the credential-strip when a
 * cross-origin poll Location is explicitly allowed). Exercised as pure helpers so the
 * scheme/host/port comparison and the strip-set are checked without a network fixture.
 */
class ApiTestrRepositoryPollingOriginTest {

    // ── isSameOrigin ───────────────────────────────────────────────────

    @Test
    fun `same host and port is same origin`() {
        assertTrue(ApiTestrRepository.isSameOrigin("https://api.example.com/a", "https://api.example.com/b/c"))
    }

    @Test
    fun `different host is cross origin`() {
        assertFalse(ApiTestrRepository.isSameOrigin("https://api.example.com/a", "https://other.example.com/a"))
    }

    @Test
    fun `different port is cross origin`() {
        assertFalse(ApiTestrRepository.isSameOrigin("https://api.example.com:8443/a", "https://api.example.com:443/a"))
    }

    @Test
    fun `different scheme is cross origin`() {
        assertFalse(ApiTestrRepository.isSameOrigin("https://api.example.com/a", "http://api.example.com/a"))
    }

    @Test
    fun `implicit default port matches an explicit default port`() {
        assertTrue(ApiTestrRepository.isSameOrigin("https://api.example.com/a", "https://api.example.com:443/a"))
        assertTrue(ApiTestrRepository.isSameOrigin("http://api.example.com/a", "http://api.example.com:80/a"))
    }

    @Test
    fun `host comparison is case insensitive`() {
        assertTrue(ApiTestrRepository.isSameOrigin("https://API.Example.com/a", "https://api.example.com/a"))
    }

    @Test
    fun `unparseable url never matches`() {
        assertFalse(ApiTestrRepository.isSameOrigin("not a url", "https://api.example.com/a"))
        assertFalse(ApiTestrRepository.isSameOrigin("https://valid.example.com/a", "://bad"))
        assertFalse(ApiTestrRepository.isSameOrigin("", ""))
    }

    // ── buildPollRequest: same-origin preserves credentials ────────────

    @Test
    fun `same-origin poll keeps credentials intact`() {
        val source = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://api.example.com/jobs",
            headers = listOf(KeyValueItem(key = "Authorization", value = "Bearer tok", isEnabled = true)),
            authType = AuthType.BEARER,
            bearerToken = "tok",
            bodyType = RequestBodyType.JSON,
            bodyContent = "{\"x\":1}",
            queryParams = listOf(KeyValueItem(key = "api_key", value = "secret", isEnabled = true)),
        )

        val poll = ApiTestrRepository.buildPollRequest(source, "https://api.example.com/jobs/123", stripCredentials = false)

        assertEquals("https://api.example.com/jobs/123", poll.url)
        assertEquals(HttpMethod.GET, poll.method)
        assertEquals(RequestBodyType.NONE, poll.bodyType)
        assertEquals(AuthType.BEARER, poll.authType)
        assertEquals("tok", poll.bearerToken)
        assertEquals(1, poll.headers.size)
        assertTrue(poll.headers.first().isEnabled)
    }

    // ── buildPollRequest: cross-origin strip ───────────────────────────

    @Test
    fun `cross-origin poll disables credential headers and blanks auth and query state`() {
        val source = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://origin.example/jobs",
            headers = listOf(
                KeyValueItem(key = "Authorization", value = "Bearer tok", isEnabled = true),
                KeyValueItem(key = "Cookie", value = "session=abc", isEnabled = true),
                KeyValueItem(key = "Proxy-Authorization", value = "Basic dTpw", isEnabled = true),
                KeyValueItem(key = "X-API-Key", value = "k-secret", isEnabled = true),
                KeyValueItem(key = "Accept", value = "application/json", isEnabled = true),
            ),
            authType = AuthType.API_KEY,
            apiKeyHeader = "X-API-Key",
            apiKeyValue = "k-secret",
            bodyType = RequestBodyType.JSON,
            bodyContent = "{\"x\":1}",
            formData = listOf(KeyValueItem(key = "f", value = "v", isEnabled = true)),
            queryParams = listOf(KeyValueItem(key = "api_key", value = "secret", isEnabled = true)),
            mockEnabled = true,
        )

        val poll = ApiTestrRepository.buildPollRequest(source, "https://signed-host.example/result", stripCredentials = true)

        assertEquals("https://signed-host.example/result", poll.url)
        assertEquals(HttpMethod.GET, poll.method)
        assertEquals(RequestBodyType.NONE, poll.bodyType)
        assertEquals(AuthType.NONE, poll.authType)
        assertEquals("", poll.bearerToken)
        assertEquals("", poll.apiKeyValue)
        assertEquals("", poll.bodyContent)
        assertTrue(poll.formData.isEmpty())
        assertTrue(poll.queryParams.isEmpty())
        assertFalse(poll.mockEnabled)

        fun enabled(key: String) = poll.headers.first { it.key == key }.isEnabled
        assertFalse("Authorization must be disabled", enabled("Authorization"))
        assertFalse("Cookie must be disabled", enabled("Cookie"))
        assertFalse("Proxy-Authorization must be disabled", enabled("Proxy-Authorization"))
        assertFalse("configured API-key header must be disabled", enabled("X-API-Key"))
        assertTrue("non-credential header must survive", enabled("Accept"))
    }

    @Test
    fun `cross-origin strip is case-insensitive on header names`() {
        val source = ApiRequestItem(
            url = "https://origin.example/jobs",
            headers = listOf(
                KeyValueItem(key = "authorization", value = "Bearer tok", isEnabled = true),
                KeyValueItem(key = "X-API-Key", value = "k-secret", isEnabled = true),
            ),
            authType = AuthType.API_KEY,
            apiKeyHeader = "x-api-key",
            apiKeyValue = "k-secret",
        )

        val poll = ApiTestrRepository.buildPollRequest(source, "https://signed-host.example/result", stripCredentials = true)

        assertFalse(poll.headers.first { it.key == "authorization" }.isEnabled)
        assertFalse(poll.headers.first { it.key == "X-API-Key" }.isEnabled)
    }
}
