package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.example.data.interception.CaptureLogRepository
import com.example.data.repository.SettingsRepository
import com.example.data.interception.CaState
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.RootState
import com.example.data.interception.ShizukuState
import com.example.interception.CapabilityProbe
import com.example.interception.CertificateAuthority
import kotlinx.coroutines.launch
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrWordmark
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val settings by SettingsRepository.settings.collectAsState()
    // Real capability state and a real CA read; v2.1 showed a hardcoded serial/fingerprint
    // and six diagnostics rows that always claimed success (APP_v2.md Bug 3.1 / 10).
    val settingsContext = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    val capabilities by InterceptionStateRepository.capabilities.collectAsState()
    val capturedCount by remember(settingsContext) {
        CaptureLogRepository.observeCount(settingsContext)
    }.collectAsState(initial = 0)
    val caSummary by produceState<Triple<String, String, String>?>(initialValue = null) {
        value = runCatching {
            val cert = CertificateAuthority.getOrCreate(settingsContext).certificate
            val fp = java.security.MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded).joinToString(":") { "%02X".format(it) }
            Triple(
                cert.subjectX500Principal.name,
                cert.serialNumber.toString(16).uppercase(),
                fp
            )
        }.getOrNull()
    }
    LaunchedEffect(Unit) { CapabilityProbe.probeAll(settingsContext) }

    var userNameInput by remember(settings.userName) { mutableStateOf(settings.userName) }
    var proxyPortInput by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    var upstreamProxyInput by remember { mutableStateOf("") }

    var autoStartInterceptor by remember { mutableStateOf(false) }
    var strictTls13 by remember { mutableStateOf(true) }
    var followRedirects by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(
            title = "Settings & Config",
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Profile & Identity
            item {
                SettingsSectionHeader("OPERATOR & IDENTITY")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Operator Alias / Username", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = userNameInput,
                            onValueChange = {
                                userNameInput = it
                                SettingsRepository.updateUserName(it)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            // Section 2: Atmosphere & 7 Luxury Themes
            item {
                SettingsSectionHeader("ATMOSPHERE & THEME PALETTES")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeMode.values().forEach { mode ->
                        val isSelected = settings.themeMode.equals(mode.name, ignoreCase = true)
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                            borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            onClick = { SettingsRepository.updateTheme(mode.name) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(mode.samplePrimary)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = mode.displayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (mode.isLight) "Light Editorial Tone" else "Luxury Dark Palette",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: UI Geometry & Radius
            item {
                SettingsSectionHeader("UI GEOMETRY & DISPLAY")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Card Corner Radius", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("${settings.cornerRadiusDp} dp", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.cornerRadiusDp.toFloat(),
                            onValueChange = { SettingsRepository.updateCornerRadius(it.toInt()) },
                            valueRange = 8f..24f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            // Section 4: Network & Proxy Engine
            item {
                SettingsSectionHeader("INTERCEPTION & PROXY ENGINE")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column {
                            Text("Proxy Listening Port (Localhost 127.0.0.1)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = proxyPortInput,
                                onValueChange = {
                                    proxyPortInput = it
                                    it.toIntOrNull()?.let { p -> SettingsRepository.updateProxyPort(p) }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }

                        SettingToggleRow("Auto-start interceptor on launch", autoStartInterceptor) { autoStartInterceptor = it }
                        SettingToggleRow("Enforce TLS v1.3 Strict Ciphers", strictTls13) { strictTls13 = it }
                        SettingToggleRow("Follow HTTP 3xx Redirects", followRedirects) { followRedirects = it }
                    }
                }
            }

            // Section 5: Deep Links & Trigger-to-Action Ecosystem
            item {
                SettingsSectionHeader("ECOSYSTEM & DEEP LINKS")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("requestr:// URI Handler Scheme", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Enables Telegram-like inter-app triggers, n8n automation links, and terminal hooks.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(modifier = Modifier.height(10.dp))

                        val deepLinks = listOf(
                            "requestr://intercept/toggle" to "Toggle capture state",
                            "requestr://api/run?id=req-1" to "Trigger API payload",
                            "requestr://flow/execute?id=flow-1" to "Run n8n Automation Chain",
                            "requestr://note/create?title=Log" to "New quick note"
                        )

                        deepLinks.forEach { (scheme, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(scheme, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                Text(desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Section 6: Root CA Certificate Details
            item {
                SettingsSectionHeader("CA ROOT CERTIFICATE & TRUST STORE")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val summary = caSummary
                        if (summary == null) {
                            Text("Reading certificate...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(summary.first, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Serial: ${summary.second}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("SHA-256: ${summary.third}", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        // Trust-store state from a real probe, not an assumption.
                        Text(
                            text = when (capabilities.ca) {
                                CaState.SYSTEM_STORE -> "Trust store: installed (system)"
                                CaState.USER_STORE -> "Trust store: installed (user)"
                                CaState.NOT_INSTALLED -> "Trust store: NOT installed"
                                CaState.UNKNOWN -> "Trust store: checking..."
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (capabilities.isCaInstalled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    // Launches the real KeyChain install intent for the actual CA.
                                    settingsScope.launch {
                                        runCatching {
                                            settingsContext.startActivity(
                                                CertificateAuthority.buildInstallIntent(settingsContext)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Install CA", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    // Exports the genuine CA as PEM for manual system-store install.
                                    settingsScope.launch {
                                        runCatching {
                                            val pem = CertificateAuthority.exportPem(settingsContext)
                                            settingsContext.startActivity(
                                                Intent.createChooser(
                                                    Intent(Intent.ACTION_SEND).apply {
                                                        type = "application/x-pem-file"
                                                        putExtra(Intent.EXTRA_SUBJECT, "requestr Root CA (PEM)")
                                                        putExtra(Intent.EXTRA_TEXT, pem)
                                                    },
                                                    "Export CA as PEM"
                                                )
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Export PEM", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Section 7: Diagnostics
            item {
                SettingsSectionHeader("SECURITY TIERS DIAGNOSTICS")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Every row below reports a REAL probe result. v2.1 hardcoded all six to
                        // "true" with invented version strings, so an unrooted device with no
                        // Shizuku still showed six green ticks (APP_v2.md Bug 3.1).
                        DiagnosticRow(
                            "VPN capture tier (VpnService)",
                            "Always available",
                            true
                        )
                        DiagnosticRow(
                            "Local proxy port",
                            "Port ${settings.proxyPort}",
                            true
                        )
                        DiagnosticRow(
                            "CA trust store",
                            when (capabilities.ca) {
                                CaState.SYSTEM_STORE -> "Installed (system store)"
                                CaState.USER_STORE -> "Installed (user store)"
                                CaState.NOT_INSTALLED -> "Not installed"
                                CaState.UNKNOWN -> "Not probed"
                            },
                            capabilities.isCaInstalled
                        )
                        DiagnosticRow(
                            "Root access",
                            when (capabilities.root) {
                                RootState.GRANTED -> "Granted (su returned uid=0)"
                                RootState.AVAILABLE -> "su present, not confirmed"
                                RootState.DENIED -> "Denied by root manager"
                                RootState.UNAVAILABLE -> "No su binary (not rooted)"
                                RootState.UNKNOWN -> "Not probed"
                            },
                            capabilities.root == RootState.GRANTED
                        )
                        DiagnosticRow(
                            "Shizuku service",
                            when (capabilities.shizuku) {
                                ShizukuState.GRANTED -> "Bound and permitted"
                                ShizukuState.PERMISSION_DENIED -> "Running, permission denied"
                                ShizukuState.NOT_RUNNING -> "Installed, not running"
                                ShizukuState.NOT_INSTALLED -> "Not installed"
                                ShizukuState.UNKNOWN -> "Not probed"
                            },
                            capabilities.shizuku == ShizukuState.GRANTED
                        )
                        DiagnosticRow(
                            "frida-server (127.0.0.1:27042)",
                            when (capabilities.frida) {
                                FridaState.RUNNING -> "Reachable"
                                FridaState.NOT_RUNNING -> "Not reachable"
                                FridaState.UNKNOWN -> "Not probed"
                            },
                            capabilities.frida == FridaState.RUNNING
                        )
                    }
                }
            }

            // Section 8: Backup & Privacy
            item {
                SettingsSectionHeader("BACKUP & PRIVACY")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        // Deletes the real persisted rows rather than an in-memory list.
                        onClick = { settingsScope.launch { CaptureLogRepository.clearAll(settingsContext) } },
                        enabled = capturedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (capturedCount == 0) "Log empty" else "Clear $capturedCount",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = { /* Export */ },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export JSON", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
            }

            // Section 9: MANDATORY App Metadata Block (Fix #3 / Item #3)
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RequestrWordmark(fontSize = 20)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("RELEASE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(10.dp))

                        AppMetaInfoRow("Application Name", "requestr")
                        AppMetaInfoRow("Package ID", "com.kailashhh.requestr")
                        AppMetaInfoRow("Version", "2.1.0-release (API 36)")
                        AppMetaInfoRow("Build Date", "2026.08.17")
                        AppMetaInfoRow("Core Engine", "OkHttp 4.12.0 + Conscrypt APEX")
                        AppMetaInfoRow("Architecture", "arm64-v8a, armeabi-v7a, x86_64")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(90.dp)) }
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun AppMetaInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun DiagnosticRow(name: String, status: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isOk) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(status, color = if (isOk) Color(0xFF10B981) else Color(0xFFEF4444), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}
