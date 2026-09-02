package com.example.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.util.Locale

// ====================================================================
// COOKIE DATA MODEL & PARSER
// ====================================================================

data class ParsedCookie(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "/",
    val expires: String = "",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String = ""
)

fun parseCookiesFromHeaders(headers: Map<String, String>): List<ParsedCookie> =
    parseCookiesFromHeaderValues(
        headers.entries
            .filter { (name, _) -> name.equals("set-cookie", ignoreCase = true) }
            .map { it.value }
    )

/** Parses distinct Set-Cookie field-lines without comma folding (Expires values contain commas). */
fun parseCookiesFromHeaderValues(headerValues: List<String>): List<ParsedCookie> {
    val result = mutableListOf<ParsedCookie>()
    for (rawHeaderValue in headerValues) {
        for (headerValue in splitCombinedSetCookieHeader(rawHeaderValue)) {
            val parts = headerValue.split(";").map { it.trim() }
            if (parts.isEmpty() || parts[0].isBlank()) continue

            val cookiePair = parts[0].split("=", limit = 2)
            val name = cookiePair[0].trim()
            val value = if (cookiePair.size > 1) cookiePair[1].trim() else ""
            var domain = ""
            var path = "/"
            var expires = ""
            var secure = false
            var httpOnly = false
            var sameSite = ""

            for (index in 1 until parts.size) {
                val attribute = parts[index].split("=", limit = 2)
                val attributeName = attribute[0].trim().lowercase(Locale.ROOT)
                val attributeValue = if (attribute.size > 1) attribute[1].trim() else ""
                when (attributeName) {
                    "domain" -> domain = attributeValue
                    "path" -> path = attributeValue
                    "expires" -> expires = attributeValue
                    "max-age" -> if (expires.isEmpty()) expires = "Max-Age: $attributeValue s"
                    "secure" -> secure = true
                    "httponly" -> httpOnly = true
                    "samesite" -> sameSite = attributeValue
                }
            }

            result += ParsedCookie(
                name = name,
                value = value,
                domain = domain,
                path = path,
                expires = expires,
                secure = secure,
                httpOnly = httpOnly,
                sameSite = sameSite,
            )
        }
    }
    return result
}

/**
 * Legacy map-based callers can still contain illegally folded cookie field-lines. Split only on
 * commas that are followed by a new cookie-pair; commas inside Expires dates are retained.
 */
private fun splitCombinedSetCookieHeader(value: String): List<String> =
    value.split(Regex(",(?=\\s*[^;,=\\s]+\\s*=)"))
        .map(String::trim)
        .filter(String::isNotEmpty)

// ====================================================================
// HEX DUMP FORMATTER
// ====================================================================

data class HexRow(
    val offset: String,
    val hexString: String,
    val asciiString: String
)

fun generateHexRows(bytes: ByteArray, maxBytes: Int = 16384): List<HexRow> {
    val rows = mutableListOf<HexRow>()
    val limit = bytes.size.coerceAtMost(maxBytes)

    for (i in 0 until limit step 16) {
        val offset = String.format("%04X", i)
        val chunkLen = (limit - i).coerceAtMost(16)

        val hexBuilder = StringBuilder()
        val asciiBuilder = StringBuilder()

        for (j in 0 until 16) {
            if (j < chunkLen) {
                val b = bytes[i + j].toInt() and 0xFF
                hexBuilder.append(String.format("%02X ", b))
                if (b in 32..126) {
                    asciiBuilder.append(b.toChar())
                } else {
                    asciiBuilder.append('.')
                }
            } else {
                hexBuilder.append("   ")
            }
        }

        rows.add(
            HexRow(
                offset = offset,
                hexString = hexBuilder.toString().trimEnd(),
                asciiString = asciiBuilder.toString()
            )
        )
    }
    return rows
}

// ====================================================================
// RICH SYNTAX HIGHLIGHTER (JSON, HTML/XML, CSS, JS, PLAINTEXT)
// ====================================================================

object UniversalSyntaxHighlighter {

    fun highlight(text: String, contentType: String, searchQuery: String = ""): List<AnnotatedString> {
        val ct = contentType.lowercase(Locale.ROOT)
        val isJson = ct.contains("json") || text.trimStart().startsWith("{") || text.trimStart().startsWith("[")
        val isHtml = ct.contains("html") || text.trimStart().startsWith("<!doctype", ignoreCase = true) || text.trimStart().startsWith("<html", ignoreCase = true)
        val isXml = ct.contains("xml") || text.trimStart().startsWith("<?xml", ignoreCase = true)

        val lines = text.split("\n")
        return lines.map { line ->
            when {
                isJson -> highlightJsonLine(line, searchQuery)
                isHtml || isXml -> highlightHtmlLine(line, searchQuery)
                else -> highlightGenericLine(line, searchQuery)
            }
        }
    }

    private fun highlightJsonLine(line: String, query: String): AnnotatedString {
        val base = buildAnnotatedString {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0 && line.trimStart().startsWith("\"")) {
                val beforeColon = line.substring(0, colonIdx)
                val afterColon = line.substring(colonIdx)

                // Key in bright cyan
                withStyle(SpanStyle(color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)) {
                    append(beforeColon)
                }
                // Colon
                withStyle(SpanStyle(color = Color(0xFF94A3B8))) {
                    append(":")
                }
                // Value: analyze numbers, strings, booleans
                val valTrim = afterColon.removePrefix(":").trim()
                val valColor = when {
                    valTrim.startsWith("\"") -> Color(0xFF4ADE80) // Green string
                    valTrim.startsWith("true") || valTrim.startsWith("false") -> Color(0xFFF472B6) // Pink boolean
                    valTrim.startsWith("null") -> Color(0xFFFB7185) // Red null
                    valTrim.any { it.isDigit() } -> Color(0xFFFBBF24) // Amber number
                    else -> Color(0xFFE2E8F0)
                }
                withStyle(SpanStyle(color = valColor)) {
                    append(afterColon.removePrefix(":"))
                }
            } else {
                withStyle(SpanStyle(color = Color(0xFF94A3B8))) {
                    append(line)
                }
            }
        }

        return applySearchHighlight(base, query)
    }

    private fun highlightHtmlLine(line: String, query: String): AnnotatedString {
        val base = buildAnnotatedString {
            var i = 0
            val len = line.length

            while (i < len) {
                when {
                    line.startsWith("<!--", i) -> {
                        val end = line.indexOf("-->", i)
                        val comment = if (end != -1) line.substring(i, end + 3) else line.substring(i)
                        withStyle(SpanStyle(color = Color(0xFF64748B), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                            append(comment)
                        }
                        i += comment.length
                    }
                    line[i] == '<' -> {
                        val end = line.indexOf('>', i)
                        if (end != -1) {
                            val tagContent = line.substring(i, end + 1)
                            formatHtmlTag(this, tagContent)
                            i = end + 1
                        } else {
                            withStyle(SpanStyle(color = Color(0xFFF43F5E))) {
                                append(line.substring(i))
                            }
                            i = len
                        }
                    }
                    line.trim().startsWith("--") && line.contains(":") -> {
                        // CSS Variable line
                        val colon = line.indexOf(':')
                        withStyle(SpanStyle(color = Color(0xFF38BDF8))) {
                            append(line.substring(0, colon))
                        }
                        withStyle(SpanStyle(color = Color(0xFF94A3B8))) {
                            append(":")
                        }
                        withStyle(SpanStyle(color = Color(0xFF4ADE80))) {
                            append(line.substring(colon + 1))
                        }
                        i = len
                    }
                    else -> {
                        withStyle(SpanStyle(color = Color(0xFFE2E8F0))) {
                            append(line[i])
                        }
                        i++
                    }
                }
            }
        }

        return applySearchHighlight(base, query)
    }

    private fun formatHtmlTag(builder: AnnotatedString.Builder, tag: String) {
        builder.apply {
            withStyle(SpanStyle(color = Color(0xFF94A3B8))) { append("<") }
            val inner = tag.removePrefix("<").removeSuffix(">")
            val parts = inner.split(Regex("\\s+")).filter { it.isNotBlank() }

            if (parts.isNotEmpty()) {
                val tagName = parts[0]
                withStyle(SpanStyle(color = Color(0xFFF43F5E), fontWeight = FontWeight.Bold)) { // Tag in vibrant coral
                    append(tagName)
                }

                for (p in 1 until parts.size) {
                    append(" ")
                    val attr = parts[p]
                    if (attr.contains("=")) {
                        val kv = attr.split("=", limit = 2)
                        withStyle(SpanStyle(color = Color(0xFF38BDF8))) { // Attribute name
                            append(kv[0])
                        }
                        withStyle(SpanStyle(color = Color(0xFF94A3B8))) { append("=") }
                        withStyle(SpanStyle(color = Color(0xFF4ADE80))) { // Attribute value
                            append(kv[1])
                        }
                    } else {
                        withStyle(SpanStyle(color = Color(0xFF38BDF8))) {
                            append(attr)
                        }
                    }
                }
            }
            withStyle(SpanStyle(color = Color(0xFF94A3B8))) { append(">") }
        }
    }

    private fun highlightGenericLine(line: String, query: String): AnnotatedString {
        val base = buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFFCBD5E1))) {
                append(line)
            }
        }
        return applySearchHighlight(base, query)
    }

    private fun applySearchHighlight(text: AnnotatedString, query: String): AnnotatedString {
        if (query.isBlank()) return text
        val raw = text.text
        var index = raw.indexOf(query, ignoreCase = true)
        if (index < 0) return text

        return buildAnnotatedString {
            var currentPos = 0
            while (index >= 0) {
                // Preceding text with original styling
                if (index > currentPos) {
                    append(text.subSequence(currentPos, index))
                }
                // Highlighted matching range
                val matchEnd = index + query.length
                withStyle(
                    SpanStyle(
                        background = Color(0xFFFBBF24),
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(raw.substring(index, matchEnd))
                }
                currentPos = matchEnd
                index = raw.indexOf(query, currentPos, ignoreCase = true)
            }
            if (currentPos < raw.length) {
                append(text.subSequence(currentPos, raw.length))
            }
        }
    }
}
