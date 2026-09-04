package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.persistence.PersistenceHealth

/**
 * S2 task 17: the user-visible side of the persistence failure table
 * ([PersistenceHealth.Snapshot.headline]). Renders nothing while everything is healthy; one
 * compact line otherwise -- data-loss states (dropped/failed writes, destructive wipe, failed
 * migration) in the error colour, recoverable/informational ones (restored from backup, backup
 * failed, quota pause) in the tertiary colour. Never invents a state: the text is the snapshot's.
 */
@Composable
fun PersistenceHealthBanner(snapshot: PersistenceHealth.Snapshot, modifier: Modifier = Modifier) {
    val headline = snapshot.headline ?: return
    val severe = snapshot.hasDataLoss
    val tint = if (severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = if (severe) "Data loss" else "Persistence notice",
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = headline,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
