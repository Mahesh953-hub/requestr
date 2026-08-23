package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.models.MusicTrackItem

@Composable
fun TrackArtworkView(
    track: MusicTrackItem,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    iconSize: Dp = (size.value * 0.45f).dp,
    showBorder: Boolean = true
) {
    val context = LocalContext.current
    val imageModel = when {
        !track.albumArtUri.isNullOrBlank() -> Uri.parse(track.albumArtUri)
        !track.contentUri.isNullOrBlank() -> Uri.parse(track.contentUri)
        !track.filePath.isNullOrBlank() -> track.filePath
        else -> null
    }

    val fallbackGradient = Brush.linearGradient(
        listOf(
            Color(track.albumCoverHex),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        )
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(
                if (showBorder) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (imageModel != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageModel)
                    .crossfade(true)
                    .build(),
                contentDescription = "${track.title} Album Art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    DefaultFallbackBox(
                        gradient = fallbackGradient,
                        iconSize = iconSize
                    )
                },
                error = {
                    DefaultFallbackBox(
                        gradient = fallbackGradient,
                        iconSize = iconSize
                    )
                }
            )
        } else {
            DefaultFallbackBox(
                gradient = fallbackGradient,
                iconSize = iconSize
            )
        }
    }
}

@Composable
private fun DefaultFallbackBox(
    gradient: Brush,
    iconSize: Dp
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(iconSize)
        )
    }
}
