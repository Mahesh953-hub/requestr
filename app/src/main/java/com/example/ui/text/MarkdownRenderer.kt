package com.example.ui.text

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Task 6 (plan D1) — a small hand-rolled Markdown renderer, since no markdown dependency
 * exists in `app/build.gradle.kts` and `NotesScreen.kt` previously showed Markdown "as-is
 * (monospace)" with an in-code admission that no render lib exists.
 *
 * Supports: ATX headings (`#`..`######`), fenced code blocks, unordered lists (`-`/`*`),
 * and inline `**bold**`, `*italic*`, `` `code` ``, and `[text](url)` links. Unmatched markers
 * (e.g. a lone unbalanced `*`) are left as literal text rather than silently dropped.
 */
object MarkdownRenderer {

    private val inlineRegex = Regex(
        "\\[(?<linktext>[^\\]]+)]\\((?<linkurl>[^)]+)\\)" +
            "|\\*\\*(?<bold>[^*]+)\\*\\*" +
            "|\\*(?<italic>[^*]+)\\*" +
            "|`(?<code>[^`]+)`"
    )

    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val codeBackground = Color(0x22888888)
    private val linkColor = Color(0xFF4EA1FF)

    /** Renders [source] Markdown into a styled [AnnotatedString]. */
    fun render(source: String): AnnotatedString = buildAnnotatedString {
        val lines = source.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmedStart = line.trimStart()
            val heading = headingRegex.find(line)

            when {
                trimmedStart.startsWith("```") -> {
                    i++
                    val codeLines = mutableListOf<String>()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        codeLines.add(lines[i])
                        i++
                    }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(codeLines.joinToString("\n"))
                    }
                    append("\n")
                    // i currently points at the closing fence (or lines.size if unterminated);
                    // the outer i++ below advances past it.
                }

                heading != null -> {
                    val level = heading.groupValues[1].length
                    val fontSize = when (level) {
                        1 -> 22.sp
                        2 -> 20.sp
                        3 -> 18.sp
                        4 -> 16.sp
                        5 -> 15.sp
                        else -> 14.sp
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize)) {
                        appendInline(heading.groupValues[2])
                    }
                    append("\n")
                }

                trimmedStart.startsWith("- ") || trimmedStart.startsWith("* ") -> {
                    append("•  ")
                    appendInline(trimmedStart.substring(2))
                    append("\n")
                }

                else -> {
                    appendInline(line)
                    append("\n")
                }
            }
            i++
        }
    }

    /** Appends [text] with inline bold/italic/code/link styling applied. */
    private fun AnnotatedString.Builder.appendInline(text: String) {
        var lastIndex = 0
        for (match in inlineRegex.findAll(text)) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }
            val linkText = match.groups["linktext"]?.value
            val linkUrl = match.groups["linkurl"]?.value
            val bold = match.groups["bold"]?.value
            val italic = match.groups["italic"]?.value
            val code = match.groups["code"]?.value
            when {
                linkText != null && linkUrl != null -> {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(linkText)
                    }
                }
                bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
                code != null -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                ) { append(code) }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }

    /**
     * Walks a [Spanned]'s real character-style spans into an [AnnotatedString], instead of the
     * previous `AnnotatedString(spanned.toString())` which discarded every span and rendered
     * "HTML preview" as plain tag-stripped text.
     */
    fun fromSpanned(spanned: Spanned): AnnotatedString = buildAnnotatedString {
        append(spanned.toString())
        val spans = spanned.getSpans(0, spanned.length, CharacterStyle::class.java)
        for (span in spans) {
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end < 0 || start >= end) continue
            val style = when (span) {
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> null
                }
                is URLSpan -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
                else -> null
            }
            style?.let { addStyle(it, start, end) }
        }
    }

    /** A single `- [ ]` / `- [x]` checklist row parsed out of a note's raw content. */
    data class ChecklistItem(val text: String, val checked: Boolean, val lineIndex: Int)

    private val checklistLineRegex = Regex("^\\s*-\\s\\[([ xX])]\\s?(.*)$")

    /** Parses every checklist line in [source]. Non-checklist lines are simply skipped. */
    fun parseChecklist(source: String): List<ChecklistItem> =
        source.split("\n").mapIndexedNotNull { index, line ->
            val match = checklistLineRegex.find(line) ?: return@mapIndexedNotNull null
            ChecklistItem(
                text = match.groupValues[2],
                checked = match.groupValues[1].equals("x", ignoreCase = true),
                lineIndex = index
            )
        }

    /**
     * Flips the checked state of the checklist line at [lineIndex] and returns the updated
     * source text. Returns [source] unchanged if [lineIndex] isn't a checklist line, so callers
     * can write the result straight back through `NotesRepository.saveNote` without validating
     * first.
     */
    fun toggleChecklistItem(source: String, lineIndex: Int): String {
        val lines = source.split("\n").toMutableList()
        if (lineIndex !in lines.indices) return source
        val line = lines[lineIndex]
        val match = Regex("^(\\s*-\\s\\[)([ xX])(]\\s?.*)$").find(line) ?: return source
        val newMark = if (match.groupValues[2].equals("x", ignoreCase = true)) " " else "x"
        lines[lineIndex] = match.groupValues[1] + newMark + match.groupValues[3]
        return lines.joinToString("\n")
    }
}
