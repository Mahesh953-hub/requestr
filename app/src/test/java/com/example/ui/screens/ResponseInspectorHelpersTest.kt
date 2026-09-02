package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure response-inspection helpers shared by ApiTestrScreen: cookie parsing,
 * hex-dump generation, and syntax/search highlighting. No Android framework calls.
 */
class ResponseInspectorHelpersTest {

    // ─── parseCookiesFromHeaders ─────────────────────────────────────────

    @Test
    fun `parseCookiesFromHeaders returns empty list when no set-cookie header`() {
        val headers = mapOf("content-type" to "application/json", "server" to "nginx")
        assertEquals(0, parseCookiesFromHeaders(headers).size)
    }

    @Test
    fun `parseCookiesFromHeaders parses single cookie with name and value`() {
        val headers = mapOf("set-cookie" to "session=abc123; Path=/; Secure")
        val cookies = parseCookiesFromHeaders(headers)
        assertEquals(1, cookies.size)
        assertEquals("session", cookies[0].name)
        assertEquals("abc123", cookies[0].value)
        assertEquals("/", cookies[0].path)
        assertTrue(cookies[0].secure)
    }

    @Test
    fun `parseCookiesFromHeaders parses domain httponly and samesite attributes`() {
        val headers = mapOf(
            "set-cookie" to "token=xyz; Domain=example.com; Path=/api; HttpOnly; SameSite=Strict"
        )
        val cookies = parseCookiesFromHeaders(headers)
        assertEquals(1, cookies.size)
        assertEquals("token", cookies[0].name)
        assertEquals("xyz", cookies[0].value)
        assertEquals("example.com", cookies[0].domain)
        assertEquals("/api", cookies[0].path)
        assertTrue(cookies[0].httpOnly)
        assertEquals("Strict", cookies[0].sameSite)
        assertFalse(cookies[0].secure)
    }

    @Test
    fun `parseCookiesFromHeaders parses max-age when expires is absent`() {
        val headers = mapOf("set-cookie" to "cache=val; Max-Age=3600")
        val cookies = parseCookiesFromHeaders(headers)
        assertEquals(1, cookies.size)
        assertEquals("Max-Age: 3600 s", cookies[0].expires)
    }

    @Test
    fun `parseCookiesFromHeaders handles case-insensitive header key`() {
        val headers = mapOf("Set-Cookie" to "foo=bar")
        val cookies = parseCookiesFromHeaders(headers)
        assertEquals(1, cookies.size)
        assertEquals("foo", cookies[0].name)
        assertEquals("bar", cookies[0].value)
    }

    @Test
    fun `parseCookiesFromHeaders splits comma-separated cookies without spaces`() {
        val headers = mapOf(
            "set-cookie" to "a=1; Path=/,b=2; Path=/api"
        )
        val cookies = parseCookiesFromHeaders(headers)
        assertEquals(2, cookies.size)
        assertEquals("a", cookies[0].name)
        assertEquals("1", cookies[0].value)
        assertEquals("/", cookies[0].path)
        assertEquals("b", cookies[1].name)
        assertEquals("2", cookies[1].value)
        assertEquals("/api", cookies[1].path)
    }


    @Test
    fun `parseCookiesFromHeaderValues preserves separate lines and Expires comma`() {
        val cookies = parseCookiesFromHeaderValues(
            listOf(
                "session=abc; Path=/; HttpOnly",
                "prefs=dark; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Secure",
            )
        )

        assertEquals(2, cookies.size)
        assertEquals("session", cookies[0].name)
        assertTrue(cookies[0].httpOnly)
        assertEquals("prefs", cookies[1].name)
        assertEquals("Wed, 21 Oct 2030 07:28:00 GMT", cookies[1].expires)
        assertTrue(cookies[1].secure)
    }

    // ─── generateHexRows ─────────────────────────────────────────────────

    @Test
    fun `generateHexRows produces correct offset and ascii for short input`() {
        val bytes = "AB".toByteArray()
        val rows = generateHexRows(bytes)
        assertEquals(1, rows.size)
        assertEquals("0000", rows[0].offset)
        assertTrue(rows[0].hexString.contains("41"))
        assertTrue(rows[0].hexString.contains("42"))
        assertEquals("AB", rows[0].asciiString)
    }

    @Test
    fun `generateHexRows replaces non-printable bytes with dots in ascii`() {
        val bytes = byteArrayOf(0x00, 0x41, 0x1F, 0x42)
        val rows = generateHexRows(bytes)
        assertEquals(1, rows.size)
        assertEquals(".A.B", rows[0].asciiString)
    }

    @Test
    fun `generateHexRows truncates to maxBytes`() {
        val bytes = ByteArray(40) { it.toByte() }
        val rows = generateHexRows(bytes, maxBytes = 16)
        assertEquals(1, rows.size)
        assertEquals("0000", rows[0].offset)
    }

    @Test
    fun `generateHexRows splits across 16-byte rows`() {
        val bytes = ByteArray(20) { 0x41 } // 'A' repeated
        val rows = generateHexRows(bytes)
        assertEquals(2, rows.size)
        assertEquals("0000", rows[0].offset)
        assertEquals("0010", rows[1].offset)
    }

    // ─── UniversalSyntaxHighlighter ──────────────────────────────────────

    @Test
    fun `highlight detects JSON from content-type`() {
        val lines = UniversalSyntaxHighlighter.highlight(
            text = "{\"key\": \"value\"}",
            contentType = "application/json"
        )
        assertEquals(1, lines.size)
        // The annotated string should contain the key and value text
        assertEquals("{\"key\": \"value\"}", lines[0].text)
    }

    @Test
    fun `highlight detects JSON from leading brace`() {
        val lines = UniversalSyntaxHighlighter.highlight(
            text = "[1, 2, 3]",
            contentType = "text/plain"
        )
        assertEquals(1, lines.size)
        assertEquals("[1, 2, 3]", lines[0].text)
    }

    @Test
    fun `highlight detects HTML from content-type`() {
        val lines = UniversalSyntaxHighlighter.highlight(
            text = "<!doctype html>\n<html><body>Hello</body></html>",
            contentType = "text/html"
        )
        assertEquals(2, lines.size)
        assertEquals("<!doctype html>", lines[0].text)
        assertEquals("<html><body>Hello</body></html>", lines[1].text)
    }

    @Test
    fun `highlight falls through to generic for unknown content type`() {
        val lines = UniversalSyntaxHighlighter.highlight(
            text = "plain text line\nsecond line",
            contentType = "text/plain"
        )
        assertEquals(2, lines.size)
        assertEquals("plain text line", lines[0].text)
        assertEquals("second line", lines[1].text)
    }

    @Test
    fun `highlight splits text into per-line annotated strings`() {
        val text = "line1\nline2\nline3"
        val lines = UniversalSyntaxHighlighter.highlight(text, "text/plain")
        assertEquals(3, lines.size)
        assertEquals("line1", lines[0].text)
        assertEquals("line2", lines[1].text)
        assertEquals("line3", lines[2].text)
    }

    @Test
    fun `highlight with search query preserves full text`() {
        val text = "hello world hello"
        val lines = UniversalSyntaxHighlighter.highlight(text, "text/plain", "hello")
        assertEquals(1, lines.size)
        assertEquals("hello world hello", lines[0].text)
    }
}
