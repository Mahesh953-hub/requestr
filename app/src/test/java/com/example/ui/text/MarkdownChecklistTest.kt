package com.example.ui.text

import org.junit.Assert.*
import org.junit.Test

class MarkdownChecklistTest {

    @Test
    fun `unchecked and checked lines parse to the right checked state`() {
        val source = "- [ ] todo one\n- [x] done one\n- [X] done two (uppercase X)"
        val items = MarkdownRenderer.parseChecklist(source)
        assertEquals(3, items.size)
        assertFalse(items[0].checked)
        assertEquals("todo one", items[0].text)
        assertTrue(items[1].checked)
        assertEquals("done one", items[1].text)
        assertTrue(items[2].checked)
    }

    @Test
    fun `non-checklist lines are skipped`() {
        val source = "just a note\n- [ ] real item\nanother plain line"
        val items = MarkdownRenderer.parseChecklist(source)
        assertEquals(1, items.size)
        assertEquals("real item", items[0].text)
        assertEquals(1, items[0].lineIndex)
    }

    @Test
    fun `toggling an unchecked item checks it and round-trips into the source`() {
        val source = "- [ ] task"
        val toggled = MarkdownRenderer.toggleChecklistItem(source, 0)
        assertEquals("- [x] task", toggled)
        val items = MarkdownRenderer.parseChecklist(toggled)
        assertTrue(items[0].checked)
    }

    @Test
    fun `toggling a checked item unchecks it and round-trips into the source`() {
        val source = "- [x] task"
        val toggled = MarkdownRenderer.toggleChecklistItem(source, 0)
        assertEquals("- [ ] task", toggled)
        val items = MarkdownRenderer.parseChecklist(toggled)
        assertFalse(items[0].checked)
    }

    @Test
    fun `toggling preserves other lines untouched`() {
        val source = "- [ ] first\n- [x] second\n- [ ] third"
        val toggled = MarkdownRenderer.toggleChecklistItem(source, 1)
        assertEquals("- [ ] first\n- [ ] second\n- [ ] third", toggled)
    }

    @Test
    fun `toggling a non-checklist line index returns the source unchanged`() {
        val source = "just plain text\nanother line"
        val toggled = MarkdownRenderer.toggleChecklistItem(source, 0)
        assertEquals(source, toggled)
    }

    @Test
    fun `toggling an out-of-range index returns the source unchanged`() {
        val source = "- [ ] only item"
        val toggled = MarkdownRenderer.toggleChecklistItem(source, 5)
        assertEquals(source, toggled)
    }

    @Test
    fun `empty checklist source parses to no items`() {
        assertTrue(MarkdownRenderer.parseChecklist("").isEmpty())
    }
}
