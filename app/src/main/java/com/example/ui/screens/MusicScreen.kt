package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.models.MusicTrackItem
import com.example.data.repository.MusicRepository
import com.example.LocalBottomOverlayHeight
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.DriveParallaxFrom
import com.example.ui.components.GlassActionPill
import com.example.ui.components.GlassCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.TrackArtworkView
import com.example.ui.components.rememberAppHapticFeedback
import com.example.ui.theme.animateStateColor
import com.example.ui.theme.scaledSpring
import kotlinx.coroutines.launch

@Composable
fun MusicScreen(
    onBack: (() -> Unit)? = null,
    onMiniSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHapticFeedback()

    val tracks by MusicRepository.tracks.collectAsState()
    val currentTrack by MusicRepository.currentTrack.collectAsState()
    val isPlaying by MusicRepository.isPlaying.collectAsState()
    val positionSec by MusicRepository.currentPositionSec.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var isScanning by remember { mutableStateOf(false) }

    // v6.0 (plan task 13, music-permission mockup): the music library needs READ_MEDIA_AUDIO
    // (API 33+) or READ_EXTERNAL_STORAGE (below). When the permission is not granted we render
    // the approved permission-denied state instead of attempting a scan that would silently
    // return nothing. Follows the OnboardingScreen permission pattern.
    val musicPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasMusicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, musicPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    var permissionGranted by remember { mutableStateOf(hasMusicPermission()) }
    var permissionRequested by remember { mutableStateOf(false) }

    val musicPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permissionRequested = true
        if (granted) {
            scope.launch { MusicRepository.loadLocalLibrary(context) }
        }
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
    }

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
    // even after READ_MEDIA_AUDIO was granted. Now we scan once on first composition, but
    // only when the permission is already granted (otherwise we show the permission state).
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && tracks.isEmpty()) {
            MusicRepository.loadLocalLibrary(context)
        }
    }

    var pendingDeleteTrack by remember { mutableStateOf<MusicTrackItem?>(null) }

    // The playlist is Music's primary scroll container: bind it to the app-wide parallax layer.
    val listState = rememberLazyListState()
    DriveParallaxFrom(listState)
    // `music` scrolls under the nav pill (ROUTES_SCROLLING_UNDER_PILL): the list and the
    // snackbar reserve the overlay height themselves.
    val bottomOverlay = LocalBottomOverlayHeight.current

    // Transparent root so the ParallaxBackground hosted in MainActivity shows through.
    Box(modifier = Modifier.fillMaxSize()) {
        // Main content column: Locked Big Player + Scrollable Playlist underneath
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 58.dp) // Space for top floating SectionHeader
        ) {
            if (!permissionGranted) {
                // ─── PERMISSION-DENIED STATE (music-permission mockup) ──────────────
                PermissionDeniedState(
                    requestedBefore = permissionRequested,
                    onOpenSettings = { openAppSettings() },
                    onGrant = { musicPermLauncher.launch(musicPerm) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else if (tracks.isEmpty()) {
                // ─── EMPTY STATE (music-empty mockup) ───────────────────────────────
                EmptyTracksPlaceholder(
                    onScan = { rescanLibrary() }
                )
            } else {
            // ─── 1. LOCKED BIG HERO PLAYER ──────────────────────────────────────
            LockedBigPlayerCard(
                track = currentTrack,
                isPlaying = isPlaying,
                positionSec = positionSec,
                onTogglePlay = {
                    haptics(HapticFeedbackType.LongPress)
                    MusicRepository.togglePlayPause()
                },
                onPrev = {
                    haptics(HapticFeedbackType.LongPress)
                    MusicRepository.prevTrack()
                },
                onNext = {
                    haptics(HapticFeedbackType.LongPress)
                    MusicRepository.nextTrack()
                },
                onSeek = { MusicRepository.seekTo(it) },
                onShuffle = {
                    haptics(HapticFeedbackType.LongPress)
                    scope.launch {
                        MusicRepository.pickRandomTrack(context)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // ─── 2. PLAYLIST HEADER: EYE ICON + TOTAL TRACKS ───────────────────
            PlaylistControlHeader(
                totalTracks = tracks.size,
                onShuffleAll = {
                    haptics(HapticFeedbackType.LongPress)
                    scope.launch {
                        MusicRepository.pickRandomTrack(context)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // ─── 3. SCROLLABLE SONG LIST (UNDERWAY OF LOCKED PLAYER) ─────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = bottomOverlay + 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    val isCurrent = track.id == currentTrack.id

                    // Swipe-to-remove asks for confirmation before deleting instead of
                    // destroying the row on a stray swipe (backup Fix #35).
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                pendingDeleteTrack = track
                            }
                            false
                        }
                    )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TrackListItemRow(
                                track = track,
                                index = index + 1,
                                isCurrent = isCurrent,
                                isPlaying = isPlaying && isCurrent,
                                onClick = {
                                    haptics(HapticFeedbackType.LongPress)
                                    MusicRepository.selectTrack(track.id)
                                }
                            )
                        }
                    }
            }
            }
        }

        // Top Floating Header
        SectionHeader(
            title = "Music Lounge",
            onBack = onBack,
            onMiniSettings = onMiniSettings,
            actions = {
                GlassActionPill(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Scan Audio Files",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { rescanLibrary() }
                )
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomOverlay + 16.dp)
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

// ─── LOCKED BIG HERO PLAYER CARD ──────────────────────────────────────────────

@Composable
private fun LockedBigPlayerCard(
    track: MusicTrackItem,
    isPlaying: Boolean,
    positionSec: Int,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Ambient decorative loop: runs only at full motion. REDUCED shortens a loop's period,
    // which makes it faster and more distracting, so reduced-motion turns it off entirely and
    // the resting values below take over. See rememberAmbientMotionEnabled().
    val animationsEnabled = com.example.ui.theme.rememberAmbientMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "playerWave")

    val waveAnim by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAnim"
    )

    val vinylRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinyl"
    )

    val currentWave = if (animationsEnabled && isPlaying) waveAnim else 0.4f
    val currentRotation = if (animationsEnabled && isPlaying) vinylRotation else 0f

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    GlassCard(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            ),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Upper Row: Artwork Thumbnail (with vinyl badge) + Track Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vinyl Animated Artwork Container
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    TrackArtworkView(
                        track = track,
                        size = 80.dp,
                        shape = RoundedCornerShape(18.dp),
                        iconSize = 36.dp
                    )

                    // Overlay Vinyl Center Spinner when playing
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(currentRotation)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Track Title, Artist, & Source Badge
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = track.title.ifBlank { "No Track Selected" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = track.artist.ifBlank { "Tap any song to play" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = track.source.label.take(16),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (isPlaying) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.18f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "PLAYING",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Waveform Audio Graphic
            val activeColor = MaterialTheme.colorScheme.primary
            val inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val barCount = 32
                val barWidth = (size.width / barCount) - 3f
                val progressRatio = if (track.durationSec > 0) {
                    (positionSec.toFloat() / track.durationSec.toFloat()).coerceIn(0f, 1f)
                } else 0f

                for (i in 0 until barCount) {
                    val heightMultiplier = if (isPlaying) {
                        ((kotlin.math.sin(i.toDouble() * 0.45 + currentWave.toDouble() * 3.2) + 1.2) / 2.2).toFloat()
                    } else {
                        0.18f
                    }
                    val barHeight = (size.height * heightMultiplier).coerceAtLeast(3f)
                    val x = i * (barWidth + 3f)
                    val y = (size.height - barHeight) / 2f
                    val isPast = (i.toFloat() / barCount.toFloat()) <= progressRatio

                    drawRoundRect(
                        color = if (isPast) activeColor else inactiveColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
            }

            // Seekbar
            val maxDuration = track.durationSec.coerceAtLeast(1).toFloat()
            val currentPos = positionSec.coerceIn(0, track.durationSec.coerceAtLeast(1)).toFloat()

            Slider(
                value = currentPos,
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..maxDuration,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
            )

            // Timestamps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%02d:%02d".format(positionSec / 60, positionSec % 60),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "%02d:%02d".format(track.durationSec / 60, track.durationSec % 60),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Playback Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onShuffle,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onPrev,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Center Main Play/Pause Button
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(if (isPlaying && animationsEnabled) pulseScale else 1.0f)
                        .shadow(6.dp, CircleShape, spotColor = MaterialTheme.colorScheme.primary)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = { /* Repeat / Loop */ },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = "Audio Track Mode",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ─── PLAYLIST HEADER WITH EYE ICON + TOTAL TRACKS ────────────────────────────

@Composable
private fun PlaylistControlHeader(
    totalTracks: Int,
    onShuffleAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Eye icon strictly placed left of total tracks as requested
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.RemoveRedEye,
                contentDescription = "Active Tracks",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )

            Text(
                text = "$totalTracks Tracks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Shuffle / Quick Action pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    RoundedCornerShape(12.dp)
                )
                .clickable(onClick = onShuffleAll)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Random Pick",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ─── PLAYLIST TRACK ITEM ROW (DISTINCT CURRENT TRACK STYLING) ─────────────────

@Composable
private fun TrackListItemRow(
    track: MusicTrackItem,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    // "Now playing" state animates on a gated spring; the theme colours resolve instantly.
    val stateSpec = scaledSpring<Float>(dampingRatio = 0.8f, stiffness = 300f)
    val bgAnim by animateStateColor(
        active = isCurrent,
        activeColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        inactiveColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        animationSpec = stateSpec,
        label = "trackBg"
    )

    val borderAnim by animateStateColor(
        active = isCurrent,
        activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        inactiveColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        animationSpec = stateSpec,
        label = "trackBorder"
    )

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) {
                    Modifier.shadow(4.dp, RoundedCornerShape(16.dp), spotColor = MaterialTheme.colorScheme.primary)
                } else Modifier
            ),
        backgroundColor = bgAnim,
        borderColor = borderAnim,
        cornerRadius = 16.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Index or Equalizer Bar Indicator
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrent && isPlaying) {
                        MiniLiveEqualizer()
                    } else {
                        Text(
                            text = "%02d".format(index),
                            fontSize = 11.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Album Thumbnail
                TrackArtworkView(
                    track = track,
                    size = 42.dp,
                    shape = RoundedCornerShape(10.dp),
                    iconSize = 18.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = track.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Duration & Playing Tag
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "NOW" else "PAUSED",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = "%02d:%02d".format(track.durationSec / 60, track.durationSec % 60),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ─── MINI LIVE EQUALIZER ANIMATION ───────────────────────────────────────────

@Composable
fun MiniLiveEqualizer(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    // Ambient decorative loop: runs only at full motion. REDUCED shortens a loop's period,
    // which makes it faster and more distracting, so reduced-motion turns it off entirely and
    // the resting values below take over. See rememberAmbientMotionEnabled().
    val animationsEnabled = com.example.ui.theme.rememberAmbientMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "miniEq")

    val h1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq3"
    )

    val finalH1 = if (animationsEnabled) h1 else 8f
    val finalH2 = if (animationsEnabled) h2 else 12f
    val finalH3 = if (animationsEnabled) h3 else 6f

    Row(
        modifier = modifier.size(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(finalH1.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(tint)
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(finalH2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(tint)
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(finalH3.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(tint)
        )
    }
}

// ─── EMPTY TRACKS PLACEHOLDER ────────────────────────────────────────────────

@Composable
private fun EmptyTracksPlaceholder(onScan: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "No audio tracks detected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Add audio files to your device or tap scan to refresh your library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onScan)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Scan Device Storage",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─── PERMISSION-DENIED STATE (music-permission mockup, DESIGN-SYSTEM §15) ─────

@Composable
private fun PermissionDeniedState(
    requestedBefore: Boolean,
    onOpenSettings: () -> Unit,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier
) {
    val errorColor = MaterialTheme.colorScheme.error
    GlassCard(
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 72dp red-tinted warning icon tile.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(errorColor.copy(alpha = 0.1f))
                    .border(1.dp, errorColor.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = errorColor,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Music access permission required",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "requestr needs storage access to scan and play your local audio files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Only show the "previously denied" hint after the system prompt has been
            // shown at least once (requestedBefore), matching the mockup's hint.
            if (requestedBefore) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "You previously denied this permission. Open Settings to grant access manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
            ) {
                // Open Settings — primary (gear icon).
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = "Open system settings" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Open Settings",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Grant Permission — secondary.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        .clickable(onClick = onGrant)
                        .semantics { contentDescription = "Grant permission" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Grant Permission",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
