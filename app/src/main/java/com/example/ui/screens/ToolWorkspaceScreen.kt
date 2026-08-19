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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Look up a tool by id and render its domain-specific workspace. */
@Composable
fun ToolWorkspaceScreen(
    toolId: String,
    onBack: () -> Unit
) {
    val tool = remember(toolId) {
        ToolsRepository.getToolsCatalog().find { it.id == toolId }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool?.title ?: "Unknown Tool",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (tool != null) {
                    Text(
                        text = "${tool.category.displayName} · ${tool.description}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        // Workspace body
        if (tool == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tool not found", color = MaterialTheme.colorScheme.error)
            }
        } else {
            val scrollState = rememberScrollState()
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

                    else -> PlaceholderWorkspace(tool.title)
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

    // Mode selector
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeChip(encodeLabel, selected = mode) { mode = true; process() }
            ModeChip(decodeLabel, selected = !mode) { mode = false; process() }
        }
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
                IconButton(onClick = { copyToClipboard(ctx, output) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { input = ""; output = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Clear, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
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

    val hashFn: (String) -> String = remember(algorithm) {
        { ToolsRepository.formatJson(ToolsRepository::class.java.getMethod(
            "hash${algorithm.replace("-", "")}", String::class.java
        ).invoke(ToolsRepository, it) as String) }
    }

    // Simpler approach: use a when
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

        // Encrypt/Decrypt + Key
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ModeChip(if (encrypt) "Encrypt" else "Decrypt", true) { encrypt = !encrypt; process() }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { key = randomHex(32); process() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.AutoAwesome, "Random key", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
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
        outputBlock(if (encrypt) "Ciphertext" else "Plaintext", output, ctx)
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

@Composable
private fun JwtDecoderWorkspace() {
    var token by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val result = remember(token) { if (token.isBlank()) "" else ToolsRepository.decodeJwt(token) }
    Column {
        modeRow("JWT Decoder")
        Text("Paste a JWT token to decode its header, payload, and signature.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = token, onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
            placeholder = { Text("eyJhbGciOiJIUzI1NiJ9.eyJzdWIi…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            colors = fieldColors(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        outputBlock("Decoded", result, ctx)
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
// Placeholder
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PlaceholderWorkspace(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🚧", fontSize = 40.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text("$title — Coming Soon", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
private fun outputBlock(label: String, value: String, ctx: Context) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNotBlank()) {
            Row {
                IconButton(onClick = { copyToClipboard(ctx, value) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = value.ifBlank { "Output will appear here…" },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (value.isNotBlank()) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
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
