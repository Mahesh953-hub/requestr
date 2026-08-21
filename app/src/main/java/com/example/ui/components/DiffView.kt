package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.analysis.TextDiffer

/**
 * Diff view composable — renders side-by-side or inline diff of two texts.
 * Used by #28 (Diff view) for comparing request/response bodies.
 */
@Composable
fun DiffView(
    oldText: String,
    newText: String,
    modifier: Modifier = Modifier,
    title: String = "Diff",
) {
    val hunks = androidx.compose.runtime.remember(oldText, newText) {
        TextDiffer.diff(oldText, newText)
    }
    val (added, removed, unchanged) = TextDiffer.summarize(hunks)

    Column(modifier = modifier.fillMaxWidth()) {
        // Summary header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("+$added", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("-$removed", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("=$unchanged", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Diff content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(8.dp)
        ) {
            hunks.forEach { hunk ->
                val bgColor = when (hunk.op) {
                    TextDiffer.Op.INSERT -> Color(0xFF10B981).copy(alpha = 0.1f)
                    TextDiffer.Op.DELETE -> Color(0xFFEF4444).copy(alpha = 0.1f)
                    TextDiffer.Op.EQUAL -> Color.Transparent
                }
                val textColor = when (hunk.op) {
                    TextDiffer.Op.INSERT -> Color(0xFF10B981)
                    TextDiffer.Op.DELETE -> Color(0xFFEF4444)
                    TextDiffer.Op.EQUAL -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val prefix = when (hunk.op) {
                    TextDiffer.Op.INSERT -> "+"
                    TextDiffer.Op.DELETE -> "-"
                    TextDiffer.Op.EQUAL -> " "
                }
                val lineNum = if (hunk.op == TextDiffer.Op.DELETE) hunk.oldLineNum else hunk.newLineNum
                val content = if (hunk.op == TextDiffer.Op.DELETE) hunk.oldLine else hunk.newLine

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        "${lineNum.toString().padStart(4)} $prefix ",
                        color = textColor.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        content,
                        color = textColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
