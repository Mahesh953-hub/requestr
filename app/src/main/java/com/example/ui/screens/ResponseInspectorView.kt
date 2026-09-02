package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.export.FileExporter
import com.example.data.export.MediaStoreExporter
import com.example.data.models.ApiResponseData
import com.example.data.models.Assertion
import com.example.data.models.AssertionResult
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.StatusCodeBadge
import com.example.ui.components.rememberAppHapticFeedback
import kotlinx.coroutines.launch
import java.util.Locale
import com.example.ui.theme.animateStateColor
import com.example.ui.theme.scaledTween

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

fun parseCookiesFromHeaders(headers: Map<String, String>): List<ParsedCookie> {
    val cookieHeaders = headers.filter { (k, _) -> k.equals("set-cookie", ignoreCase = true) }
    val result = mutableListOf<ParsedCookie>()

    for ((_, headerVal) in cookieHeaders) {
        // Can be comma-separated multiple cookies or single
        val lines = headerVal.split(Regex("(?<!Expires=.*),(?! )"))
        for (line in lines) {
            val parts = line.split(";").map { it.trim() }
            if (parts.isEmpty() || parts[0].isBlank()) continue

            val kv = parts[0].split("=", limit = 2)
            val name = kv[0].trim()
            val value = if (kv.size > 1) kv[1].trim() else ""

            var domain = ""
            var path = "/"
            var expires = ""
            var secure = false
            var httpOnly = false
            var sameSite = ""

            for (i in 1 until parts.size) {
                val attr = parts[i]
                val attrKv = attr.split("=", limit = 2)
                val attrName = attrKv[0].trim().lowercase(Locale.ROOT)
                val attrVal = if (attrKv.size > 1) attrKv[1].trim() else ""

                when (attrName) {
                    "domain" -> domain = attrVal
                    "path" -> path = attrVal
                    "expires" -> expires = attrVal
                    "max-age" -> if (expires.isEmpty()) expires = "Max-Age: $attrVal s"
                    "secure" -> secure = true
                    "httponly" -> httpOnly = true
                    "samesite" -> sameSite = attrVal
                }
            }

            result.add(
                ParsedCookie(
                    name = name,
                    value = value,
                    domain = domain,
                    path = path,
                    expires = expires,
                    secure = secure,
                    httpOnly = httpOnly,
                    sameSite = sameSite
                )
            )
        }
    }
    return result
}

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

// ====================================================================
// COMPLETE RICH RESPONSE INSPECTOR COMPONENT
// ====================================================================

@Composable
fun RichResponseInspector(
    response: ApiResponseData,
    requestUrl: String,
    assertions: List<Assertion>,
    testResults: List<AssertionResult>?,
    isDark: Boolean,
    onRunTests: () -> Unit,
    onBeautifyBody: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val performHaptic = rememberAppHapticFeedback()
    val scope = rememberCoroutineScope()

    // Top response tabs: 0: Body, 1: Headers, 2: Set-Cookies, 3: Metrics, 4: Tests
    val cookies = remember(response.headers) { parseCookiesFromHeaders(response.headers) }
    val contentType = remember(response.headers) {
        response.headers.entries.find { it.key.equals("content-type", ignoreCase = true) }?.value ?: "text/plain"
    }

    val hasAssertions = assertions.any { it.enabled }
    val tabs = remember(response.headers.size, cookies.size, hasAssertions, testResults) {
        val list = mutableListOf(
            "Body" to "",
            "Headers" to "(${response.headers.size})",
            "Set-Cookies" to if (cookies.isNotEmpty()) "(${cookies.size})" else "",
            "Metrics" to ""
        )
        if (hasAssertions) {
            val badge = testResults?.let { " (${it.count { r -> r.passed }}/${it.size})" } ?: ""
            list.add("Tests" to badge)
        }
        list
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var bodyViewMode by remember { mutableStateOf("Text") } // "Text" vs "Hex"
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    val rawBytes = remember(response.body) { response.body.toByteArray(Charsets.UTF_8) }
    val hexRows = remember(rawBytes) { generateHexRows(rawBytes) }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        onShowSnackbar("Copied $label")
        performHaptic(HapticFeedbackType.TextHandleMove)
    }

    fun exportResponse(isHex: Boolean) {
        scope.launch {
            try {
                val content = if (isHex) {
                    hexRows.joinToString("\n") { "${it.offset}  ${it.hexString.padEnd(48)}  ${it.asciiString}" }
                } else {
                    response.body
                }
                val result = MediaStoreExporter.writeResponseBody(
                    context = context,
                    body = content,
                    contentType = contentType,
                    url = requestUrl,
                    isHex = isHex
                )
                if (result != null) {
                    val (target, mime) = result
                    FileExporter.shareFile(context, target, mime, "Exported Response")
                    val displayName = when (target) {
                        is MediaStoreExporter.ExportTarget.MediaStoreTarget -> target.displayName
                        is MediaStoreExporter.ExportTarget.FileTarget -> target.file.name
                    }
                    onShowSnackbar("Saved $displayName")
                    performHaptic(HapticFeedbackType.LongPress)
                } else {
                    onShowSnackbar("Export failed: could not create file")
                }
            } catch (e: Exception) {
                onShowSnackbar("Export failed: ${e.message}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ==========================================
        // 1. RESPONSE STATUS & PROTOCOL HEADER BAR
        // ==========================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusCodeBadge(statusCode = response.statusCode, statusMessage = response.statusMessage)
                    Spacer(modifier = Modifier.width(8.dp))
                    ProtocolBadge(protocol = response.protocol)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Latency Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${response.latencyMs} ms",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Size Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = formatDataSize(response.sizeBytes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // 2. RESPONSE TABS ROW
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEachIndexed { idx, (title, badge) ->
                val isSelected = selectedTab == idx
                val targetShape = if (isSelected) RoundedCornerShape(24.dp) else RoundedCornerShape(12.dp)
                // Selection animates; the theme does not. Both endpoints are theme tokens, so
                // animating the colour itself made every tab cross-fade on a theme swap.
                val bgColor by animateStateColor(
                    active = isSelected,
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = MaterialTheme.colorScheme.surfaceVariant,
                    animationSpec = scaledTween(200),
                    label = "tabBg"
                )
                val contentColor by animateStateColor(
                    active = isSelected,
                    activeColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = scaledTween(200),
                    label = "tabContent"
                )

                Surface(
                    onClick = {
                        selectedTab = idx
                        performHaptic(HapticFeedbackType.TextHandleMove)
                    },
                    shape = targetShape,
                    color = bgColor,
                    border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) else null
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$title $badge".trimEnd(),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // 3. TAB VIEW CONTENTS
        // ==========================================
        when (selectedTab) {
            0 -> {
                // ==========================================
                // BODY TAB (Text / Hex & Syntax Highlighting)
                // ==========================================
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Body Toolbar (Mode Switcher: Text / Hex + Actions)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        color = if (isDark) Color(0xFF0D111A) else Color(0xFF1E2430),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263345))
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Text / Hex Selector
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xFF182232))
                                        .padding(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    listOf("Text", "Hex").forEach { mode ->
                                        val isSel = bodyViewMode == mode
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                .clickable {
                                                    bodyViewMode = mode
                                                    performHaptic(HapticFeedbackType.TextHandleMove)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = mode,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSel) MaterialTheme.colorScheme.onPrimary else Color(0xFF8B95A6)
                                            )
                                        }
                                    }
                                }

                                // Actions Toolbar: Search, Beautify, Copy, Export
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Search Action
                                    IconButton(
                                        onClick = {
                                            showSearchBar = !showSearchBar
                                            if (!showSearchBar) searchQuery = ""
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search in response",
                                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary else Color(0xFF8B95A6),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    // Beautify / Format (if Text mode)
                                    if (bodyViewMode == "Text") {
                                        IconButton(
                                            onClick = onBeautifyBody,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = "Beautify",
                                                tint = Color(0xFF8B95A6),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }

                                    // Copy Action
                                    IconButton(
                                        onClick = {
                                            if (bodyViewMode == "Hex") {
                                                val hexText = hexRows.joinToString("\n") { "${it.offset}  ${it.hexString}  ${it.asciiString}" }
                                                copyToClipboard("Hex Dump", hexText)
                                            } else {
                                                copyToClipboard("Response Body", response.body)
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            tint = Color(0xFF8B95A6),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    // Save / Export Action
                                    IconButton(
                                        onClick = { exportResponse(isHex = bodyViewMode == "Hex") },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FileDownload,
                                            contentDescription = "Save and export",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Search Input Field Bar
                            AnimatedVisibility(visible = showSearchBar) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF141C28))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        textStyle = TextStyle(
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        singleLine = true,
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        decorationBox = { inner ->
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Find in response text...",
                                                    color = Color(0xFF64748B),
                                                    fontSize = 11.5.sp
                                                )
                                            }
                                            inner()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        val matchCount = response.body.split(searchQuery).size - 1
                                        Text(
                                            text = "$matchCount matches",
                                            fontSize = 10.sp,
                                            color = Color(0xFFFBBF24),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear",
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Content Viewer Box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                        color = if (isDark) Color(0xFF05080E) else Color(0xFF141924),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263345))
                    ) {
                        if (response.body.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Empty response body", color = Color(0xFF64748B), fontSize = 12.sp)
                            }
                        } else if (bodyViewMode == "Hex") {
                            // ==========================================
                            // HEX VIEWER (Offset | Hex Dump | ASCII)
                            // ==========================================
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp)
                            ) {
                                hexRows.forEach { row ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Offset Column (0000, 0010...)
                                        Text(
                                            text = row.offset,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF64748B),
                                            modifier = Modifier.width(36.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // Hex Dump Column (3C 21 64...)
                                        Text(
                                            text = row.hexString,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFF38BDF8),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // ASCII Column
                                        Text(
                                            text = row.asciiString,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFF4ADE80),
                                            maxLines = 1,
                                            overflow = TextOverflow.Clip
                                        )
                                    }
                                }
                            }
                        } else {
                            // ==========================================
                            // TEXT SYNTAX HIGHLIGHTED CODE VIEWER
                            // ==========================================
                            val highlightedLines = remember(response.body, contentType, searchQuery) {
                                UniversalSyntaxHighlighter.highlight(response.body, contentType, searchQuery)
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 8.dp)
                            ) {
                                highlightedLines.forEachIndexed { idx, lineAnnotated ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Line Number Gutter
                                        Text(
                                            text = "${idx + 1}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            color = Color(0xFF475569),
                                            textAlign = TextAlign.End,
                                            modifier = Modifier
                                                .width(34.dp)
                                                .padding(end = 8.dp)
                                        )
                                        // Line divider
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(18.dp)
                                                .background(Color(0xFF1E293B))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // Code Text
                                        Text(
                                            text = lineAnnotated,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            modifier = Modifier
                                                .weight(1f)
                                                .horizontalScroll(rememberScrollState())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // ==========================================
                // HEADERS TAB
                // ==========================================
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${response.headers.size} RESPONSE HEADERS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        IconButton(
                            onClick = {
                                val flat = response.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                                copyToClipboard("Headers", flat)
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy all", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        }
                    }

                    response.headers.forEach { (k, v) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = k,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = v,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(
                                    onClick = { copyToClipboard(k, "$k: $v") },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // ==========================================
                // SET-COOKIES TAB (REQABLE STYLE DETAILED CARDS)
                // ==========================================
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${cookies.size} SET-COOKIES PARSED",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        if (cookies.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val cookieStr = cookies.joinToString("\n\n") { c ->
                                        "Name: ${c.name}\nValue: ${c.value}\nDomain: ${c.domain}\nPath: ${c.path}\nExpires: ${c.expires}\nSecure: ${c.secure}\nHttpOnly: ${c.httpOnly}"
                                    }
                                    copyToClipboard("Cookies", cookieStr)
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy all cookies", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    if (cookies.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No Set-Cookie headers in this response.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        cookies.forEach { cookie ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // Name + Copy Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Name", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = cookie.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = { copyToClipboard("Cookie ${cookie.name}", "${cookie.name}=${cookie.value}") },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Value
                                    Text("Value", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = cookie.value,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Attributes Row (Domain & Path)
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        if (cookie.domain.isNotBlank()) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Domain", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(cookie.domain, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Path", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(cookie.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }

                                    if (cookie.expires.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("Expires", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(cookie.expires, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Flags: Secure, HttpOnly, SameSite
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (cookie.secure) {
                                            CookieFlagBadge(label = "Secure", color = Color(0xFF10B981))
                                        }
                                        if (cookie.httpOnly) {
                                            CookieFlagBadge(label = "HttpOnly", color = Color(0xFF3B82F6))
                                        }
                                        if (cookie.sameSite.isNotBlank()) {
                                            CookieFlagBadge(label = "SameSite=${cookie.sameSite}", color = Color(0xFF8B5CF6))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                // ==========================================
                // METRICS & TIMELINE OVERVIEW TAB (REQABLE METRICS)
                // ==========================================
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Request ID Card
                    val reqId = response.headers["x-request-id"] ?: "req-${response.timestamp.toString(16)}"
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Request-Id", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                Text(reqId, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(
                                onClick = { copyToClipboard("Request-Id", reqId) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                            }
                        }
                    }

                    // Timing Breakdown Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("TIMING BREAKDOWN", fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                            Spacer(modifier = Modifier.height(10.dp))

                            val total = (response.dnsTimeMs + response.connectTimeMs + response.tlsTimeMs + response.requestTimeMs + response.responseTimeMs).coerceAtLeast(1)
                            TimelineMetricBar("DNS Resolution", "${response.dnsTimeMs} ms", (response.dnsTimeMs.toFloat() / total).coerceIn(0.05f, 1f), Color(0xFF3B82F6))
                            Spacer(modifier = Modifier.height(6.dp))
                            TimelineMetricBar("TCP Connect", "${response.connectTimeMs} ms", (response.connectTimeMs.toFloat() / total).coerceIn(0.05f, 1f), Color(0xFF06B6D4))
                            Spacer(modifier = Modifier.height(6.dp))
                            TimelineMetricBar("TLS Handshake", "${response.tlsTimeMs} ms", (response.tlsTimeMs.toFloat() / total).coerceIn(0.05f, 1f), Color(0xFF8B5CF6))
                            Spacer(modifier = Modifier.height(6.dp))
                            TimelineMetricBar("TTFB / Request Sent", "${response.requestTimeMs} ms", (response.requestTimeMs.toFloat() / total).coerceIn(0.05f, 1f), Color(0xFFF59E0B))
                            Spacer(modifier = Modifier.height(6.dp))
                            TimelineMetricBar("Content Download", "${response.responseTimeMs} ms", (response.responseTimeMs.toFloat() / total).coerceIn(0.05f, 1f), Color(0xFF22C55E))
                        }
                    }

                    // Size Breakdown Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("PAYLOAD SIZE", fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                            Spacer(modifier = Modifier.height(8.dp))

                            val headerSize = response.headers.entries.sumOf { it.key.length + it.value.length + 4 }.toLong()
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Headers", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatDataSize(headerSize), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Body", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatDataSize(response.sizeBytes), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Total", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatDataSize(headerSize + response.sizeBytes), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }

            4 -> {
                // ==========================================
                // TESTS TAB
                // ==========================================
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ASSERTION CHECKS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        Button(
                            onClick = onRunTests,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Re-run Tests", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    val results = testResults
                    if (results == null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Tap \"Re-run Tests\" above to evaluate test assertions.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    } else {
                        val passedCount = results.count { it.passed }
                        Text(
                            text = "$passedCount / ${results.size} assertions passed",
                            fontWeight = FontWeight.Bold,
                            color = if (passedCount == results.size) Color(0xFF22C55E) else Color(0xFFEF4444),
                            fontSize = 13.sp
                        )
                        results.forEach { result ->
                            AssertionResultRow(result)
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// SUB-COMPONENTS & BADGES
// ====================================================================

@Composable
private fun CookieFlagBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatDataSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
    }
}
