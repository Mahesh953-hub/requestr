package com.example.data.repository

import org.junit.Assert.*
import org.junit.Test

/**
 * Task 7 (plan E3) — tests for the structured `addCustomTool` signature and its round-trip
 * through `executeToolFunction` / `catalog`, replacing the old design that reverse-parsed a
 * human-readable description string to recover pattern/replacement.
 */
class ToolsRepositoryCustomToolTest {

    @Test
    fun `structured regex-replace custom tool applies the replacement`() {
        ToolsRepository.addCustomTool(
            name = "Strip Digits Test A",
            operation = "regex-replace",
            pattern = "[0-9]+",
            replacement = "#"
        )
        val result = ToolsRepository.executeToolFunction("custom-strip-digits-test-a", "abc123def456")
        assertEquals("abc#def#", result)
    }

    @Test
    fun `structured template custom tool substitutes input`() {
        ToolsRepository.addCustomTool(
            name = "Wrap Test B",
            operation = "template",
            pattern = "Result: {input}",
            replacement = ""
        )
        val result = ToolsRepository.executeToolFunction("custom-wrap-test-b", "hello")
        assertEquals("Result: hello", result)
    }

    @Test
    fun `invalid regex pattern returns an error json instead of throwing`() {
        ToolsRepository.addCustomTool(
            name = "Bad Regex Test C",
            operation = "regex-replace",
            pattern = "[unterminated",
            replacement = "x"
        )
        val result = ToolsRepository.executeToolFunction("custom-bad-regex-test-c", "input")
        assertTrue(result.contains("error"))
        assertTrue(result.contains("Invalid regex"))
    }

    @Test
    fun `newly added custom tool appears in the catalog`() {
        ToolsRepository.addCustomTool(
            name = "Catalog Visible Test D",
            operation = "template",
            pattern = "{input}",
            replacement = ""
        )
        val catalog = ToolsRepository.catalog.value
        assertTrue(catalog.any { it.id == "custom-catalog-visible-test-d" })
        assertTrue(ToolsRepository.getToolsCatalog().any { it.id == "custom-catalog-visible-test-d" })
    }

    @Test
    fun `catalog display description is derived, not parsed back from a stored string`() {
        ToolsRepository.addCustomTool(
            name = "Derived Desc Test E",
            operation = "regex-replace",
            pattern = "foo",
            replacement = "bar"
        )
        val item = ToolsRepository.catalog.value.first { it.id == "custom-derived-desc-test-e" }
        assertEquals("Custom regex: foo → bar", item.description)

        // The tool still works correctly even though its description contains "→", proving
        // pattern/replacement come from stored structured fields, not from re-parsing this text.
        val result = ToolsRepository.executeToolFunction("custom-derived-desc-test-e", "foo baz")
        assertEquals("bar baz", result)
    }
}
