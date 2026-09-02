package com.example.data.repository

import com.example.data.models.HttpMethod
import com.example.data.models.RequestBodyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApiTestrRepository.parseCurl] maps [com.example.data.export.CurlParser]'s tokenizer output onto
 * API Testr's request model. The tokenizer itself is covered by
 * [com.example.data.export.CurlParserTest]; these tests cover the mapping decisions and the
 * refusals -- the cases where the old hand-rolled `split("\\s+")` importer silently produced a
 * request the user never pasted (a header value truncated at its first space, a flag value used as
 * the URL, `-X TRACE` downgraded to GET, a form body labelled JSON).
 */
class ApiTestrRepositoryParseCurlTest {

    private fun success(command: String): com.example.data.models.ApiRequestItem {
        val result = ApiTestrRepository.parseCurl(command)
        assertTrue("expected success, got $result", result is CurlImportResult.Success)
        return (result as CurlImportResult.Success).request
    }

    private fun failureMessage(command: String): String {
        val result = ApiTestrRepository.parseCurl(command)
        assertTrue("expected failure, got $result", result is CurlImportResult.Failure)
        return (result as CurlImportResult.Failure).message
    }

    // ── quoting: what the old whitespace tokenizer got wrong ────────────────────────────────

    @Test
    fun `a single-quoted header value containing spaces survives intact`() {
        val request = success("curl 'https://example.com/x' -H 'User-Agent: My App 1.0'")
        assertEquals(listOf("User-Agent" to "My App 1.0"), request.headers.map { it.key to it.value })
    }

    @Test
    fun `a double-quoted header value containing spaces survives intact`() {
        val request = success("curl \"https://example.com/x\" -H \"X-Note: hello there world\"")
        assertEquals(listOf("X-Note" to "hello there world"), request.headers.map { it.key to it.value })
    }

    @Test
    fun `a quoted json body keeps its spaces and is labelled JSON from its content type`() {
        val request = success(
            "curl -X POST 'https://example.com/x' " +
                "-H 'Content-Type: application/json' " +
                "-d '{\"a\": \"b c\", \"n\": 1}'"
        )
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("{\"a\": \"b c\", \"n\": 1}", request.bodyContent)
        assertEquals(RequestBodyType.JSON, request.bodyType)
    }

    // ── URL validation ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a flag value is not imported as the url`() {
        val request = success("curl --proxy localhost:8080 'https://api.example.com/v1/items'")
        assertEquals("https://api.example.com/v1/items", request.url)
    }

    @Test
    fun `a command with no url is refused rather than defaulting to some other host`() {
        val message = failureMessage("curl -X POST -H 'X-A: 1'")
        assertTrue(message, message.contains("URL"))
    }

    @Test
    fun `a relative or scheme-less url is refused`() {
        val message = failureMessage("curl example.com/x")
        assertTrue(message, message.contains("absolute"))
    }

    @Test
    fun `an unsupported scheme is refused`() {
        val message = failureMessage("curl 'ftp://files.example.com/x'")
        assertTrue(message, message.contains("absolute"))
    }

    // ── method ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unsupported method is reported instead of being downgraded to GET`() {
        val message = failureMessage("curl -X TRACE 'https://example.com/x'")
        assertTrue(message, message.contains("TRACE"))
    }

    @Test
    fun `a supported method is carried through`() {
        assertEquals(HttpMethod.PATCH, success("curl -X PATCH 'https://example.com/x'").method)
        assertEquals(HttpMethod.GET, success("curl 'https://example.com/x'").method)
    }

    @Test
    fun `a glued method flag imports as that method, not as a GET`() {
        assertEquals(HttpMethod.POST, success("curl -XPOST 'https://example.com/x'").method)
    }

    @Test
    fun `an unrecognised short flag is refused by name`() {
        val message = failureMessage("curl -W foo 'https://example.com/x'")
        assertTrue(message, message.contains("-W"))
    }

    @Test
    fun `a -G command imports as a GET with the data in the query and no body`() {
        val request = success("curl -G -d 'q=kotlin' 'https://example.com/search?lang=en'")
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("https://example.com/search?lang=en&q=kotlin", request.url)
        assertEquals(RequestBodyType.NONE, request.bodyType)
        assertEquals("", request.bodyContent)
    }

    // ── body type inference ─────────────────────────────────────────────────────────────────

    @Test
    fun `a form-encoded body is labelled FORM, not JSON`() {
        val request = success(
            "curl -X POST 'https://example.com/login' " +
                "-H 'Content-Type: application/x-www-form-urlencoded' " +
                "-d 'user=jo&pass=secret sauce'"
        )
        assertEquals(RequestBodyType.FORM, request.bodyType)
        assertEquals("user=jo&pass=secret sauce", request.bodyContent)
    }

    @Test
    fun `a body with no content type falls back to RAW rather than claiming JSON`() {
        val request = success("curl -X POST 'https://example.com/x' -d 'just some text'")
        assertEquals(RequestBodyType.RAW, request.bodyType)
    }

    @Test
    fun `a body with an unrecognized content type falls back to RAW`() {
        val request = success(
            "curl -X POST 'https://example.com/x' -H 'Content-Type: text/plain' -d 'hello'"
        )
        assertEquals(RequestBodyType.RAW, request.bodyType)
    }

    @Test
    fun `no body means NONE and an empty body string`() {
        val request = success("curl 'https://example.com/x'")
        assertEquals(RequestBodyType.NONE, request.bodyType)
        assertEquals("", request.bodyContent)
    }
}
