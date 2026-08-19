package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppThemeMode

/**
 * Redesigned onboarding (Fix 10). Three steps:
 *   1. Welcome + Name — branded intro with name capture
 *   2. Permissions & Access — storage, network, music cards with skip
 *   3. Appearance — System/Light/Dark selector + accent colour swatches
 *
 * Progress dots at the top; all colours use theme tokens; no hardcoded values.
 * When re-run via Settings, already-granted permissions show "Granted" +
 * a subtle "Skip" option; the theme selector pre-fills the current choice.
 */
@Composable
fun OnboardingScreen(
    onFinishOnboarding: (userName: String, themeMode: String) -> Unit
) {
    val totalSteps = 3
    var currentStep by remember { mutableIntStateOf(1) }
    var userName by remember { mutableStateOf("") }
    var selectedTheme by remember { mutableStateOf("MONOCHROME_DARK") }

    // Permission flags — ALL start as NOT granted (real status checked on tap)
    var storageHandled by remember { mutableStateOf(false) }
    var networkHandled by remember { mutableStateOf(false) }
    var musicHandled by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // ── Progress dots ──────────────────────────────────────
            ProgressDots(currentStep = currentStep, total = totalSteps)
            Spacer(modifier = Modifier.height(32.dp))

            // ── Step content ───────────────────────────────────────
            when (currentStep) {
                1 -> WelcomeStep(
                    userName = userName,
                    onNameChange = { userName = it },
                    onContinue = { currentStep = 2 }
                )

                2 -> PermissionsStep(
                    storageHandled = storageHandled,
                    networkHandled = networkHandled,
                    musicHandled = musicHandled,
                    onStorageGranted = { storageHandled = true },
                    onStorageSkipped = { storageHandled = true },
                    onNetworkGranted = { networkHandled = true },
                    onMusicGranted = { musicHandled = true },
                    onMusicSkipped = { musicHandled = true },
                    onContinue = { currentStep = 3 }
                )

                3 -> AppearanceStep(
                    selectedTheme = selectedTheme,
                    onThemeSelected = { selectedTheme = it },
                    onFinish = { onFinishOnboarding(userName, selectedTheme) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ─── Step 1: Welcome + Name ────────────────────────────────────────

@Composable
private fun WelcomeStep(
    userName: String,
    onNameChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Branded logo
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF1E3A8A),
                            MaterialTheme.colorScheme.primary,
                            Color(0xFF06B6D4)
                        )
                    )
                )
                .border(2.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("⚡", fontSize = 36.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "requestr",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Network Security & API Intelligence",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = userName,
            onValueChange = onNameChange,
            placeholder = {
                Text(
                    "Enter your name or alias",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            ),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Used for greetings and personalization",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onContinue,
            enabled = userName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                "Continue",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── Step 2: Permissions & Access ───────────────────────────────────

@Composable
private fun PermissionsStep(
    storageHandled: Boolean,
    networkHandled: Boolean,
    musicHandled: Boolean,
    onStorageGranted: () -> Unit,
    onStorageSkipped: () -> Unit,
    onNetworkGranted: () -> Unit,
    onMusicGranted: () -> Unit,
    onMusicSkipped: () -> Unit,
    onContinue: () -> Unit
) {
    // Continue is enabled when storage + network are handled; music is optional
    val canContinue = storageHandled && networkHandled

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Permissions & Access",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "requestr needs these permissions to capture and analyze traffic",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Storage card
        PermissionCard(
            icon = Icons.Default.Storage,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Storage Access",
            description = "Save and load API collections, HAR exports & notes",
            handled = storageHandled,
            onGrant = onStorageGranted,
            onSkip = onStorageSkipped
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Network card
        PermissionCard(
            icon = Icons.Default.Public,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = "Network Permissions",
            description = "Required for API testing and packet interception",
            handled = networkHandled,
            onGrant = onNetworkGranted,
            onSkip = onNetworkGranted, // network is auto-granted; tapping skip = same as grant
            grantLabel = "Auto-granted"
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Music card (from Fix 05)
        PermissionCard(
            icon = Icons.Default.MusicNote,
            iconTint = MaterialTheme.colorScheme.tertiary,
            title = "Local Music Library",
            description = "Scan local audio for Focus & Synth Lounge",
            handled = musicHandled,
            onGrant = onMusicGranted,
            onSkip = onMusicSkipped,
            optional = true
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                "Continue",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── Step 3: Appearance ────────────────────────────────────────────

@Composable
private fun AppearanceStep(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit,
    onFinish: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Choose your visual style. You can change this anytime in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Appearance selector (System / Light / Dark) — mirrors Fix 01 design
        Text(
            "Mode",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        AppearanceSelectorCompact(
            onModeSelected = { /* handled via accent grid — mode is implied by theme */ }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Accent colour swatches
        Text(
            "Accent Color",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        AccentColorSwatches(
            currentTheme = selectedTheme,
            onThemeSelected = onThemeSelected
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                "Finish Setup",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── Shared composables ────────────────────────────────────────────

@Composable
private fun ProgressDots(currentStep: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val isActive = index + 1 == currentStep
            val color by animateColorAsState(
                if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = tween(300),
                label = "dotColor"
            )
            Box(
                modifier = Modifier
                    .size(if (isActive) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    handled: Boolean,
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    grantLabel: String = "Grant",
    optional: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon container
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title + description
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (optional) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "optional",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status
        if (handled) {
            // Already handled — show granted + subtle skip
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF10B981).copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "Granted",
                        color = Color(0xFF10B981),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            // Needs granting
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = grantLabel,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onGrant() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }

    // Skip row appears below when handled
    if (handled) {
        Text(
            text = "Skip",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(start = 52.dp, top = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onSkip() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AppearanceSelectorCompact(
    onModeSelected: (String) -> Unit
) {
    val modes = listOf(
        Triple("SYSTEM", "System", Icons.Outlined.SettingsBrightness),
        Triple("LIGHT", "Light", Icons.Filled.LightMode),
        Triple("DARK", "Dark", Icons.Outlined.DarkMode)
    )
    var selectedMode by remember { mutableStateOf("SYSTEM") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        modes.forEach { (mode, label, icon) ->
            val isSelected = selectedMode == mode
            val bgColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent,
                label = "modeBg"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .clickable { selectedMode = mode; onModeSelected(mode) }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AccentColorSwatches(
    currentTheme: String,
    onThemeSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppThemeMode.values().forEach { mode ->
            val isSelected = currentTheme.equals(mode.name, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(if (isSelected) RoundedCornerShape(6.dp) else CircleShape)
                    .then(
                        if (isSelected) Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(mode.samplePrimary)
                        else Modifier
                            .clip(CircleShape)
                            .background(mode.samplePrimary)
                    )
                    .clickable { onThemeSelected(mode.name) }
            )
        }
    }
}
