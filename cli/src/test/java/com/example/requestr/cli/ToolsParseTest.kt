package com.example.requestr.cli

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CAT-1 regression, `:cli` side — first test in this module.
 *
 * [RequestrCli.cmdTools] parses `GET /api/tools`'s response with exactly one call:
 * `org.json.JSONArray(response)`, then reads each element with `optString("id"/"title"/
 * "category")`. This test drives that identical call+read pattern against two response bodies:
 *
 *  - the **corrected** shape [LocalApiServer.listTools] now produces (built on
 *    `org.json.JSONObject`/`JSONArray`, matching the app-side
 *    `LocalApiServerToolsEncodingTest`) — proving `cmdTools`'s parse succeeds even when a tool
 *    title contains a multibyte character or a custom tool's description contains a raw
 *    backslash (a `\d`-style regex pattern).
 *  - the **pre-fix, hand-rolled** shape (`.replace("\"","\\\"")`-escaped string interpolation)
 *    for the same backslash-bearing description — proving that shape is what actually broke
 *    `requestr tools` (an illegal `\d` JSON escape), so the fix is the real root cause and not
 *    coincidental.
 *
 * Per the task's non-goal, [RequestrCli]'s parsing logic itself is untouched -- `cmdTools`
 * already calls the correct, standard `org.json.JSONArray` API; the defect was entirely in what
 * the server sent it.
 */
class ToolsParseTest {

    private data class ParsedTool(val id: String, val title: String, val category: String)

    /** Exactly the read pattern `RequestrCli.cmdTools` uses on each array element. */
    private fun parseToolsResponse(response: String): List<ParsedTool> {
        val items = JSONArray(response)
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            ParsedTool(
                id = item.optString("id"),
                title = item.optString("title"),
                category = item.optString("category"),
            )
        }
    }

    /** Mirrors [LocalApiServer.listTools]'s post-fix body construction (org.json throughout). */
    private fun correctedToolsBody(vararg tools: Triple<String, String, String>): String {
        val arr = JSONArray()
        for ((id, title, description) in tools) {
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("title", title)
                    .put("description", description)
                    .put("category", "DECODERS")
            )
        }
        return arr.toString()
    }

    /** Mirrors [LocalApiServer.listTools]'s pre-fix body construction: raw string interpolation
     *  that only escapes double quotes, never backslashes. */
    private fun handRolledPreFixToolsBody(id: String, title: String, description: String): String {
        val escapedTitle = title.replace("\"", "\\\"")
        val escapedDescription = description.replace("\"", "\\\"")
        return """[{"id":"$id","title":"$escapedTitle","description":"$escapedDescription","category":"CUSTOM"}]"""
    }

    @Test
    fun `corrected org-json body with a multibyte title parses via the exact call cmdTools uses`() {
        val body = correctedToolsBody(
            Triple("hex-converter", "Hex ↔ String Converter", "Convert ASCII/UTF-8 strings to Hex bytes"),
        )

        val parsed = parseToolsResponse(body)

        assertEquals(1, parsed.size)
        assertEquals("hex-converter", parsed[0].id)
        assertEquals("Hex ↔ String Converter", parsed[0].title)
        assertEquals("DECODERS", parsed[0].category)
    }

    @Test
    fun `corrected org-json body with a backslash-bearing custom tool description parses cleanly`() {
        val body = correctedToolsBody(
            Triple("custom-strip-digits", "Strip Digits", "Custom regex: \\d+ → #"),
        )

        val parsed = parseToolsResponse(body)

        assertEquals(1, parsed.size)
        assertEquals("custom-strip-digits", parsed[0].id)
        // Round-trips through org.json's escaping (`\\d` on the wire) back to the literal `\d`.
        val fullDescription = JSONArray(body).getJSONObject(0).getString("description")
        assertEquals("Custom regex: \\d+ → #", fullDescription)
    }

    @Test
    fun `pre-fix hand-rolled body with the same backslash description is not valid JSON`() {
        val brokenBody = handRolledPreFixToolsBody(
            id = "custom-strip-digits",
            title = "Strip Digits",
            description = "Custom regex: \\d+ → #",
        )

        // This is the exact defect that broke `requestr tools`: a raw backslash from a custom
        // tool's regex pattern reaches the wire unescaped, which is not a legal JSON escape
        // sequence (RFC 8259 §7 only allows \" \\ \/ \b \f \n \r \t \uXXXX), so the same
        // `org.json.JSONArray(response)` call `cmdTools` uses throws instead of returning tools.
        val ex = assertFailsWith<JSONException> { parseToolsResponse(brokenBody) }
        assertTrue(ex.message.orEmpty().contains("scape", ignoreCase = true))
    }

    @Test
    fun `multiple tools including one with a multibyte title all parse in order, mirroring cmdTools output`() {
        val body = correctedToolsBody(
            Triple("jwt-decoder", "JWT Token Decoder", "Decode Header, Payload claims and inspect signatures"),
            Triple("hex-converter", "Hex ↔ String Converter", "Convert ASCII/UTF-8 strings to Hex bytes"),
        )

        val parsed = parseToolsResponse(body)
        val rendered = parsed.map { "${it.title}  [${it.category}]  # ${it.id}" }

        assertEquals(
            listOf(
                "JWT Token Decoder  [DECODERS]  # jwt-decoder",
                "Hex ↔ String Converter  [DECODERS]  # hex-converter",
            ),
            rendered,
        )
    }
}
