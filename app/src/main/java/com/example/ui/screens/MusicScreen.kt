package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.MusicTrackItem
import com.example.data.repository.MusicRepository
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.GlassCard
import com.example.ui.components.SectionHeader
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun MusicScreen(
    onBack: (() -> Unit)? = null,
    onMiniSettings: () -> Unit
) {
    val context = LocalContext.current
    val tracks by MusicRepository.tracks.collectAsState()
    val currentTrack by MusicRepository.currentTrack.collectAsState()
    val isPlaying by MusicRepository.isPlaying.collectAsState()
    val positionSec by MusicRepository.currentPositionSec.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }

    fun rescanLibrary() {
        if (isScanning) return
        isScanning = true
        scope.launch {
            MusicRepository.loadLocalLibrary(context)
            isScanning = false
            val count = MusicRepository.tracks.value.size
            snackbarHostState.showSnackbar(
                if (count == 0) "No audio files found on this device"
                else "Found $count track${if (count == 1) "" else "s"}"
            )
        }
    }

    // BUG FIX: Actually trigger the MediaStore scan when the screen is first composed.
    // Previously, nobody ever called loadLocalLibrary — the track list stayed empty forever
    // even after READ_MEDIA_AUDIO was granted. Now we scan once on first composition.
    LaunchedEffect(Unit) {
        if (tracks.isEmpty()) {
            MusicRepository.loadLocalLibrary(context)
        }
    }

    var pendingDeleteTrack by remember { mutableStateOf<MusicTrackItem?>(null) }

    // v3.1 B8: infinite pulse can't be duration-scaled to 0 without flickering forever,
    // so skip it entirely and hold a static value when animations are disabled.
    val animationsEnabled = com.example.ui.theme.LocalAnimationsEnabled.current
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val animatedWave by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAnim"
    )
    val waveAnim = if (animationsEnabled) animatedWave else 0.6f

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(
            title = "Focus & Synth Lounge",
            onBack = onBack,
            onMiniSettings = onMiniSettings,
            actions = {
                IconButton(onClick = { rescanLibrary() }) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Scan Audio Files",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Player Card
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Big Synth Artwork
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color(currentTrack.albumCoverHex),
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                )
                                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎧", fontSize = 54.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = currentTrack.title,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp
                        )

                        Text(
                            text = "${currentTrack.artist} · ${currentTrack.source.label}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val activeBarColor = MaterialTheme.colorScheme.primary
                        val inactiveBarColor = MaterialTheme.colorScheme.surfaceVariant

                        // Waveform Graphic
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        ) {
                            val barCount = 28
                            val barWidth = (size.width / barCount) - 4f
                            for (i in 0 until barCount) {
                                val heightMultiplier = if (isPlaying) {
                                    ((kotlin.math.sin(i.toDouble() * 0.5 + waveAnim.toDouble() * 3) + 1.2) / 2.2).toFloat()
                                } else {
                                    0.15f
                                }
                                val barHeight = (size.height * heightMultiplier).coerceAtLeast(4f)
                                val x = i * (barWidth + 4f)
                                val y = (size.height - barHeight) / 2f
                                drawRoundRect(
                                    color = if (i < barCount * (positionSec.toFloat() / currentTrack.durationSec)) activeBarColor else inactiveBarColor,
                                    topLeft = Offset(x, y),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(2f, 2f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Seekbar
                        Slider(
                            value = positionSec.toFloat(),
                            onValueChange = { MusicRepository.seekTo(it.toInt()) },
                            valueRange = 0f..currentTrack.durationSec.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("%02d:%02d".format(positionSec / 60, positionSec % 60), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("%02d:%02d".format(currentTrack.durationSec / 60, currentTrack.durationSec % 60), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Control Buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            IconButton(onClick = { MusicRepository.prevTrack() }) {
                                Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "Prev", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { MusicRepository.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            IconButton(onClick = { MusicRepository.nextTrack() }) {
                                Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }

            // Playlist Queue
            item {
                Text("Soundtrack Playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }

            items(tracks, key = { it.id }) { track ->
                val isSelected = track.id == currentTrack.id
                // Swipe-to-remove now asks for confirmation before deleting instead of
                // destroying the row on a stray swipe (Fix #35).
                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                            pendingDeleteTrack = track
                        }
                        false
                    }
                )
                androidx.compose.material3.SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                        borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        onClick = { MusicRepository.selectTrack(track.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isSelected && isPlaying) "▶" else "🎵", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(track.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                }
                            }
                            Text(
                                "%02d:%02d".format(track.durationSec / 60, track.durationSec % 60),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
    )
    }

    val trackToDelete = pendingDeleteTrack
    if (trackToDelete != null) {
        ConfirmActionDialog(
            title = "Remove track?",
            message = "\"${trackToDelete.title}\" will be removed from the playlist.",
            confirmLabel = "Remove",
            onConfirm = {
                MusicRepository.removeTrack(trackToDelete.id)
                pendingDeleteTrack = null
            },
            onDismiss = { pendingDeleteTrack = null }
        )
    }
}
