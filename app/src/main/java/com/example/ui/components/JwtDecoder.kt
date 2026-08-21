package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Base64

@Composable
fun JwtDecoder(token: String) {
    if (!token.contains(".") || token.split(".").size != 3) return

    val parts = token.split(".")
    val header = decodeJwtPart(parts[0])
    val payload = decodeJwtPart(parts[1])
    val signature = parts[2]
    val now = System.currentTimeMillis() / 1000
    val exp = extractExp(payload)

    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Compact JWT pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B))
                .clickable { expanded = !expanded }
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("JWT", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                "${if (expired(exp, now)) "⚠ EXPIRED " else ""}${header.take(20)}...",
                color = if (expired(exp, now)) Color(0xFFEF4444) else Color(0xFF94A3B8),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
            Text(if (expanded) "▲" else "▼", color = Color(0xFF64748B), fontSize = 12.sp)
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            JwtSection("HEADER", header) { formatJson(it) }
            JwtSection("PAYLOAD", payload) { formatJson(it) }
            JwtSection("SIGNATURE", signature.take(20) + "...") { it }
        }
    }
}

@Composable
private fun JwtSection(title: String, content: String, formatter: (String) -> String) {
    val bg = when (title) {
        "HEADER" -> Color(0xFF1E3A5F)
        "PAYLOAD" -> Color(0xFF14532D)
        else -> Color(0xFF4A1D6D)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp), color = bg.copy(alpha = 0.3f),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF94A3B8))
            Text(formatter(content), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFE2E8F0))
        }
    }
}

private fun decodeJwtPart(b64: String): String = try {
    val padded = b64.padEnd(b64.length + (4 - b64.length % 4) % 4, '=')
    String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
} catch (_: Exception) { b64 }

private fun extractExp(payload: String): Long = try {
    val re = Regex("\"exp\"\\s*:\\s*(\\d+)")
    re.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: -1
} catch (_: Exception) { -1 }

private fun expired(exp: Long, now: Long): Boolean = exp > 0 && now > exp

private fun formatJson(raw: String): String {
    return try {
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false
        for (ch in raw) {
            when {
                escape -> { sb.append(ch); escape = false }
                ch == '\\' && inString -> { sb.append(ch); escape = true }
                ch == '"' -> { sb.append(ch); inString = !inString }
                ch in "{[," && !inString -> { sb.append(ch); sb.append('\n'); indent++; repeat(indent * 2) { sb.append(' ') } }
                ch in "}]" && !inString -> { sb.append('\n'); indent--; repeat(indent * 2) { sb.append(' ') }; sb.append(ch) }
                ch == ',' && !inString -> { sb.append(ch); sb.append('\n'); repeat(indent * 2) { sb.append(' ') } }
                else -> sb.append(ch)
            }
        }
        sb.toString()
    } catch (_: Exception) { raw }
}
