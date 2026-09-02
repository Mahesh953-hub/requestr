package com.example.ui.screens

import com.example.data.models.KeyValueItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uses Robolectric for the `flattenJsonBodyToKeyValueLines` cases because they exercise real
 * `org.json` parsing, which the plain android.jar stub used on the unit-test classpath does not
 * implement (same rationale as [com.example.flow.JsonFieldExtractorTest] /
 * [com.example.data.export.PostmanCollectionCodecTest]).
 */
@RunWith(RobolectricTestRunner::class)
class ApiTestrBodyToolbarHelpersTest {

    // ---- parseRawTextToKeyValueItems ----

    @Test
    fun `enabled row parses key and value`() {
        val items = parseRawTextToKeyValueItems("API_Key: v4_auth_key")
        assertEquals(1, items.size)
        assertEquals("API_Key", items[0].key)
        assertEquals("v4_auth_key", items[0].value)
        assertEquals(true, items[0].isEnabled)
    }

    @Test
    fun `comment-prefixed row parses as disabled with the prefix stripped`() {
        val items = parseRawTextToKeyValueItems("// Version: 1.2.3")
        assertEquals(1, items.size)
        assertEquals("Version", items[0].key)
        assertEquals("1.2.3", items[0].value)
        assertEquals(false, items[0].isEnabled)
    }

    @Test
    fun `blank lines produce no row`() {
        val items = parseRawTextToKeyValueItems("A: 1\n\n\nB: 2")
        assertEquals(2, items.size)
        assertEquals("A", items[0].key)
        assertEquals("B", items[1].key)
    }

    @Test
    fun `a line with no colon is dropped, not kept with an empty value`() {
        val items = parseRawTextToKeyValueItems("A: 1\nthis-is-not-a-row\nB: 2")
        assertEquals(2, items.size)
        assertEquals(listOf("A", "B"), items.map { it.key })
    }

    @Test
    fun `duplicate keys are preserved as separate positional rows`() {
        val items = parseRawTextToKeyValueItems("A: 1\nA: 2")
        assertEquals(2, items.size)
        assertEquals("1", items[0].value)
        assertEquals("2", items[1].value)
    }

    @Test
    fun `keys and values are trimmed`() {
        val items = parseRawTextToKeyValueItems("  A   :   1  ")
        assertEquals("A", items[0].key)
        assertEquals("1", items[0].value)
    }

    @Test
    fun `value containing a colon keeps only splitting on the first colon`() {
        val items = parseRawTextToKeyValueItems("Endpoint: http://example.com:8080/x")
        assertEquals("Endpoint", items[0].key)
        assertEquals("http://example.com:8080/x", items[0].value)
    }

    // ---- keyValueItemsToRawText ----

    @Test
    fun `keyValueItemsToRawText round-trips an enabled and a disabled row`() {
        val items = listOf(
            KeyValueItem(key = "API_Key", value = "v4_auth_key", isEnabled = true),
            KeyValueItem(key = "Version", value = "1.2.3", isEnabled = false)
        )
        val raw = keyValueItemsToRawText(items)
        assertEquals("API_Key: v4_auth_key\n// Version: 1.2.3", raw)

        val reparsed = parseRawTextToKeyValueItems(raw)
        assertEquals(2, reparsed.size)
        assertEquals(true, reparsed[0].isEnabled)
        assertEquals(false, reparsed[1].isEnabled)
    }

    // ---- flattenJsonBodyToKeyValueLines ----

    @Test
    fun `flattens a flat JSON object into path=value lines`() {
        val out = flattenJsonBodyToKeyValueLines("""{"name":"Ada","age":30}""")
        assertEquals("name=Ada\nage=30", out)
    }

    @Test
    fun `flattens nested objects with dot notation`() {
        val out = flattenJsonBodyToKeyValueLines("""{"user":{"name":"Ada","role":"admin"}}""")
        assertEquals("user.name=Ada\nuser.role=admin", out)
    }

    @Test
    fun `flattens arrays with bracket-index notation`() {
        val out = flattenJsonBodyToKeyValueLines("""{"tags":["a","b"]}""")
        assertEquals("tags[0]=a\ntags[1]=b", out)
    }

    @Test
    fun `flattens arrays of objects combining bracket and dot notation`() {
        val out = flattenJsonBodyToKeyValueLines("""{"users":[{"name":"Ada"},{"name":"Grace"}]}""")
        assertEquals("users[0].name=Ada\nusers[1].name=Grace", out)
    }

    @Test
    fun `invalid JSON falls back to the original text unmodified`() {
        val notJson = "query { viewer { id } }"
        assertEquals(notJson, flattenJsonBodyToKeyValueLines(notJson))
    }

    @Test
    fun `blank input is returned unmodified`() {
        assertEquals("", flattenJsonBodyToKeyValueLines(""))
    }

    @Test
    fun `booleans and nulls are flattened using their literal string form`() {
        val out = flattenJsonBodyToKeyValueLines("""{"active":true,"deletedAt":null}""")
        assertEquals("active=true\ndeletedAt=null", out)
    }
}
