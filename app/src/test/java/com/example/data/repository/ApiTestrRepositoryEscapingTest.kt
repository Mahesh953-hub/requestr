package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master plan §1.7 — the old `req.bodyContent.replace("'", "\\'")` left a "Copy as cURL" export
 * one quote short of a valid POSIX single-quoted string whenever the body/URL/headers contained
 * an apostrophe, and left backtick/`$()`/double-quote header values shell-injectable outright.
 * These lock the corrected [ShellEscape.singleQuote]-based output, and the analogous
 * [SourceLiteralEscape]-based fix for the other `generateCodeSnippet` language branches.
 */
class ApiTestrRepositoryEscapingTest {

    @Test
    fun `ShellEscape singleQuote closes reopens and escapes an embedded apostrophe`() {
        assertEquals("'it'\\''s'", ShellEscape.singleQuote("it's"))
    }

    @Test
    fun `cURL export escapes an apostrophe in the body so it cannot break out of the quoting`() {
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            headers = emptyList(),
            bodyType = RequestBodyType.RAW,
            bodyContent = "O'Brien"
        )
        val curl = ApiTestrRepository.generateCodeSnippet(item, "cURL")
        assertTrue(curl.contains("-d 'O'\\''Brien'"))
    }

    @Test
    fun `cURL export escapes backtick dollar-paren and double-quote in a header value`() {
        val dangerous = "`whoami`\$(id)\"quoted\""
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = "https://example.com/api",
            headers = listOf(KeyValueItem(key = "X-Test", value = dangerous, isEnabled = true)),
            bodyType = RequestBodyType.NONE
        )
        val curl = ApiTestrRepository.generateCodeSnippet(item, "cURL")
        // The whole dangerous value must appear untouched but single-quote-wrapped -- none of
        // backtick/$()/double-quote are special *inside* a correctly single-quoted string, so
        // it must NOT have been escaped with backslashes (that would instead re-enable them).
        assertTrue(curl.contains("-H 'X-Test: $dangerous'"))
        assertFalse(curl.contains("\\`"))
    }

    @Test
    fun `Kotlin export escapes a dollar sign so it cannot start a string template`() {
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = "https://example.com/api",
            headers = listOf(KeyValueItem(key = "X-Price", value = "a\$b", isEnabled = true)),
            bodyType = RequestBodyType.NONE
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")
        assertTrue(kotlin.contains("a\\\$b"))
    }

    @Test
    fun `Python export escapes an embedded double quote`() {
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = "https://example.com/api",
            headers = listOf(KeyValueItem(key = "X-Quote", value = "a\"b", isEnabled = true)),
            bodyType = RequestBodyType.NONE
        )
        val python = ApiTestrRepository.generateCodeSnippet(item, "Python - Requests")
        assertTrue(python.contains("a\\\"b"))
    }

    @Test
    fun `JS fetch export escapes an embedded single quote in the URL`() {
        // Headers in the JS branch render as a double-quoted object literal (see the Python
        // test above for that style); only the fetch() URL argument is single-quoted, so that's
        // the one place an embedded `'` must be escaped. A URL toHttpUrlOrNull() can't parse
        // (no scheme) falls back to the raw, un-percent-encoded req.url string here, which is
        // the only way an unescaped `'` can reach jsQuoted at all.
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = "not-a-real-url-with-'-quote",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.NONE
        )
        val js = ApiTestrRepository.generateCodeSnippet(item, "JavaScript - fetch")
        assertTrue(js.contains("with-\\'-quote"))
    }
}
