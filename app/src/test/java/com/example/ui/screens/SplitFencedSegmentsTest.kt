package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure coverage for [splitFencedSegments] — the alternating (isCode, text) splitter that
 * [MessageBody] uses to render fenced ```code blocks``` with a monospace background inside
 * an otherwise plain chat bubble.
 */
class SplitFencedSegmentsTest {

    @Test
    fun `plain text with no fence is a single non-code segment`() {
        val result = splitFencedSegments("just a plain message")
        assertEquals(listOf(false to "just a plain message"), result)
    }

    @Test
    fun `a single fenced block with no surrounding text is one code segment`() {
        val result = splitFencedSegments("```kotlin\nval x = 1\n```")
        assertEquals(listOf(true to "val x = 1\n"), result)
    }

    @Test
    fun `text before and after a fenced block produces three segments in order`() {
        val content = "before\n```\ncode here\n```\nafter"
        val result = splitFencedSegments(content)
        assertEquals(3, result.size)
        assertEquals(false to "before\n", result[0])
        assertEquals(true to "code here\n", result[1])
        assertEquals(false to "\nafter", result[2])
    }

    @Test
    fun `multiple fenced blocks are each split out as separate code segments`() {
        val content = "```\na\n```\nmiddle\n```\nb\n```"
        val result = splitFencedSegments(content)
        assertEquals(listOf(true to "a\n", false to "\nmiddle\n", true to "b\n"), result)
    }

    @Test
    fun `empty content returns an empty list`() {
        assertEquals(emptyList<Pair<Boolean, String>>(), splitFencedSegments(""))
    }
}
