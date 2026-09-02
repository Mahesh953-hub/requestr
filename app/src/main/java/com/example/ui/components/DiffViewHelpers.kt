package com.example.ui.components

import com.example.data.analysis.TextDiffer

/**
 * Pure, JVM-testable state model behind [DiffView].
 *
 * Classifies a pair of texts into one of the [DiffKind] states the view must render explicitly
 * (DESIGN-SYSTEM §15 "every state designed") and guards the O(n·m) LCS in [TextDiffer] against
 * inputs large enough to exhaust memory.
 */
enum class DiffKind {
    /** Both sides empty — nothing to compare. */
    BOTH_EMPTY,
    /** Texts are byte-for-byte equal. */
    IDENTICAL,
    /** Every changed line is an insertion (old side is a strict subset). */
    ADDITIONS_ONLY,
    /** Every changed line is a deletion (new side is a strict subset). */
    DELETIONS_ONLY,
    /** Both insertions and deletions present. */
    MIXED,
    /** One or both sides look like binary data — a line diff would be meaningless. */
    BINARY,
    /** Line-count product exceeds [DEFAULT_MAX_LINE_PRODUCT]; a line diff is not attempted. */
    TOO_LARGE,
}

/** Everything [DiffView] needs to render, computed once per (old, new) pair. */
data class DiffModel(
    val kind: DiffKind,
    val hunks: List<TextDiffer.DiffHunk>,
    val added: Int,
    val removed: Int,
    val unchanged: Int,
    val oldLineCount: Int,
    val newLineCount: Int,
) {
    val hasRenderableHunks: Boolean
        get() = kind == DiffKind.ADDITIONS_ONLY || kind == DiffKind.DELETIONS_ONLY || kind == DiffKind.MIXED
}

/**
 * Upper bound on `oldLines * newLines` before the LCS table (an `Int[n+1][m+1]`) is considered
 * too expensive. 4M cells ≈ 16 MB, comfortably below what a mid-range device tolerates for a
 * transient UI computation.
 */
const val DEFAULT_MAX_LINE_PRODUCT: Long = 4_000_000L

/** How many leading characters [looksBinary] inspects. */
private const val BINARY_SNIFF_CHARS = 8_192

/**
 * Heuristic binary detection, mirroring what `git` and most editors do: any NUL byte, or more
 * than ~10% of the sniffed prefix being non-text control characters, means "not text". Tab,
 * newline and carriage return are always treated as text. U+FFFD (the decoder's replacement
 * character) counts as a control character because it means undecodable bytes were present.
 */
fun looksBinary(text: String): Boolean {
    if (text.isEmpty()) return false
    val sample = if (text.length > BINARY_SNIFF_CHARS) text.substring(0, BINARY_SNIFF_CHARS) else text
    var control = 0
    for (ch in sample) {
        if (ch == '\u0000') return true
        val isTextWhitespace = ch == '\n' || ch == '\r' || ch == '\t'
        if (!isTextWhitespace && (ch.code < 0x20 || ch == '\uFFFD' || ch.code == 0x7F)) {
            control++
        }
    }
    return control * 10 > sample.length
}

/** Number of lines a text splits into, matching [TextDiffer.diff]'s `split("\n")` semantics. */
fun diffLineCount(text: String): Int = text.count { it == '\n' } + 1

/**
 * Builds the [DiffModel] for [oldText] vs [newText]. Never throws; never allocates the LCS
 * table when the inputs are binary, identical or too large.
 */
fun buildDiffModel(
    oldText: String,
    newText: String,
    maxLineProduct: Long = DEFAULT_MAX_LINE_PRODUCT,
): DiffModel {
    val oldLines = diffLineCount(oldText)
    val newLines = diffLineCount(newText)

    fun empty(kind: DiffKind) = DiffModel(
        kind = kind, hunks = emptyList(), added = 0, removed = 0,
        unchanged = if (kind == DiffKind.IDENTICAL) oldLines else 0,
        oldLineCount = oldLines, newLineCount = newLines,
    )

    if (oldText.isEmpty() && newText.isEmpty()) return empty(DiffKind.BOTH_EMPTY)
    if (looksBinary(oldText) || looksBinary(newText)) return empty(DiffKind.BINARY)
    if (oldText == newText) return empty(DiffKind.IDENTICAL)
    if (oldLines.toLong() * newLines.toLong() > maxLineProduct) return empty(DiffKind.TOO_LARGE)

    val hunks = TextDiffer.diff(oldText, newText)
    val (added, removed, unchanged) = TextDiffer.summarize(hunks)
    val kind = when {
        added > 0 && removed == 0 -> DiffKind.ADDITIONS_ONLY
        removed > 0 && added == 0 -> DiffKind.DELETIONS_ONLY
        added == 0 && removed == 0 -> DiffKind.IDENTICAL
        else -> DiffKind.MIXED
    }
    return DiffModel(kind, hunks, added, removed, unchanged, oldLines, newLines)
}

/** User-facing explanation for the non-hunk states; null for states that render hunks. */
fun diffStateMessage(model: DiffModel): String? = when (model.kind) {
    DiffKind.BOTH_EMPTY -> "Both sides are empty — nothing to compare."
    DiffKind.IDENTICAL -> "No differences. Both sides are identical (${model.oldLineCount} ${pluralLines(model.oldLineCount)})."
    DiffKind.BINARY -> "Binary content detected — a line diff would be meaningless. Compare sizes or export both payloads instead."
    DiffKind.TOO_LARGE -> "Too large to diff line-by-line (${model.oldLineCount} vs ${model.newLineCount} lines). Narrow the selection or compare a section."
    DiffKind.ADDITIONS_ONLY, DiffKind.DELETIONS_ONLY, DiffKind.MIXED -> null
}

/** Compact "+a −r" summary for the header pills; unchanged count is reported separately. */
fun diffSummaryLabel(model: DiffModel): String = when (model.kind) {
    DiffKind.ADDITIONS_ONLY -> "+${model.added} added"
    DiffKind.DELETIONS_ONLY -> "−${model.removed} removed"
    DiffKind.MIXED -> "+${model.added} −${model.removed}"
    DiffKind.IDENTICAL -> "identical"
    DiffKind.BOTH_EMPTY -> "empty"
    DiffKind.BINARY -> "binary"
    DiffKind.TOO_LARGE -> "too large"
}

private fun pluralLines(n: Int) = if (n == 1) "line" else "lines"

/** Gutter text for a hunk: right-aligned line number plus the +/−/space marker. */
fun diffGutterText(hunk: TextDiffer.DiffHunk, width: Int): String {
    val lineNum = if (hunk.op == TextDiffer.Op.DELETE) hunk.oldLineNum else hunk.newLineNum
    val marker = when (hunk.op) {
        TextDiffer.Op.INSERT -> '+'
        TextDiffer.Op.DELETE -> '−'
        TextDiffer.Op.EQUAL -> ' '
    }
    return "${lineNum.toString().padStart(width)} $marker"
}

/** Number of digits needed to right-align every line number in [model]. */
fun diffGutterWidth(model: DiffModel): Int =
    maxOf(model.oldLineCount, model.newLineCount).toString().length.coerceAtLeast(2)
