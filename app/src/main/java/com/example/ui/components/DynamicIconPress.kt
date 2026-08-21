package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Dynamic pill-outline press interaction for icon buttons.
 * On press: rounded capsule outline smoothly expands around the icon
 * with spring-based scale+fade. No gray ripple.
 */
@Composable
fun DynamicIconPress(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable (pressed: Boolean) -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        if (isPressed) 1.08f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "iconPressScale"
    )

    val borderAlpha by animateFloatAsState(
        if (isPressed) 0.5f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "iconPressBorder"
    )

    val bgAlpha by animateFloatAsState(
        if (isPressed) 0.12f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "iconPressBg"
    )

    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (isPressed) 12.dp else 6.dp))
            .background(if (isPressed) accent.copy(alpha = bgAlpha) else Color.Transparent)
            .border(
                width = if (isPressed) 1.dp else 0.dp,
                color = accent.copy(alpha = borderAlpha),
                shape = RoundedCornerShape(if (isPressed) 12.dp else 6.dp)
            )
            .scale(scale)
            .clickable(onClick = onClick)
            .padding(if (isPressed) 2.dp else 0.dp)
    ) {
        content(isPressed)
    }
}
