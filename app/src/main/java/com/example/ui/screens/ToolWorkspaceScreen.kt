package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.ToolsRepository
import com.example.ui.components.BadgePill
import com.example.ui.components.DriveParallaxFrom
import com.example.ui.components.EmptyState
import com.example.ui.components.FloatingCapsuleHeader
import com.example.ui.components.GlassActionPill
import com.example.ui.components.GlassCard

/**
 * Look up a tool by id and render its domain-specific workspace.
 *
 * Phase B (PHASE-B-REBRAND-NO-MOCKUP-COVERAGE §Tool workspace): the header is the shipped
 * [FloatingCapsuleHeader] — the single `FloatingCapsuleHeader(...)` call in this file — so a
 * future header primitive can swap in at exactly that spot. The tool is resolved from the live
 * [ToolsRepository.catalog] flow (not a one-shot snapshot) so a custom tool that finishes loading
 * from Room after this screen is entered still resolves instead of dead-ending in "not found".
 */
@Composable
fun ToolWorkspaceScreen(
    toolId: String,
    onBack: () -> Unit
) {
    val catalog by ToolsRepository.catalog.collectAsState()
    val tool = remember(toolId, catalog) { catalog.find { it.id == toolId } }
    // Record the open through ToolsRepository.openTool -- the single mediating call that feeds
    // Home's Room-backed "Recently opened" -- once the id resolves. Keyed on `tool?.id` (not the
    // catalog) so it fires exactly once per screen entry, and only after a late-loading custom
    // tool has actually appeared; rendering still follows the live catalog above, so the
    // "Tool not found" state below stays reachable for an id that never resolves.
    LaunchedEffect(toolId, tool?.id) {
        if (tool != null) ToolsRepository.openTool(toolId)
    }

    // Transparent root so the ParallaxBackground hosted in MainActivity shows through.
    Column(modifier = Modifier.fillMaxSize()) {
        // Header primitive — single call site for this screen.
        FloatingCapsuleHeader(
            title = tool?.title ?: "Tool not found",
            icon = tool?.icon ?: Icons.Default.Build,
            onBack = onBack,
            showWordmarkDot = false,
            trailingContent = {
                if (tool != null) {
                    BadgePill(
                        text = "${tool.category.icon} ${tool.category.displayName}".uppercase(),
                        color = 0xFF6366F1
                    )
                }
            }
        )

        if (tool != null) {
            Text(
                text = tool.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }

        // Workspace body
        if (tool == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    message = "No tool with id \"$toolId\" exists in the catalog. It may have been removed or the link is stale.",
                    cta = {
                        GlassActionPill(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to tools",
                            onClick = onBack,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                )
            }
        } else {
            val scrollState = rememberScrollState()
            // The workspace body is the screen's scroll container: it drives the parallax layer.
            DriveParallaxFrom(scrollState)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                when (tool.id) {
                    // ── Reversible simple tools ──────────────────
                    "base64"        -> Base64Workspace()
                    "url-encoder"   -> UrlEncoderWorkspace()
                    "hex-converter" -> HexConverterWorkspace()
                    "html-entity"   -> HtmlEntityWorkspace()
                    "morse-code"    -> MorseCodeWorkspace()
                    "xor-cipher"    -> XorCipherWorkspace()

                    // ── Hash tools ──────────────────────────────
                    "sha256"        -> HashWorkspace("SHA-256")
                    "sha1"          -> HashWorkspace("SHA-1")
                    "md5"           -> HashWorkspace("MD5")
                    "hmac-sha256"   -> HmacWorkspace()
                    "crc32"         -> Crc32Workspace()

                    // ── Crypto ──────────────────────────────────
                    "aes-cipher"    -> AesCipherWorkspace()

                    // ── Formatters ──────────────────────────────
                    "json-formatter" -> JsonFormatterWorkspace()
                    "sql-formatter"  -> SqlFormatterWorkspace()

                    // ── Network ────────────────────────────────
                    "ip-calculator" -> IpCalculatorWorkspace()

                    // ── Token decoder ──────────────────────────
                    "jwt-decoder"   -> JwtDecoderWorkspace()

                    // ── Generators ─────────────────────────────
                    "uuid-gen"      -> GeneratorWorkspace("UUID v4", "Generate", ::uuidGen)
                    "random-string" -> GeneratorWorkspace("Random String", "Generate", ::randomStringGen)
                    "lorem-ipsum"   -> GeneratorWorkspace("Lorem Ipsum", "Generate", ::loremGen)

                    else -> CustomToolWorkspace(tool.id)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Reversible simple tools
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ReversibleWorkspace(
    label: String,
    encodeLabel: String,
    decodeLabel: String,
    encode: (String) -> String,
    decode: (String) -> String
) {
    var mode by remember { mutableStateOf(true) } // true = encode
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }

    val ctx = LocalContext.current

    fun process() {
        output = if (mode) encode(input) else decode(input)
    }

    // Mode selector — dossier Q4 / mockup tools/*base64* pixel-tab pattern: a spring-animated
    // sliding accent fill (PixelTabSwitch), not a plain two-chip toggle. Per the mockup's legend,
    // switching tabs must NOT auto-reprocess the existing input — the user re-triggers processing
    // by editing the input or using Swap, so [process] is deliberately NOT called here.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        PixelTabSwitch(
            leftLabel = encodeLabel,
            rightLabel = decodeLabel,
            leftSelected = mode,
            onSelectLeft = { mode = true },
            onSelectRight = { mode = false }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Input
    Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it; process() },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 8,
        placeholder = { Text("Paste or type…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace, fontSize = 13.sp
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Output
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Output", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (output.isNotBlank()) {
            Row {
                // Swap: mockup's exact semantic is "swaps output into input field AND switches
                // the active tab to the opposite mode" — not a generic two-field swap. We also
                // re-run processing immediately so the round-trip result is visible right away.
                GlassActionPill(
                    icon = Icons.Default.SwapHoriz,
                    contentDescription = "Swap output into input",
                    onClick = {
                        input = output
                        mode = !mode
                        process()
                    },
                    tint = MaterialTheme.colorScheme.primary,
                    size = 28.dp,
                    iconSize = 14.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                GlassActionPill(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Copy output",
                    onClick = { copyToClipboard(ctx, output) },
                    tint = MaterialTheme.colorScheme.primary,
                    size = 28.dp,
                    iconSize = 14.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                GlassActionPill(
                    icon = Icons.Default.Clear,
                    contentDescription = "Clear",
                    onClick = { input = ""; output = "" },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 28.dp,
                    iconSize = 14.dp,
                )
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = output.ifBlank { "Output will appear here…" },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (output.isNotBlank()) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Reusable spring-animated dual pixel-tab mode switch (dossier Q4 / mockup
 * tools/tools-section-with-independate-tools-*.html Screen 3's Base64 pattern, also applied to
 * AES's Encrypt/Decrypt tabs per the same mockup's legend). Unlike [ModeChip] (a plain two-chip
 * toggle used by non-reversible tools), this renders a single sliding accent-filled pill that
 * animates between the two labels via [com.example.ui.theme.scaledSpring] so it honours
 * [com.example.ui.theme.LocalAnimationsEnabled] / duration-scale gating like the rest of the app.
 */
@Composable
private fun PixelTabSwitch(
    leftLabel: String,
    rightLabel: String,
    leftSelected: Boolean,
    onSelectLeft: () -> Unit,
    onSelectRight: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        val tabWidth = maxWidth / 2
        val fillOffset by animateDpAsState(
            targetValue = if (leftSelected) 0.dp else tabWidth,
            animationSpec = com.example.ui.theme.scaledSpring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "pixelTabFill"
        )
        Box(
            modifier = Modifier
                .offset(x = fillOffset)
                .width(tabWidth)
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Row(modifier = Modifier.width(tabWidth * 2)) {
            PixelTabLabel(leftLabel, leftSelected, Modifier.width(tabWidth)) { onSelectLeft() }
            PixelTabLabel(rightLabel, !leftSelected, Modifier.width(tabWidth)) { onSelectRight() }
        }
    }
}

@Composable
private fun PixelTabLabel(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        fontSize = 11.sp,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable private fun Base64Workspace() = ReversibleWorkspace(
    "Base64", "Encode", "Decode",
    ToolsRepository::encodeBase64, ToolsRepository::decodeBase64
)

@Composable private fun UrlEncoderWorkspace() = ReversibleWorkspace(
    "URL Encode", "Encode", "Decode",
    ToolsRepository::urlEncode, ToolsRepository::urlDecode
)

@Composable private fun HexConverterWorkspace() = ReversibleWorkspace(
    "Hex", "To Hex", "From Hex",
    ToolsRepository::textToHex, ToolsRepository::hexToText
)

@Composable private fun HtmlEntityWorkspace() = ReversibleWorkspace(
    "HTML Entity", "Encode", "Decode",
    ToolsRepository::htmlEntityEncode, ToolsRepository::htmlEntityDecode
)

// ═══════════════════════════════════════════════════════════════════
// Morse Code — domain-specific
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun MorseCodeWorkspace() {
    val morseMap = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---",
        '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.", ' ' to "/"
    )
    val reverseMap = morseMap.entries.associate { (k, v) -> v to k }

    fun encode(s: String) = s.uppercase().map { morseMap[it] ?: "?" }.joinToString(" ")
    fun decode(s: String) = s.trim().split("\\s+".toRegex()).joinToString("") { reverseMap[it]?.toString() ?: "?" }

    ReversibleWorkspace("Morse Code", "To Morse", "From Morse", ::encode, ::decode)
}

// ═══════════════════════════════════════════════════════════════════
// XOR Cipher
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun XorCipherWorkspace() {
    var key by remember { mutableStateOf("secret") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    fun xorOp(s: String, k: String) = s.toByteArray().mapIndexed { i, b ->
        (b.toInt() xor k[i % k.length].code).toChar()
    }.joinToString("")

    Column {
        modeRow("XOR Cipher")

        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("Cipher Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = input, onValueChange = { input = it; output = xorOp(input, key) },
            modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
            placeholder = { Text("Text to encrypt/decrypt…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Output", output, ctx)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Hash tools
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun HashWorkspace(algorithm: String) {
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    val result = remember(input) {
        when (algorithm) {
            "SHA-256" -> ToolsRepository.hashSha256(input)
            "SHA-1"   -> ToolsRepository.hashSha1(input)
            "MD5"     -> ToolsRepository.hashMd5(input)
            else      -> ""
        }
    }

    Column {
        modeRow("$algorithm Hash")

        Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
            placeholder = { Text("Text to hash…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Digest", result, ctx)
    }
}

@Composable
private fun HmacWorkspace() {
    var input by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val result = remember(input, key) {
        if (input.isBlank() || key.isBlank()) ""
        else try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            mac.doFinal(input.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "Error computing HMAC" }
    }

    Column {
        modeRow("HMAC-SHA256")
        OutlinedTextField(value = key, onValueChange = { key = it },
            label = { Text("Secret Key") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors())
        Spacer(modifier = Modifier.height(12.dp))
        Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("HMAC Digest", result, ctx)
    }
}

@Composable
private fun Crc32Workspace() {
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val result = remember(input) {
        if (input.isBlank()) ""
        else {
            val crc = java.util.zip.CRC32()
            crc.update(input.toByteArray())
            "%08x".format(crc.value)
        }
    }
    Column {
        modeRow("CRC32 Checksum")
        Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
            placeholder = { Text("Data to checksum…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("CRC32", result, ctx)
    }
}

// ═══════════════════════════════════════════════════════════════════
// AES Cipher
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun AesCipherWorkspace() {
    var mode by remember { mutableStateOf("CBC") }
    var key by remember { mutableStateOf("") }
    var iv by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var encrypt by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val modes = listOf("CBC", "GCM", "CTR", "ECB")
    var modeExpanded by remember { mutableStateOf(false) }

    fun process() {
        output = if (encrypt)
            ToolsRepository.aesEncrypt(input, key, iv.ifBlank { "1234567890123456" }, mode)
        else
            ToolsRepository.aesDecrypt(input, key, iv.ifBlank { "1234567890123456" }, mode)
    }

    Column {
        modeRow("AES Cipher")

        // Mode selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(8.dp))
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { modeExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mode, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                    modes.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { mode = m; modeExpanded = false; process() })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Encrypt/Decrypt — dossier Q4 pixel-tab pattern, applied here per the mockup legend
        // ("this pattern also covers AES"). Switching does NOT auto-reprocess; the user
        // re-triggers via editing input, changing key/IV, or the swap action below.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PixelTabSwitch(
                leftLabel = "Encrypt",
                rightLabel = "Decrypt",
                leftSelected = encrypt,
                onSelectLeft = { encrypt = true },
                onSelectRight = { encrypt = false }
            )
            Spacer(modifier = Modifier.weight(1f))
            GlassActionPill(
                icon = Icons.Default.AutoAwesome,
                contentDescription = "Random key",
                onClick = { key = randomHex(32); process() },
                tint = MaterialTheme.colorScheme.primary,
                size = 32.dp,
                iconSize = 16.dp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(value = key, onValueChange = { key = it; process() },
            label = { Text("Key (16/24/32 hex chars)") }, singleLine = true,
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(if (keyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)
                }
            },
            visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors())

        if (mode != "ECB") {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = iv, onValueChange = { iv = it; process() },
                label = { Text("IV / Nonce") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors())
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("${if (encrypt) "Plaintext" else "Ciphertext (Base64)"}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = input, onValueChange = { input = it; process() },
            modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 6,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp))

        Spacer(modifier = Modifier.height(12.dp))
        // Swap: puts the ciphertext/plaintext output back into Input and flips Encrypt/Decrypt,
        // then reprocesses so the round-trip result is visible immediately (same semantic as the
        // mockup's Base64 swap action).
        outputBlock(if (encrypt) "Ciphertext" else "Plaintext", output, ctx, onSwap = {
            input = output
            encrypt = !encrypt
            process()
        })
    }
}

// ═══════════════════════════════════════════════════════════════════
// Formatters
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun JsonFormatterWorkspace() {
    var input by remember { mutableStateOf("") }
    var indent by remember { mutableIntStateOf(2) }
    var minified by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val result = remember(input, indent, minified) {
        when {
            input.isBlank() -> ""
            minified -> try { org.json.JSONObject(input).toString() } catch (_: Exception) { input }
            else -> ToolsRepository.formatJson(input, indent)
        }
    }
    Column {
        modeRow("JSON Formatter")
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChip("Pretty", !minified) { minified = false }
            Spacer(modifier = Modifier.width(6.dp))
            ModeChip("Minify", minified) { minified = true }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12,
            placeholder = { Text("{ \"key\": \"value\" }…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Result", result, ctx)
    }
}

@Composable
private fun SqlFormatterWorkspace() {
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val result = remember(input) { if (input.isBlank()) "" else ToolsRepository.formatSql(input) }
    Column {
        modeRow("SQL Formatter")
        Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10,
            placeholder = { Text("SELECT * FROM table WHERE…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Formatted", result, ctx)
    }
}

// ═══════════════════════════════════════════════════════════════════
// IP Calculator
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun IpCalculatorWorkspace() {
    var cidr by remember { mutableStateOf("192.168.1.0/24") }
    val ctx = LocalContext.current
    val result = remember(cidr) { if (cidr.isBlank()) "" else ToolsRepository.calculateSubnet(cidr) }
    Column {
        modeRow("IPv4 Subnet Calculator")
        OutlinedTextField(value = cidr, onValueChange = { cidr = it },
            label = { Text("CIDR (e.g. 10.0.0.0/8)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors())
        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Subnet Details", result, ctx)
    }
}

// ═══════════════════════════════════════════════════════════════════
// JWT Decoder
// ═══════════════════════════════════════════════════════════════════

// v6.0 lane B (manifest §tools-workspace): parsed JWT segments for the structured
// header/payload/signature layout.
private data class JwtParts(val header: String, val payload: String, val signature: String, val valid: Boolean)

@Composable
private fun JwtDecoderWorkspace() {
    var token by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    // v6.0 lane B (manifest §tools-workspace): parse JWT segments locally for the
    // header/payload/signature blocks. ToolsRepository.decodeJwt returns a flat
    // string; we split the token here to render the structured layout with alg,
    // iss, and exp tags matching the approved mockup.
    val parts = remember(token) {
        if (token.isBlank() || token.split(".").size != 3) {
            JwtParts("", "", "", false)
        } else {
            runCatching {
                val segs = token.split(".")
                fun decode(s: String): String {
                    val pad = s + "=".repeat((4 - s.length % 4) % 4)
                    return String(java.util.Base64.getUrlDecoder().decode(pad), Charsets.UTF_8)
                }
                JwtParts(decode(segs[0]), decode(segs[1]), segs[2], true)
            }.getOrDefault(JwtParts("", "", "", false))
        }
    }

    // Extract alg and exp from the decoded JSON for the tag badges
    val alg = remember(parts.header) {
        Regex("\"alg\"\\s*:\\s*\"([^\"]+)\"").find(parts.header)?.groupValues?.getOrNull(1) ?: ""
    }
    val iss = remember(parts.payload) {
        Regex("\"iss\"\\s*:\\s*\"([^\"]+)\"").find(parts.payload)?.groupValues?.getOrNull(1)
    }
    val expRaw = remember(parts.payload) {
        Regex("\"exp\"\\s*:\\s*(\\d+)").find(parts.payload)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
    val expValid = remember(expRaw) {
        expRaw != null && expRaw > System.currentTimeMillis() / 1000
    }

    val prettyHeader = remember(parts.header) {
        if (parts.header.isBlank()) "" else ToolsRepository.formatJson(parts.header)
    }
    val prettyPayload = remember(parts.payload) {
        if (parts.payload.isBlank()) "" else ToolsRepository.formatJson(parts.payload)
    }

    Column {
        modeRow("JWT Decoder")
        Text(
            "Paste a JWT token to decode its header, payload, and signature.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Input field with paste button (manifest §tools-workspace: InputBlock)
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            placeholder = { Text("eyJhbGciOiJIUzI1NiJ9.eyJzdWIi…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            trailingIcon = {
                IconButton(onClick = {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.primaryClip?.getItemAt(0)?.text?.let { token = it.toString() }
                }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.ContentCopy, "Paste",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            },
            colors = fieldColors(),
            shape = RoundedCornerShape(12.dp)
        )

        if (!parts.valid && token.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Invalid JWT — expected 3 dot-separated segments.",
                fontSize = 11.sp,
                color = Color(0xFFEF4444)
            )
        }

        if (parts.valid) {
            Spacer(modifier = Modifier.height(12.dp))

            // Header block with alg badge (manifest §tools-workspace: JwtHeaderBlock)
            JwtOutputBlock(
                title = "Header",
                content = prettyHeader,
                tagText = if (alg.isNotEmpty()) alg else null,
                tagColor = Color(0xFFA78BFA),
                onCopy = { copyToClipboard(ctx, prettyHeader) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Payload block with iss/exp tags (manifest §tools-workspace: JwtPayloadBlock)
            JwtOutputBlock(
                title = "Payload · Claims",
                content = prettyPayload,
                issTag = iss,
                expValid = expValid,
                onCopy = { copyToClipboard(ctx, prettyPayload) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Signature hex bar (manifest §tools-workspace: SignatureBar)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Signature",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = parts.signature,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "HMAC-SHA256",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "3 segments detected · header & payload are Base64URL decoded · signature is HMAC of the first two segments.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Generators
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun GeneratorWorkspace(
    title: String,
    buttonLabel: String,
    generate: () -> String
) {
    var result by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    Column {
        modeRow(title)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { result = generate() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(buttonLabel, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { result = "" },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.Clear, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear", color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Generated", result, ctx)
    }
}

private fun uuidGen() = ToolsRepository.generateUuid()
private fun randomStringGen() = ToolsRepository.generateRandomString(32)
private fun loremGen() = ToolsRepository.generateLoremIpsum(2)

// ═══════════════════════════════════════════════════════════════════
// Custom tools (E1) — every custom tool used to dead-end in a placeholder
// workspace stub even though ToolsRepository.executeToolFunction already
// routed custom-tool ids to a real regex-replace/template implementation.
// This wires that up.
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CustomToolWorkspace(toolId: String) {
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val output = remember(toolId, input) { ToolsRepository.executeToolFunction(toolId, input) }

    Column {
        modeRow("Custom Tool")
        Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 8,
            placeholder = { Text("Paste or type…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Output", output, ctx)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Shared helpers
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun modeRow(title: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun outputBlock(label: String, value: String, ctx: Context, onSwap: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNotBlank()) {
            Row {
                if (onSwap != null) {
                    GlassActionPill(
                        icon = Icons.Default.SwapHoriz,
                        contentDescription = "Swap output into input",
                        onClick = onSwap,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 28.dp,
                        iconSize = 14.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                GlassActionPill(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Copy output",
                    onClick = { copyToClipboard(ctx, value) },
                    tint = MaterialTheme.colorScheme.primary,
                    size = 28.dp,
                    iconSize = 14.dp,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    ) {
        Text(
            text = value.ifBlank { "Output will appear here…" },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (value.isNotBlank()) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("requestr tool output", text))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)

private fun randomHex(len: Int): String =
    (1..len).map { "0123456789abcdef".random() }.joinToString("")

// ═══════════════════════════════════════════════════════════════════
// v6.0 lane B (manifest §tools-workspace): JWT output block — surface card
// with title row (badge tags) + pretty-printed JSON code + copy button.
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun JwtOutputBlock(
    title: String,
    content: String,
    tagText: String? = null,
    tagColor: Color = MaterialTheme.colorScheme.primary,
    issTag: String? = null,
    expValid: Boolean? = null,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Title row with tag badges
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (tagText != null) {
                        JwtTag(text = tagText, color = tagColor)
                    }
                    if (issTag != null) {
                        JwtTag(text = "iss: $issTag", color = Color(0xFF06B6D4))
                    }
                    if (expValid != null) {
                        JwtTag(
                            text = if (expValid) "exp valid" else "exp expired",
                            color = if (expValid) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }
                }
            }

            // Code block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = content,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp
                )
            }

            // Copy button
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable { onCopy() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ContentCopy, "Copy",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            "Copy Payload",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JwtTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
