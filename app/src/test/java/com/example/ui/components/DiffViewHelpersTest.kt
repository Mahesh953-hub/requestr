package com.example.ui.components

import com.example.data.analysis.TextDiffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function coverage for the state model behind the Phase B [DiffView]. */
class DiffViewHelpersTest {

    @Test
    fun `both empty and identical inputs never run the line diff`() {
        val empty = buildDiffModel("", "")
        assertEquals(DiffKind.BOTH_EMPTY, empty.kind)
        assertTrue(empty.hunks.isEmpty())
        assertFalse(empty.hasRenderableHunks)

        val same = buildDiffModel("a\nb\nc", "a\nb\nc")
        assertEquals(DiffKind.IDENTICAL, same.kind)
        assertEquals(3, same.unchanged)
        assertEquals(0, same.added)
        assertEquals(0, same.removed)
        assertTrue(same.hunks.isEmpty())
        assertEquals("No differences. Both sides are identical (3 lines).", diffStateMessage(same))
    }

    @Test
    fun `additions only, deletions only and mixed are classified from the hunks`() {
        val add = buildDiffModel("a\nb", "a\nb\nc\nd")
        assertEquals(DiffKind.ADDITIONS_ONLY, add.kind)
        assertEquals(2, add.added)
        assertEquals(0, add.removed)
        assertEquals("+2 added", diffSummaryLabel(add))
        assertNull(diffStateMessage(add))

        val del = buildDiffModel("a\nb\nc", "b")
        assertEquals(DiffKind.DELETIONS_ONLY, del.kind)
        assertEquals(2, del.removed)
        assertEquals("−2 removed", diffSummaryLabel(del))

        val mixed = buildDiffModel("a\nb\nc", "a\nx\nc")
        assertEquals(DiffKind.MIXED, mixed.kind)
        assertEquals(1, mixed.added)
        assertEquals(1, mixed.removed)
        assertEquals(2, mixed.unchanged)
        assertEquals("+1 −1", diffSummaryLabel(mixed))
        assertTrue(mixed.hasRenderableHunks)
    }

    @Test
    fun `binary detection triggers on NUL or a high control-character ratio, not on tabs or newlines`() {
        assertTrue(looksBinary("PNG\u0000\u0001\u0002"))
        assertTrue(looksBinary("\u0001\u0002\u0003\u0004abc"))
        assertFalse(looksBinary("line one\n\tindented\r\nline two"))
        assertFalse(looksBinary(""))
        // Replacement characters mean the decoder hit undecodable bytes.
        assertTrue(looksBinary("\uFFFD\uFFFD\uFFFDab"))

        val model = buildDiffModel("text", "\u0000binary")
        assertEquals(DiffKind.BINARY, model.kind)
        assertTrue(model.hunks.isEmpty())
        assertNotNull(diffStateMessage(model))
        assertEquals("binary", diffSummaryLabel(model))
    }

    @Test
    fun `line-count product above the cap short-circuits to TOO_LARGE without allocating the LCS`() {
        val old = (1..100).joinToString("\n") { "o$it" }
        val new = (1..100).joinToString("\n") { "n$it" }
        val model = buildDiffModel(old, new, maxLineProduct = 5_000L)
        assertEquals(DiffKind.TOO_LARGE, model.kind)
        assertEquals(100, model.oldLineCount)
        assertEquals(100, model.newLineCount)
        assertTrue(model.hunks.isEmpty())
        assertEquals("Too large to diff line-by-line (100 vs 100 lines). Narrow the selection or compare a section.", diffStateMessage(model))

        // Same inputs under the default cap diff normally.
        assertEquals(DiffKind.MIXED, buildDiffModel(old, new).kind)
    }

    @Test
    fun `line counting matches TextDiffer split semantics`() {
        assertEquals(1, diffLineCount(""))
        assertEquals(1, diffLineCount("abc"))
        assertEquals(3, diffLineCount("a\nb\nc"))
        assertEquals(4, diffLineCount("a\nb\nc\n"))
    }

    @Test
    fun `gutter text right-aligns line numbers and uses the correct marker per op`() {
        val ins = TextDiffer.DiffHunk(TextDiffer.Op.INSERT, "", "new", 0, 7)
        val del = TextDiffer.DiffHunk(TextDiffer.Op.DELETE, "old", "", 12, 0)
        val eq = TextDiffer.DiffHunk(TextDiffer.Op.EQUAL, "same", "same", 3, 3)
        assertEquals("  7 +", diffGutterText(ins, 3))
        assertEquals(" 12 −", diffGutterText(del, 3))
        assertEquals("  3  ", diffGutterText(eq, 3))

        val model = buildDiffModel((1..120).joinToString("\n"), "1")
        assertEquals(3, diffGutterWidth(model))
        assertEquals(2, diffGutterWidth(buildDiffModel("a", "b")))
    }
}
