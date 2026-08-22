package com.example.ui.text

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

/**
 * Task 6 (plan D1) tests for [MarkdownRenderer.render] and [MarkdownRenderer.fromSpanned].
 * These exercise pure Compose-UI-text + Kotlin string logic (no Android framework classes
 * touched by `render`), so plain JUnit is enough — no Robolectric needed here.
 */
class MarkdownRendererTest {

    @Test
    fun `heading produces a bold larger-size span and strips the marker`() {
        val result = MarkdownRenderer.render("# Title")
        assertEquals("Title\n", result.text)
        val boldSpans = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertTrue("expected a bold span for the heading", boldSpans.isNotEmpty())
        val headingSpan = boldSpans.first()
        assertEquals(0, headingSpan.start)
        assertEquals(5, headingSpan.end)
    }

    @Test
    fun `deeper heading levels produce smaller font sizes`() {
        val h1 = MarkdownRenderer.render("# One").spanStyles.first().item.fontSize
        val h3 = MarkdownRenderer.render("### Three").spanStyles.first().item.fontSize
        assertTrue(h1 > h3)
    }

    @Test
    fun `bold marker strips asterisks and produces bold span`() {
        val result = MarkdownRenderer.render("hello **world** end")
        assertEquals("hello world end\n", result.text)
        val bold = result.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("world", result.text.substring(bold.start, bold.end))
    }

    @Test
    fun `italic marker strips single asterisks and produces italic span`() {
        val result = MarkdownRenderer.render("hello *world* end")
        assertEquals("hello world end\n", result.text)
        val italic = result.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals("world", result.text.substring(italic.start, italic.end))
    }

    @Test
    fun `inline code uses monospace font family`() {
        val result = MarkdownRenderer.render("run `ls -la` now")
        assertEquals("run ls -la now\n", result.text)
        val code = result.spanStyles.first { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace }
        assertEquals("ls -la", result.text.substring(code.start, code.end))
    }

    @Test
    fun `fenced code block uses monospace and preserves inner lines`() {
        val source = "```\nline one\nline two\n```"
        val result = MarkdownRenderer.render(source)
        assertTrue(result.text.contains("line one\nline two"))
        val monoSpans = result.spanStyles.filter { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace }
        assertTrue(monoSpans.isNotEmpty())
    }

    @Test
    fun `unordered list items are prefixed with a bullet`() {
        val result = MarkdownRenderer.render("- first\n- second")
        assertEquals("•  first\n•  second\n", result.text)
    }

    @Test
    fun `unmatched single asterisk is left literal`() {
        val result = MarkdownRenderer.render("odd * marker here")
        assertEquals("odd * marker here\n", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `link renders as styled text with the link label kept`() {
        val result = MarkdownRenderer.render("see [docs](https://example.com) here")
        assertEquals("see docs here\n", result.text)
        val link = result.spanStyles.first { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.Underline }
        assertEquals("docs", result.text.substring(link.start, link.end))
    }

    @Test
    fun `plain text with no markers renders unchanged`() {
        val result = MarkdownRenderer.render("just plain text")
        assertEquals("just plain text\n", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }
}
