package com.example.flow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Master plan §2.11 — [JsonFieldExtractor.extract] gained real nested-path traversal (dotted and
 * `[n]`-indexed) on top of the pre-existing legacy single-key regex. Uses Robolectric because
 * this exercises real `org.json` parsing, which the plain android.jar stub used on the unit-test
 * classpath does not implement (see [com.example.data.export.PostmanCollectionCodecTest]'s
 * identical rationale).
 */
@RunWith(RobolectricTestRunner::class)
class JsonFieldExtractorTest {

    private val nestedJson = """
        {
          "data": {
            "items": [
              {"id": 1, "name": "Ada"},
              {"id": 2, "name": "Grace"}
            ],
            "token": "abc123",
            "count": 2,
            "active": true,
            "meta": null
          }
        }
    """.trimIndent()

    // ── Nested dotted / array-indexed paths ─────────────────────────────────────────────────

    @Test
    fun `extracts a dotted nested string field`() {
        assertEquals("abc123", JsonFieldExtractor.extract(nestedJson, "$.data.token"))
        assertEquals("abc123", JsonFieldExtractor.extract(nestedJson, "data.token"))
    }

    @Test
    fun `extracts an array element by bracket index`() {
        assertEquals("Ada", JsonFieldExtractor.extract(nestedJson, "$.data.items[0].name"))
        assertEquals("Grace", JsonFieldExtractor.extract(nestedJson, "$.data.items[1].name"))
    }

    @Test
    fun `extracts an array element by bare-dot index`() {
        assertEquals("Ada", JsonFieldExtractor.extract(nestedJson, "data.items.0.name"))
    }

    @Test
    fun `out-of-range array index resolves to null rather than throwing`() {
        assertNull(JsonFieldExtractor.extract(nestedJson, "$.data.items[9].name"))
    }

    @Test
    fun `nonexistent nested key resolves to null rather than throwing`() {
        assertNull(JsonFieldExtractor.extract(nestedJson, "$.data.missing.field"))
    }

    // ── Non-string leaf stringification ─────────────────────────────────────────────────────

    @Test
    fun `stringifies a numeric leaf`() {
        assertEquals("2", JsonFieldExtractor.extract(nestedJson, "$.data.count"))
    }

    @Test
    fun `stringifies a boolean leaf`() {
        assertEquals("true", JsonFieldExtractor.extract(nestedJson, "$.data.active"))
    }

    @Test
    fun `a null leaf extracts to null`() {
        assertNull(JsonFieldExtractor.extract(nestedJson, "$.data.meta"))
    }

    @Test
    fun `an object leaf stringifies via toString rather than throwing`() {
        // "$.data" alone is a single flat segment (no dot/bracket in the cleaned key), so it
        // would never reach extractNested -- use a path that is genuinely multi-segment but
        // still resolves to an object leaf.
        val result = JsonFieldExtractor.extract(nestedJson, "$.data.items[0]")
        assertNotNull(result)
        assertTrue(result!!.contains("Ada"))
    }

    @Test
    fun `an array leaf stringifies via toString rather than throwing`() {
        val result = JsonFieldExtractor.extract(nestedJson, "$.data.items")
        assertNotNull(result)
        assertTrue(result!!.contains("Ada"))
    }

    // ── Legacy flat-key behavior preserved ──────────────────────────────────────────────────

    @Test
    fun `a single flat key still uses the legacy regex extractor, unchanged`() {
        val flatJson = """{"token": "xyz789", "count": 5}"""
        assertEquals("xyz789", JsonFieldExtractor.extract(flatJson, "token"))
        assertEquals("xyz789", JsonFieldExtractor.extract(flatJson, "$.token"))
        // Pre-existing legacy regex quirk (unrelated to §2.11, preserved on purpose): the
        // second fallback regex is `"count"\s*:\s*(\S+)`, which is greedy up to the next
        // whitespace -- when the number is immediately followed by the closing brace with no
        // space, the brace is captured too. This is the same behavior the old inline
        // implementation had before this extractor was pulled out.
        assertEquals("5}", JsonFieldExtractor.extract(flatJson, "count"))
    }

    @Test
    fun `extractByKey called directly still behaves exactly as before`() {
        val flatJson = """{"token": "xyz789"}"""
        assertEquals("xyz789", JsonFieldExtractor.extractByKey(flatJson, "token"))
        assertNull(JsonFieldExtractor.extractByKey(flatJson, "missing"))
    }

    // ── Malformed-JSON fallback ──────────────────────────────────────────────────────────────

    @Test
    fun `a nested path against malformed JSON falls back to extractByKey rather than throwing`() {
        // Not valid JSON (unbalanced braces) -- extractNested's JSONTokener parse fails, so
        // extract() must fall back to extractByKey(json, cleanKey) exactly like the pre-§2.11
        // behavior, rather than throwing or silently swallowing the failure into an unrelated
        // result. extractByKey looks up the *whole* cleaned multi-segment key ("data.token")
        // as a single literal regex match, so it legitimately returns null here (there is no
        // literal "data.token" key) -- the point of this test is that extract() agrees with
        // extractByKey's own answer instead of throwing.
        val malformed = """{"data": {"token": "still-found" """
        val expected = JsonFieldExtractor.extractByKey(malformed, "data.token")
        assertEquals(expected, JsonFieldExtractor.extract(malformed, "$.data.token"))
    }

    @Test
    fun `a flat key against malformed JSON still resolves via the regex`() {
        val malformed = """{"token": "found-anyway" extra garbage"""
        assertEquals("found-anyway", JsonFieldExtractor.extract(malformed, "token"))
    }

    // ── cleanPath ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cleanPath strips leading dollar-dot and surrounding quotes`() {
        assertEquals("data.token", JsonFieldExtractor.cleanPath("$.data.token"))
        assertEquals("token", JsonFieldExtractor.cleanPath(".token"))
        assertEquals("token", JsonFieldExtractor.cleanPath("\"token\""))
    }

    @Test
    fun `an empty path resolves to null`() {
        assertNull(JsonFieldExtractor.extract(nestedJson, "$."))
        assertNull(JsonFieldExtractor.extract(nestedJson, ""))
    }
}
