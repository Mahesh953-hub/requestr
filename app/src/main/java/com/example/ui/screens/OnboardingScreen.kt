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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.repository.SettingsRepository
import com.example.ui.components.GlassActionPill
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrWordmark
import com.example.ui.components.rememberAppHapticFeedback
import com.example.ui.theme.AppAccent

@Composable
fun OnboardingScreen(
    onFinishOnboarding: (userName: String, themeMode: String) -> Unit
) {
    val settings by SettingsRepository.settings.collectAsState()
    val totalSteps = 3
    var currentStep by remember { mutableIntStateOf(1) }
    var userName by remember { mutableStateOf(settings.userName.ifBlank { "Operator" }) }

    // Appearance State
    var isDarkSelected by remember { mutableStateOf(settings.appearanceMode.uppercase() != "LIGHT") }
    var selectedAccent by remember {
        mutableStateOf(AppAccent.fromKey(if (isDarkSelected) settings.selectedDarkTheme else settings.selectedLightTheme))
    }

    val context = LocalContext.current
    val haptics = rememberAppHapticFeedback()

    // Permissions logic
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

    LaunchedEffect(Unit) {
        if (hasStorage()) storageState = PermState.GRANTED
        if (hasNotif()) notifState = PermState.GRANTED
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> storageState = if (granted) PermState.GRANTED else PermState.DENIED }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifState = if (granted) PermState.GRANTED else PermState.DENIED }

    // Ambient background with animated mesh glow
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Futuristic ambient background glow spheres
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            selectedAccent.primary.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(320.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            selectedAccent.bright.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(44.dp))

            // ─── TOP DYNAMIC ISLAND PROGRESS PILL ──────────────────────
            DynamicIslandProgressPill(
                currentStep = currentStep,
                totalSteps = totalSteps,
                accentColor = selectedAccent.primary
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ─── STEP CONTENT ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(spring(stiffness = 500f)) { it * dir } + fadeIn(tween(250)))
                            .togetherWith(slideOutHorizontally(spring(stiffness = 500f)) { -it * dir } + fadeOut(tween(250)))
                    },
                    label = "onboardingStep"
                ) { step ->
                    when (step) {
                        1 -> OnboardingIdentityStep(
                            userName = userName,
                            onNameChange = { userName = it },
                            accent = selectedAccent
                        )
                        2 -> OnboardingDiagnosticsStep(
                            storageState = storageState,
                            notifState = notifState,
                            onGrantStorage = { storageLauncher.launch(storagePerm) },
                            onGrantNotif = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else notifState = PermState.GRANTED
                            },
                            accent = selectedAccent
                        )
                        3 -> OnboardingThemeStep(
                            isDark = isDarkSelected,
                            onModeToggle = { isDark ->
                                isDarkSelected = isDark
                                SettingsRepository.updateAppearanceMode(if (isDark) "DARK" else "LIGHT")
                            },
                            selectedAccent = selectedAccent,
                            onAccentSelect = { accent ->
                                selectedAccent = accent
                                SettingsRepository.updateThemeSelection(accent.name)
                            }
                        )
                    }
                }
            }

            // ─── BOTTOM DYNAMIC ACTION PILLS ───────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 1) {
                    GlassActionPill(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = { currentStep-- },
                        size = 52.dp,
                        iconSize = 22.dp
                    )
                } else {
                    Spacer(modifier = Modifier.width(52.dp))
                }

                // Next / Finish Glowing Pill Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                        .height(52.dp)
                        .shadow(8.dp, RoundedCornerShape(26.dp), spotColor = selectedAccent.primary.copy(alpha = 0.5f))
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    selectedAccent.primary,
                                    selectedAccent.bright
                                )
                            )
                        )
                        .clickable {
                            haptics(HapticFeedbackType.LongPress)
                            if (currentStep < totalSteps) {
                                currentStep++
                            } else {
                                SettingsRepository.updateUserName(userName)
                                SettingsRepository.updateIsDarkTheme(isDarkSelected)
                                SettingsRepository.updateAppearanceMode(if (isDarkSelected) "DARK" else "LIGHT")
                                SettingsRepository.updateSelectedAccent(selectedAccent.name)
                                SettingsRepository.updateThemeSelection(selectedAccent.name)
                                SettingsRepository.setOnboardingCompleted(true)
                                onFinishOnboarding(userName, selectedAccent.name)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (currentStep == totalSteps) "Launch requestr" else "Continue",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (currentStep == totalSteps) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─── DYNAMIC ISLAND PROGRESS PILL ──────────────────────────────────────────

@Composable
private fun DynamicIslandProgressPill(
    currentStep: Int,
    totalSteps: Int,
    accentColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )

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
                    val width by animateDpAsState(
                        targetValue = if (i == currentStep) 20.dp else 8.dp,
                        animationSpec = spring(dampingRatio = 0.7f),
                        label = "pillWidth"
                    )
                    Box(
                        modifier = Modifier
                            .height(5.dp)
                            .width(width)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isActive) accentColor else Color.White.copy(alpha = 0.2f))
                    )
                }
            }
        }
    }
}

// ─── STEP 1: IDENTITY ──────────────────────────────────────────────────────

@Composable
private fun OnboardingIdentityStep(
    userName: String,
    onNameChange: (String) -> Unit,
    accent: AppAccent
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Futuristic Glowing Requestr Holographic Emblem
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                accent.primary.copy(alpha = 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF1E293B),
                                Color(0xFF0F172A)
                            )
                        )
                    )
                    .border(1.2.dp, accent.bright.copy(alpha = 0.6f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "R",
                    color = accent.bright,
                    fontSize = 38.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        RequestrWordmark(fontSize = 32, textColor = MaterialTheme.colorScheme.onSurface)

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Next-Gen Network Security & Traffic Inspector",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Alias Input Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Operator Identity",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = userName,
                    onValueChange = onNameChange,
                    placeholder = { Text("Enter alias or callsign", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick suggested aliases
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Cipher", "Spectre", "ZeroTrace", "Kernel", "Ghost").forEach { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable { onNameChange(tag) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = tag, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─── STEP 2: DIAGNOSTICS & PERMISSIONS ─────────────────────────────────────

@Composable
private fun OnboardingDiagnosticsStep(
    storageState: PermState,
    notifState: PermState,
    onGrantStorage: () -> Unit,
    onGrantNotif: () -> Unit,
    accent: AppAccent
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "System Capability Verification",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Verify device permissions for full interception capabilities.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Storage & Log Dumps Card
        DiagnosticCard(
            title = "Capture Storage & Certificates",
            description = "Save HAR archives, PCAP payloads, and custom CA certificates.",
            icon = Icons.Default.Storage,
            isGranted = storageState == PermState.GRANTED,
            onGrant = onGrantStorage,
            accent = accent
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Notifications Card
        DiagnosticCard(
            title = "Real-Time Traffic Alerts",
            description = "Status bar notification and background proxy controls.",
            icon = Icons.Default.Notifications,
            isGranted = notifState == PermState.GRANTED,
            onGrant = onGrantNotif,
            accent = accent
        )

        Spacer(modifier = Modifier.height(12.dp))

        // VPN Engine verification (Built-in)
        DiagnosticCard(
            title = "Loopback VpnService Engine",
            description = "Local socket tunnel active and ready for TLS interception.",
            icon = Icons.Default.Radar,
            isGranted = true,
            onGrant = {},
            accent = accent
        )
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    onGrant: () -> Unit,
    accent: AppAccent
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isGranted) accent.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) accent.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGranted) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = accent.primary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Active", fontSize = 11.sp, color = accent.primary, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.primary)
                        .clickable(onClick = onGrant)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Grant", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── STEP 3: THEME & DYNAMIC ACCENTS ───────────────────────────────────────

@Composable
private fun OnboardingThemeStep(
    isDark: Boolean,
    onModeToggle: (Boolean) -> Unit,
    selectedAccent: AppAccent,
    onAccentSelect: (AppAccent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Universal Theme & Dynamic Accents",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Select your base mode and Android 17+ Pixel dynamic accent.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Base Mode Pill Switcher (Light vs Dark)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Light Mode Pill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (!isDark) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .border(
                        if (!isDark) 0.8.dp else 0.dp,
                        if (!isDark) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onModeToggle(false) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.LightMode, contentDescription = null, tint = if (!isDark) selectedAccent.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Light Mode", fontSize = 13.sp, fontWeight = if (!isDark) FontWeight.Bold else FontWeight.Medium, color = if (!isDark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Dark Mode Pill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDark) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .border(
                        if (isDark) 0.8.dp else 0.dp,
                        if (isDark) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onModeToggle(true) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.DarkMode, contentDescription = null, tint = if (isDark) selectedAccent.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dark Mode", fontSize = 13.sp, fontWeight = if (isDark) FontWeight.Bold else FontWeight.Medium, color = if (isDark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 10 Dynamic Accents Swatches Grid
        Text(
            text = "CHOOSE DYNAMIC ACCENT (10 PALETTES)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(AppAccent.values()) { accent ->
                val isSelected = accent == selectedAccent
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(accent.primary)
                        .border(
                            if (isSelected) 3.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.2f),
                            CircleShape
                        )
                        .clickable { onAccentSelect(accent) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live Token Preview Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Live Theme Rendering (${selectedAccent.displayName})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(selectedAccent.primary)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Primary", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(selectedAccent.bright.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Active Container", color = selectedAccent.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private enum class PermState { NOT_REQUESTED, GRANTED, SKIPPED, DENIED }
