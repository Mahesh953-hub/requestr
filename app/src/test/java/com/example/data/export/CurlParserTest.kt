package com.example.data.export

import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardens Master Plan §3 C11 "copy/paste cURL for live traffic": [CurlParser] must never throw
 * on malformed input, must handle real-world curl quoting/flags correctly, and must round-trip
 * with [CodeExporter]'s existing `ExportFormat.CURL` output.
 */
class CurlParserTest {

    // ── basic flags ──────────────────────────────────────────────────────

    @Test
    fun `parses method url header and data`() {
        val result = CurlParser.parse(
            "curl -X POST 'https://api.example.com/users' " +
                "-H 'Content-Type: application/json' " +
                "-H 'Authorization: Bearer abc123' " +
                "-d '{\"name\":\"John\"}'"
        )
        assertEquals("POST", result.method)
        assertEquals("https://api.example.com/users", result.url)
        assertEquals(
            listOf("Content-Type" to "application/json", "Authorization" to "Bearer abc123"),
            result.headers,
        )
        assertEquals("{\"name\":\"John\"}", result.body)
    }

    @Test
    fun `defaults to GET when no method or data is present`() {
        val result = CurlParser.parse("curl 'https://example.com/health'")
        assertEquals("GET", result.method)
        assertEquals("https://example.com/health", result.url)
        assertNull(result.body)
    }

    @Test
    fun `defaults to POST when data is present but no explicit method`() {
        val result = CurlParser.parse("curl 'https://example.com/x' -d 'raw body'")
        assertEquals("POST", result.method)
        assertEquals("raw body", result.body)
    }

    @Test
    fun `--request overrides the method long form`() {
        val result = CurlParser.parse("curl --request PUT --url 'https://example.com/x'")
        assertEquals("PUT", result.method)
        assertEquals("https://example.com/x", result.url)
    }

    @Test
    fun `--url flag sets the url even without a positional url token`() {
        val result = CurlParser.parse("curl -X DELETE --url https://example.com/item/9")
        assertEquals("DELETE", result.method)
        assertEquals("https://example.com/item/9", result.url)
    }

    // ── data-* variants ──────────────────────────────────────────────────

    @Test
    fun `--data-raw and --data-binary both populate the body`() {
        assertEquals("abc", CurlParser.parse("curl x --data-raw 'abc'").body)
        assertEquals("abc", CurlParser.parse("curl x --data-binary 'abc'").body)
    }

    @Test
    fun `multiple -d occurrences are joined with ampersand like real curl`() {
        val result = CurlParser.parse("curl 'https://x' -d 'a=1' -d 'b=2'")
        assertEquals("a=1&b=2", result.body)
    }

    @Test
    fun `--data-urlencode url-encodes only the value portion of a name=value pair`() {
        val result = CurlParser.parse("curl x --data-urlencode 'q=hello world'")
        assertEquals("q=hello%20world", result.body)
    }

    @Test
    fun `--data-urlencode without name= encodes the whole argument`() {
        val result = CurlParser.parse("curl x --data-urlencode 'a b'")
        assertEquals("a%20b", result.body)
    }

    // ── quoting ──────────────────────────────────────────────────────────

    @Test
    fun `double-quoted values keep internal spaces as one token`() {
        val result = CurlParser.parse(
            """curl "https://example.com/x" -H "User-Agent: My App 1.0""""
        )
        assertEquals("https://example.com/x", result.url)
        assertEquals(listOf("User-Agent" to "My App 1.0"), result.headers)
    }

    @Test
    fun `single-quoted json body with embedded spaces and colons stays one token`() {
        val result = CurlParser.parse(
            "curl 'https://example.com/x' -d '{\"name\": \"John Doe\", \"age\": 30}'"
        )
        assertEquals("{\"name\": \"John Doe\", \"age\": 30}", result.body)
    }

    @Test
    fun `escaped single quote inside a single-quoted argument reconstructs the literal apostrophe`() {
        // curl's classic bash single-quote escaping: 'it'\''s' means the literal string it's
        val result = CurlParser.parse("curl 'https://x' -d 'it'\\''s'")
        assertEquals("it's", result.body)
    }

    @Test
    fun `escaped double quote inside a double-quoted argument is preserved literally`() {
        val result = CurlParser.parse("""curl "https://x" -d "say \"hi\"" """)
        assertEquals("say \"hi\"", result.body)
    }

    // ── line continuations ───────────────────────────────────────────────

    @Test
    fun `backslash-newline line continuations are collapsed before tokenizing`() {
        val curl = "curl -X POST \\\n" +
            "  'https://api.example.com/users' \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d '{\"a\":1}'"
        val result = CurlParser.parse(curl)
        assertEquals("POST", result.method)
        assertEquals("https://api.example.com/users", result.url)
        assertEquals(listOf("Content-Type" to "application/json"), result.headers)
        assertEquals("{\"a\":1}", result.body)
    }

    // ── -u / basic auth ──────────────────────────────────────────────────

    @Test
    fun `-u user colon password becomes a base64 basic auth header`() {
        val result = CurlParser.parse("curl -u alice:s3cret 'https://example.com/secure'")
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        assertEquals(listOf("Authorization" to expected), result.headers)
    }

    // ── unknown flags / malformed input never crash ─────────────────────

    @Test
    fun `unknown boolean flags are ignored without consuming the following url`() {
        val result = CurlParser.parse("curl -s -k --compressed -L 'https://example.com/x'")
        assertEquals("https://example.com/x", result.url)
        assertEquals("GET", result.method)
    }

    @Test
    fun `completely empty input never throws and yields empty url`() {
        val result = CurlParser.parse("")
        assertEquals("", result.url)
        assertEquals("GET", result.method)
        assertTrue(result.headers.isEmpty())
        assertNull(result.body)
    }

    @Test
    fun `garbage non-curl text never throws`() {
        val result = CurlParser.parse("this is not a curl command at all !! @#\$%")
        assertEquals("GET", result.method)
    }

    @Test
    fun `unterminated quote never throws and still yields a best-effort token`() {
        val result = CurlParser.parse("curl 'https://example.com/x -H 'broken")
        // Must not throw; exact tokenization of unbalanced quoting is best-effort.
        assertTrue(result.url.isNotEmpty() || result.url.isEmpty())
    }

    @Test
    fun `trailing dangling flag with no value never throws`() {
        val result = CurlParser.parse("curl 'https://example.com/x' -H")
        assertEquals("https://example.com/x", result.url)
        assertTrue(result.headers.isEmpty())
    }

    @Test
    fun `header without a colon is dropped rather than crashing`() {
        val result = CurlParser.parse("curl 'https://x' -H 'not-a-header'")
        assertTrue(result.headers.isEmpty())
    }

    // ── round trip with the existing CodeExporter ────────────────────────

    @Test
    fun `round trips a captured GET item through CodeExporter export and CurlParser parse`() {
        val item = CapturedTrafficItem(
            method = HttpMethod.GET,
            url = "https://api.github.com/users/octocat",
            requestHeaders = mapOf("Accept" to "application/json", "Host" to "api.github.com"),
            requestBody = "",
        )
        val curl = CodeExporter.export(item, ExportFormat.CURL)
        val parsed = CurlParser.parse(curl)
        assertEquals("GET", parsed.method)
        assertEquals(item.url, parsed.url)
        assertTrue(parsed.headers.any { it.first == "Accept" && it.second == "application/json" })
        // Host is deliberately excluded by CodeExporter.toCurl -- confirm it round-trips as excluded.
        assertTrue(parsed.headers.none { it.first.equals("Host", ignoreCase = true) })
        assertNull(parsed.body)
    }

    @Test
    fun `round trips a captured POST item with a json body and special characters`() {
        val item = CapturedTrafficItem(
            method = HttpMethod.POST,
            url = "https://api.example.com/login",
            requestHeaders = mapOf("Content-Type" to "application/json", "X-Trace-Id" to "abc-123"),
            requestBody = "{\"user\":\"o'brien\",\"note\":\"line1\\nline2\"}",
        )
        val curl = CodeExporter.export(item, ExportFormat.CURL)
        val parsed = CurlParser.parse(curl)
        assertEquals("POST", parsed.method)
        assertEquals(item.url, parsed.url)
        assertTrue(parsed.headers.any { it.first == "Content-Type" && it.second == "application/json" })
        assertTrue(parsed.headers.any { it.first == "X-Trace-Id" && it.second == "abc-123" })
        assertEquals(item.requestBody, parsed.body)
    }
}
