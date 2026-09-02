package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.components.RequestrWordmark
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.rememberAmbientMotionEnabled
import com.example.ui.theme.scaledSpring
import com.example.ui.theme.animateStateColor
import com.example.ui.theme.scaledTween

// ─── Onboarding entry ─────────────────────────────────────────────

@Composable
fun OnboardingScreen(
    onFinishOnboarding: (userName: String, themeMode: String) -> Unit
) {
    val settings by com.example.data.repository.SettingsRepository.settings.collectAsState()
    val totalSteps = 3
    var currentStep by remember { mutableIntStateOf(1) }
    var userName by remember { mutableStateOf("") }

    // Theme state seeded from persisted values
    val savedMode = settings.appearanceMode.uppercase().let {
        if (it in listOf("SYSTEM", "LIGHT", "DARK")) it else "SYSTEM"
    }
    var appearanceMode by remember { mutableStateOf(savedMode) }
    var selectedLightAccent by remember { mutableStateOf(settings.selectedLightTheme) }
    var selectedDarkAccent by remember { mutableStateOf(settings.selectedDarkTheme) }
    var selectedAccent by remember { mutableStateOf(settings.selectedAccent) }

    // Permissions
    val context = LocalContext.current
    val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasStorage() = ContextCompat.checkSelfPermission(context, storagePerm) == PackageManager.PERMISSION_GRANTED
    fun hasNotif(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

    var storageState by remember { mutableStateOf(if (hasStorage()) PermState.GRANTED else PermState.NOT_REQUESTED) }
    var notifState by remember { mutableStateOf(if (hasNotif()) PermState.GRANTED else PermState.NOT_REQUESTED) }
    var musicState by remember { mutableStateOf(if (hasStorage()) PermState.GRANTED else PermState.NOT_REQUESTED) }

    LaunchedEffect(Unit) {
        if (hasStorage()) {
            if (storageState != PermState.SKIPPED) storageState = PermState.GRANTED
            if (musicState != PermState.SKIPPED) musicState = PermState.GRANTED
        }
        if (hasNotif() && notifState != PermState.SKIPPED) notifState = PermState.GRANTED
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> storageState = if (granted) PermState.GRANTED else PermState.DENIED }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifState = if (granted) PermState.GRANTED else PermState.DENIED }

    val musicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> musicState = if (granted) PermState.GRANTED else PermState.DENIED }

    // Transparent root so the ParallaxBackground hosted in MainActivity shows through.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── v6.0 transplant (manifest §5.10): Dynamic Island progress pill ──
            Spacer(modifier = Modifier.height(48.dp))
            DynamicIslandProgressPill(
                currentStep = currentStep,
                totalSteps = totalSteps,
                accentColor = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            // ── Step label ─────────────────────────────────────
            Text(
                text = "$currentStep of $totalSteps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Step content with animation ────────────────────
            // Every spec below is settings-gated: with animations off these collapse to 0ms so
            // the step swaps instantly instead of sliding.
            val stepSlideSpec = scaledSpring<IntOffset>()
            val stepFadeSpec = scaledTween<Float>(300)
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(stepSlideSpec) { it * dir } + fadeIn(stepFadeSpec))
                        .togetherWith(slideOutHorizontally(stepSlideSpec) { -it * dir } + fadeOut(stepFadeSpec))
                },
                label = "step",
                modifier = Modifier.weight(1f)
            ) { step ->
                when (step) {
                    1 -> IdentityStep(
                        userName = userName,
                        onNameChange = { userName = it },
                        onContinue = { currentStep = 2 }
                    )
                    2 -> PermissionsStep(
                        storageState = storageState,
                        notifState = notifState,
                        musicState = musicState,
                        onStorageGrant = { storageLauncher.launch(storagePerm) },
                        onStorageSettings = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            })
                        },
                        onNotifGrant = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else notifState = PermState.GRANTED
                        },
                        onNotifSkip = { notifState = PermState.SKIPPED },
                        onMusicGrant = { musicLauncher.launch(storagePerm) },
                        onMusicSkip = { musicState = PermState.SKIPPED },
                        onContinue = { currentStep = 3 }
                    )
                    3 -> AppearanceStep(
                        selectedMode = appearanceMode,
                        lightAccent = selectedLightAccent,
                        darkAccent = selectedDarkAccent,
                        selectedAccent = selectedAccent,
                        onModeSelected = { mode ->
                            appearanceMode = mode
                            com.example.data.repository.SettingsRepository.updateAppearanceMode(mode)
                        },
                        onLightAccent = { name ->
                            selectedLightAccent = name
                            com.example.data.repository.SettingsRepository.updateSelectedLightTheme(name)
                            if (appearanceMode == "LIGHT") {
                                com.example.data.repository.SettingsRepository.updateThemeSelection(name)
                            }
                        },
                        onDarkAccent = { name ->
                            selectedDarkAccent = name
                            com.example.data.repository.SettingsRepository.updateSelectedDarkTheme(name)
                            if (appearanceMode == "DARK") {
                                com.example.data.repository.SettingsRepository.updateThemeSelection(name)
                            }
                        },
                        onAccentSelected = { name ->
                            selectedAccent = name
                            com.example.data.repository.SettingsRepository.updateAccent(name)
                        },
                        onFinish = {
                            // Persist everything
                            com.example.data.repository.SettingsRepository.updateUserName(userName)
                            com.example.data.repository.SettingsRepository.updateAccent(selectedAccent)
                            com.example.data.repository.SettingsRepository.updateAppearanceMode(appearanceMode)
                            when (appearanceMode) {
                                "LIGHT" -> com.example.data.repository.SettingsRepository.updateThemeSelection(selectedLightAccent)
                                "DARK" -> com.example.data.repository.SettingsRepository.updateThemeSelection(selectedDarkAccent)
                            }
                            onFinishOnboarding(userName, selectedDarkAccent)
                        },
                        onBack = { currentStep = 2 }
                    )
                }
            }
        }
    }
}

private enum class PermState { NOT_REQUESTED, GRANTED, SKIPPED, DENIED }

// ─── v6.0 transplant (manifest §5.10): Dynamic Island progress pill ───

@Composable
private fun DynamicIslandProgressPill(
    currentStep: Int,
    totalSteps: Int,
    accentColor: Color
) {
    // Ambient decorative loop: runs only at full motion. REDUCED shortens a loop's period,
    // which makes it faster and more distracting, so reduced-motion turns it off entirely and
    // the resting values below take over. See rememberAmbientMotionEnabled().
    val animationsEnabled = rememberAmbientMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animatedPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )
    val pulseAlpha = if (animationsEnabled) animatedPulseAlpha else 0.7f

    Box(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(22.dp), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.92f))
            .border(0.8.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Glowing Live Pulse Dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = pulseAlpha))
            )

            // Step Label
            Text(
                text = "Setup Phase 0$currentStep",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )

            // Capsule Mini-Bars
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 1..totalSteps) {
                    val isActive = i <= currentStep
                    val barWidth by animateDpAsState(
                        targetValue = if (i == currentStep) 20.dp else 8.dp,
                        animationSpec = scaledSpring(dampingRatio = 0.7f),
                        label = "pillWidth"
                    )
                    Box(
                        modifier = Modifier
                            .height(5.dp)
                            .width(barWidth)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isActive) accentColor else Color.White.copy(alpha = 0.2f))
                    )
                }
            }
        }
    }
}

// ─── Step 1: Identity ────────────────────────────────────────────

@Composable
private fun IdentityStep(
    userName: String,
    onNameChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    val canContinue = userName.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.15f))

        // App icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF3B82F6),
                            Color(0xFF6366F1),
                            Color(0xFF8B5CF6)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("R", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
        }

        Spacer(modifier = Modifier.height(24.dp))

        RequestrWordmark(fontSize = 36, textColor = MaterialTheme.colorScheme.onSurface)

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            "Network Security & API Intelligence",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Name input — FIX: use surface color for both text and container
        // so text is always visible regardless of theme
        OutlinedTextField(
            value = userName,
            onValueChange = onNameChange,
            placeholder = {
                Text(
                    "Your name",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            "This is how requestr will greet you",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.weight(0.6f))

        // Continue button — right-aligned
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            FilledIconButton(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Continue", modifier = Modifier.size(24.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ─── Step 2: Permissions ─────────────────────────────────────────

@Composable
private fun PermissionsStep(
    storageState: PermState,
    notifState: PermState,
    musicState: PermState,
    onStorageGrant: () -> Unit,
    onStorageSettings: () -> Unit,
    onNotifGrant: () -> Unit,
    onNotifSkip: () -> Unit,
    onMusicGrant: () -> Unit,
    onMusicSkip: () -> Unit,
    onContinue: () -> Unit
) {
    val canContinue = storageState == PermState.GRANTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Text(
            "Permissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "requestr needs these to capture and analyze traffic",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Storage — REQUIRED
        PermissionRow(
            icon = Icons.Default.Storage,
            title = "Storage Access",
            desc = "Save API collections, exports, and notes",
            state = storageState,
            required = true,
            onGrant = onStorageGrant,
            onSettings = onStorageSettings
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Notifications — optional
        PermissionRow(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            desc = "Capture session status and background alerts",
            state = notifState,
            required = false,
            onGrant = onNotifGrant,
            onSkip = onNotifSkip
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Music — optional, auto-granted if storage is granted
        val effectiveMusic = if (storageState == PermState.GRANTED) PermState.GRANTED else musicState
        PermissionRow(
            icon = Icons.Default.MusicNote,
            title = "Local Music",
            desc = if (storageState == PermState.GRANTED) "Covered by Storage Access"
                   else "Scan local audio for the Synth Lounge",
            state = effectiveMusic,
            required = false,
            onGrant = onMusicGrant,
            onSkip = onMusicSkip
        )

        if (storageState == PermState.DENIED) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Storage access is required to continue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Next button — right-aligned, same position as screen 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            FilledIconButton(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Continue", modifier = Modifier.size(24.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    desc: String,
    state: PermState,
    required: Boolean,
    onGrant: () -> Unit,
    onSkip: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (required) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Required",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when (state) {
            PermState.GRANTED -> {
                Icon(Icons.Default.Check, "Granted", tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
            }
            PermState.SKIPPED -> {
                Text("Skipped", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PermState.DENIED -> {
                if (required && onSettings != null) {
                    Text("Open Settings", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { onSettings() })
                } else if (onSkip != null) {
                    Text("Skip", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSkip() })
                }
            }
            PermState.NOT_REQUESTED -> {
                Text("Grant", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onGrant() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ─── Step 3: Appearance ──────────────────────────────────────────

@Composable
private fun AppearanceStep(
    selectedMode: String,
    lightAccent: String,
    darkAccent: String,
    selectedAccent: String,
    onModeSelected: (String) -> Unit,
    onLightAccent: (String) -> Unit,
    onDarkAccent: (String) -> Unit,
    onAccentSelected: (String) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Text(
            "Appearance",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Pick your style. Change anytime in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        // ── Mode selector: SYSTEM / LIGHT / DARK ──────────────
        Text(
            "Theme Mode",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val modes = listOf(
            Triple("SYSTEM", "System", Icons.Default.SettingsBrightness),
            Triple("LIGHT", "Light", Icons.Default.LightMode),
            Triple("DARK", "Dark", Icons.Default.DarkMode)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            modes.forEach { (id, label, icon) ->
                val isActive = selectedMode == id
                // Selection animates on a gated tween; theme colours resolve instantly.
                val bg by animateStateColor(
                    active = isActive,
                    activeColor = MaterialTheme.colorScheme.primaryContainer,
                    inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    animationSpec = scaledTween(200),
                    label = "modeBg"
                )
                val fg by animateStateColor(
                    active = isActive,
                    activeColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = scaledTween(200),
                    label = "modeFg"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .clickable { onModeSelected(id) }
                        .padding(vertical = 14.dp)
                ) {
                    Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(label, fontSize = 12.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = fg)
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ── Accent colour swatches ────────────────────────────
        Text(
            "Accent Color",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        val allAccents = AppAccent.values().toList()

        Text(
            "Choose your accent color",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        // 10 swatches in a single horizontally-scrollable row so they never overflow
        // on narrow screens (matches onboarding-step-3-dark.html .accentrow overflow-x:auto).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            allAccents.forEach { accent ->
                val accentSelected = selectedAccent.equals(accent.name, ignoreCase = true)
                val accentSize by animateDpAsState(if (accentSelected) 40.dp else 32.dp, scaledTween(200), label = "accentSize")
                Box(
                    modifier = Modifier
                        .size(accentSize)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.primary)
                        .then(
                            if (accentSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable { onAccentSelected(accent.name) },
                    contentAlignment = Alignment.Center
                ) {
                    if (accentSelected) {
                        Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Changes accent only — never flips Light/Dark mode",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val allThemes = AppThemeMode.values().toList()
        // Filter: show light accents in LIGHT mode, dark accents in DARK mode, all in SYSTEM
        val activeAccents = when (selectedMode) {
            "LIGHT" -> allThemes.filter { it.isLight }
            "DARK" -> allThemes.filter { !it.isLight }
            else -> allThemes
        }

        // Current selected accent for the active mode
        val currentAccent = when (selectedMode) {
            "LIGHT" -> lightAccent
            "DARK" -> darkAccent
            else -> darkAccent  // SYSTEM defaults to dark accent
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            activeAccents.forEach { theme ->
                val isSelected = theme.name == currentAccent
                val size by animateDpAsState(if (isSelected) 44.dp else 36.dp, scaledTween(200), label = "swatchSize")
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(theme.samplePrimary)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable {
                            when (selectedMode) {
                                "LIGHT" -> onLightAccent(theme.name)
                                "DARK" -> onDarkAccent(theme.name)
                                else -> {
                                    // In SYSTEM mode, pick the appropriate slot
                                    if (theme.isLight) onLightAccent(theme.name)
                                    else onDarkAccent(theme.name)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Finish bar with back arrow ────────────────────────
        // Animated: expands from the right-corner button into a full pill
        val isFinalStep = true
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back arrow — only on last step
            FilledTonalButton(
                onClick = onBack,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
            }

            // Finish button — full pill, animated
            Button(
                onClick = onFinish,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                modifier = Modifier
            ) {
                Text("Finish Setup", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
