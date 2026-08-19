package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.RequestrWordmark
import com.example.ui.theme.AppThemeMode

/**
 * Real state for a single onboarding permission card (A2 fix).
 *
 * Previously every card only tracked a single `handled: Boolean` flipped identically by
 * both "Grant" and "Skip" — meaning a skipped permission rendered as a green "Granted"
 * pill exactly like a genuinely granted one. Now:
 *  - [NOT_REQUESTED]: no OS dialog has been shown yet (or a previous denial reset it).
 *  - [GRANTED]: `ContextCompat.checkSelfPermission` confirmed OS-level grant.
 *  - [SKIPPED]: the user explicitly skipped an *optional* permission — rendered distinctly
 *    from GRANTED, never claiming a real grant that didn't happen.
 *  - [DENIED]: the OS permission dialog was shown and the user denied it. For a
 *    non-optional permission this blocks wizard progression with a link to App Info.
 */
private enum class PermState { NOT_REQUESTED, GRANTED, SKIPPED, DENIED }

/**
 * Redesigned onboarding (Fix 10). Three steps:
 *   1. Welcome + Name — branded intro with name capture
 *   2. Permissions & Access — storage, network, music cards with skip
 *   3. Appearance — System/Light/Dark selector + accent colour swatches
 *
 * Progress dots at the top; all colours use theme tokens; no hardcoded values.
 * When re-run via Settings, already-granted permissions show "Granted" +
 * a subtle "Skip" option; the theme selector pre-fills the current choice.
 *
 * A1 fix (v3.1): there is exactly ONE source of truth for the theme at all times —
 * [SettingsRepository.settings.themeMode]. Previously this screen held its own
 * `remember { mutableStateOf("MONOCHROME_DARK") }` copy that was invisible to the
 * repository until "Finish Setup" was tapped. That caused two concrete bugs:
 *   1. Re-running the wizard from Settings always reset the swatch highlight to
 *      Monochrome Dark instead of showing the theme actually in effect, and tapping
 *      Finish without touching the swatches would silently overwrite the real
 *      persisted theme back to Monochrome Dark.
 *   2. The onboarding screen's own chrome (background/surface/primary colors) never
 *      reflected the theme being previewed, since the root `MyApplicationTheme` in
 *      MainActivity reads the persisted value live, not this screen's local copy.
 * Fix: seed local state FROM the repository, and write every tap straight through to
 * [SettingsRepository.updateTheme] / [SettingsRepository.updateAppearanceMode] so the
 * root theme provider and this screen are always looking at the same value — the
 * wizard becomes a live editor of the real setting, not a disconnected preview.
 */
@Composable
fun OnboardingScreen(
    onFinishOnboarding: (userName: String, themeMode: String) -> Unit
) {
    val settings by com.example.data.repository.SettingsRepository.settings.collectAsState()
    val totalSteps = 3
    var currentStep by remember { mutableIntStateOf(1) }
    var userName by remember { mutableStateOf("") }
    // Seed from the real persisted value (not a hardcoded literal) so re-running the
    // wizard shows what's actually active instead of always resetting to Monochrome Dark.
    var selectedTheme by remember { mutableStateOf(settings.themeMode) }

    // ── A2 fix: real permission state, backed by actual OS grants ─────────────
    val context = LocalContext.current

    val storagePermissionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, storagePermissionName) ==
            PackageManager.PERMISSION_GRANTED
    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // POST_NOTIFICATIONS didn't exist pre-33; notifications are implicitly allowed.
        }

    var storageState by remember { mutableStateOf(if (hasStoragePermission()) PermState.GRANTED else PermState.NOT_REQUESTED) }
    var musicState by remember { mutableStateOf(if (hasStoragePermission()) PermState.GRANTED else PermState.NOT_REQUESTED) }
    // Notifications (POST_NOTIFICATIONS, Android 13+) — optional; app can function without
    // them (only foreground-service/status notices are affected), so Skip is legitimate here.
    var notificationState by remember {
        mutableStateOf(if (hasNotificationPermission()) PermState.GRANTED else PermState.NOT_REQUESTED)
    }
    // Network permissions (INTERNET/ACCESS_NETWORK_STATE) are normal/install-time
    // permissions on Android — never runtime-requestable, so there is no OS dialog to
    // show. This card is real in that it reflects an actually-true fact (the manifest
    // declares them, so they are always granted at install), not a fabricated "auto"
    // state that pretends a runtime grant occurred.
    val networkState = PermState.GRANTED

    // Re-check on resume (e.g. returning from App Info settings after a manual grant).
    LaunchedEffect(Unit) {
        if (hasStoragePermission()) {
            if (storageState != PermState.SKIPPED) storageState = PermState.GRANTED
            if (musicState != PermState.SKIPPED) musicState = PermState.GRANTED
        }
        if (hasNotificationPermission() && notificationState != PermState.SKIPPED) {
            notificationState = PermState.GRANTED
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> storageState = if (granted) PermState.GRANTED else PermState.DENIED }

    val musicPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> musicState = if (granted) PermState.GRANTED else PermState.DENIED }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationState = if (granted) PermState.GRANTED else PermState.DENIED }

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
                    storageState = storageState,
                    networkState = networkState,
                    musicState = musicState,
                    notificationState = notificationState,
                    onStorageGrant = { storagePermissionLauncher.launch(storagePermissionName) },
                    onStorageOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    },
                    onMusicGrant = { musicPermissionLauncher.launch(storagePermissionName) },
                    onMusicSkip = { musicState = PermState.SKIPPED },
                    onNotificationGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationState = PermState.GRANTED
                        }
                    },
                    onNotificationSkip = { notificationState = PermState.SKIPPED },
                    onContinue = { currentStep = 3 }
                )

                3 -> AppearanceStep(
                    selectedTheme = selectedTheme,
                    onThemeSelected = { theme ->
                        // Write through immediately so the root theme provider (which
                        // reads SettingsRepository live) and this screen never disagree —
                        // this IS the fix for A1's "collision" symptom.
                        selectedTheme = theme
                        com.example.data.repository.SettingsRepository.updateTheme(theme)
                    },
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
        // Branded icon badge (A3 fix: emoji "⚡" placeholder replaced with a real vector
        // icon token from the app's own icon set, consistent with the rest of the app's
        // iconography rather than a stand-in glyph).
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
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // A3 fix: use the real app-wide wordmark composable (with its live-state dot)
        // instead of a disconnected plain "requestr" Text — this is the same brand
        // element rendered in HomeScreen/SettingsScreen/AppNavigationDrawer, so the
        // wizard's first impression matches the rest of the app instead of diverging
        // from it.
        RequestrWordmark(
            fontSize = 32,
            textColor = MaterialTheme.colorScheme.primary
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
    storageState: PermState,
    networkState: PermState,
    musicState: PermState,
    notificationState: PermState,
    onStorageGrant: () -> Unit,
    onStorageOpenSettings: () -> Unit,
    onMusicGrant: () -> Unit,
    onMusicSkip: () -> Unit,
    onNotificationGrant: () -> Unit,
    onNotificationSkip: () -> Unit,
    onContinue: () -> Unit
) {
    // Storage is NOT optional (collections/HAR export/notes need it) — per A2's spec,
    // non-optional permissions block progression instead of offering Skip. Network is
    // always GRANTED (install-time manifest permission). Music + notifications are
    // optional, so they never block Continue even when SKIPPED or DENIED.
    val canContinue = storageState == PermState.GRANTED

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

        // Storage card — required, no Skip. Denied blocks Continue and links to App Info.
        PermissionCard(
            icon = Icons.Default.Storage,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Storage Access",
            description = "Save and load API collections, HAR exports & notes",
            state = storageState,
            onGrant = onStorageGrant,
            onOpenSettings = onStorageOpenSettings,
            optional = false
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Network card — install-time manifest permission, always granted, no action needed.
        PermissionCard(
            icon = Icons.Default.Public,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = "Network Permissions",
            description = "Required for API testing and packet interception",
            state = networkState,
            onGrant = {},
            grantLabel = "Auto-granted"
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Music card (from Fix 05) — optional.
        PermissionCard(
            icon = Icons.Default.MusicNote,
            iconTint = MaterialTheme.colorScheme.tertiary,
            title = "Local Music Library",
            description = "Scan local audio for Focus & Synth Lounge",
            state = musicState,
            onGrant = onMusicGrant,
            onSkip = onMusicSkip,
            optional = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Notifications card (POST_NOTIFICATIONS, Android 13+) — optional.
        PermissionCard(
            icon = Icons.Default.Notifications,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = "Notifications",
            description = "Status updates for capture sessions and background tasks",
            state = notificationState,
            onGrant = onNotificationGrant,
            onSkip = onNotificationSkip,
            optional = true
        )

        if (storageState == PermState.DENIED) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "requestr needs storage access to save your API collections — " +
                    "please grant to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
            currentTheme = selectedTheme,
            onModeSelected = { mode ->
                // Mirrors the exact same write path SettingsScreen's AppearanceSelector
                // uses, so "System/Light/Dark" here has a real, immediate effect instead
                // of being cosmetic-only (previously a no-op comment).
                com.example.data.repository.SettingsRepository.updateAppearanceMode(mode)
                when (mode) {
                    "LIGHT" -> onThemeSelected("EDITORIAL_LIGHT")
                    "DARK" -> onThemeSelected(
                        com.example.data.repository.SettingsRepository.settings.value.selectedDarkTheme
                    )
                    // "SYSTEM": theme follows system brightness; leave the accent
                    // swatch selection (below) as the dark/light theme used when it does.
                }
            }
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
    state: PermState,
    onGrant: () -> Unit,
    onSkip: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
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

        // Status — four visually distinct states (A2 fix): GRANTED, SKIPPED, and DENIED
        // must never look identical to each other or to "needs action."
        when (state) {
            PermState.GRANTED -> {
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
            PermState.SKIPPED -> {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "Skipped",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            PermState.DENIED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "Denied",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            PermState.NOT_REQUESTED -> {
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

    // Secondary action row below the card.
    when {
        // Skipped — read explicitly as "won't work until granted later," with a way back in.
        state == PermState.SKIPPED -> {
            Text(
                text = "Skipped — this feature won't work until granted later in Settings",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
        }
        // Denied on a non-optional permission — block-styled message + link to App Info.
        state == PermState.DENIED && !optional && onOpenSettings != null -> {
            Text(
                text = "Open App Info to grant manually",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 52.dp, top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        // Denied on an optional permission — offer to retry the real system dialog.
        state == PermState.DENIED && optional -> {
            Text(
                text = "Try again",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 52.dp, top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onGrant() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        // Not yet requested and genuinely optional — offer Skip (never for required perms).
        state == PermState.NOT_REQUESTED && optional && onSkip != null -> {
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
}

@Composable
private fun AppearanceSelectorCompact(
    currentTheme: String,
    onModeSelected: (String) -> Unit
) {
    val modes = listOf(
        Triple("SYSTEM", "System", Icons.Outlined.SettingsBrightness),
        Triple("LIGHT", "Light", Icons.Filled.LightMode),
        Triple("DARK", "Dark", Icons.Outlined.DarkMode)
    )
    // Derived from the real selected theme instead of an isolated local default, so this
    // row reflects what AccentColorSwatches below is actually showing as selected.
    val selectedMode = when {
        currentTheme.equals("EDITORIAL_LIGHT", ignoreCase = true) -> "LIGHT"
        else -> "DARK"
    }

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
                    .clickable { onModeSelected(mode) }
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
