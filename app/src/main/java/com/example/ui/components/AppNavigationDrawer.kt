package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.data.repository.SettingsRepository
// TrafficInterceptorRepository import removed — capture state reads InterceptionStateRepository
import com.example.ui.theme.AppAccent
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorIdleAmber
import com.example.ui.theme.InterceptorInactiveRed
import kotlinx.coroutines.launch

/**
 * App-wide navigation drawer (dossier phase-b-implementation §2.9).
 *
 * Opening programmatically: hosts pass the same [DrawerState] they gave this composable and call
 * `scope.launch { drawerState.open() }` from a hamburger [GlassActionPill] (e.g.
 * `FloatingCapsuleHeader(onOpenDrawer = ...)`). `gesturesEnabled` deliberately tracks the
 * gesture-navigation setting (B5) — a disabled edge-swipe never disables the hamburger path.
 */
@Composable
fun AppNavigationDrawer(
    drawerState: DrawerState,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val settings by SettingsRepository.settings.collectAsState()
    // Real engine state, the same source the Intercept FAB and wordmark dot read.
    val interceptionState by InterceptionStateRepository.state.collectAsState()
    val isCapturing = interceptionState.isActive

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        // B5: when disabled, the edge-swipe gesture that opens this drawer is turned off so it
        // no longer fights with the system back-gesture nav; the drawer remains fully usable via
        // the hamburger icon regardless of this setting.
        gesturesEnabled = settings.gestureNavigationEnabled,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                modifier = Modifier
                    .width(310.dp)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 16.dp, horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header with Wordmark and Close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RequestrWordmark(fontSize = 24)

                        GlassActionPill(
                            icon = Icons.Default.Close,
                            contentDescription = "Close Drawer",
                            onClick = { coroutineScope.launch { drawerState.close() } },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 34.dp,
                            iconSize = 16.dp,
                        )
                    }

                    Text(
                        text = "Native Android Interceptor & Ecosystem",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Proxy Toggle Status Card
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when (interceptionState.status) {
                                                    InterceptionStatus.ACTIVE -> InterceptorActiveGreen
                                                    InterceptionStatus.ARMED -> InterceptorIdleAmber
                                                    InterceptionStatus.ERROR -> InterceptorInactiveRed
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when (interceptionState.status) {
                                            InterceptionStatus.ACTIVE -> "Capturing"
                                            InterceptionStatus.ARMED -> "Armed"
                                            InterceptionStatus.ERROR -> "Error"
                                            else -> "Not capturing"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = if (interceptionState.port > 0)
                                        "Port ${interceptionState.port} · ${interceptionState.tierLabel}"
                                    else "Configured port ${settings.proxyPort}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // No toggle here on purpose. Starting capture requires the real
                            // pre-flight gate plus a VPN-consent activity result, which this
                            // drawer cannot host. v2.1's switch flipped a boolean and claimed
                            // capture had started (APP_v2.md Bug 3.5). We show live counters
                            // instead and leave starting to the Intercept FAB.
                            Text(
                                text = if (isCapturing) "${interceptionState.packetCount} pkts" else "Use Intercept tab",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "NAVIGATION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Navigation Items — v6.0 transplant labels (manifest §5.2)
                    val navItems = listOf(
                        DrawerNavItem("home", "Home", Icons.Default.Home),
                        DrawerNavItem("intercept", "Interceptr", Icons.Default.Radar),
                        DrawerNavItem("apis", "API Testr", Icons.Default.Public),
                        DrawerNavItem("tools", "Tools", Icons.Default.Tune),
                        DrawerNavItem("notes", "Notes", Icons.AutoMirrored.Filled.Notes),
                        DrawerNavItem("flow", "Flow", Icons.Default.Code),
                        DrawerNavItem("chat", "Reqy Assistant", Icons.Default.AutoAwesome),
                        DrawerNavItem("music", "Music", Icons.Default.MusicNote)
                    )

                    navItems.forEach { item ->
                        val isSelected = currentRoute == item.route
                        val isInterceptItem = item.route == "intercept"
                        // v6.0 transplant: selected 24dp / unselected 12dp (manifest §5.2, DESIGN-RECORD §8.4)
                        val itemShape = if (isSelected) RoundedCornerShape(24.dp) else RoundedCornerShape(12.dp)

                        NavigationDrawerItem(
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.title,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    // v6.0 transplant: LIVE capture badge pill on Intercept item (manifest §5.2)
                                    if (isInterceptItem && isCapturing) {
                                        BadgePill(text = "LIVE", color = 0xFF10B981)
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(19.dp),
                                    tint = if (isInterceptItem && isCapturing) InterceptorActiveGreen
                                           else if (isSelected) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            selected = isSelected,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigate(item.route)
                            },
                            shape = itemShape,
                            colors = NavigationDrawerItemDefaults.colors(
                                // v6.0 transplant: selectedContainer 0.65 (manifest §5.2, DESIGN-RECORD §8.4)
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.padding(vertical = 2.5.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // v6.0 transplant: Theme & Accent quick switcher (manifest §5.2, DESIGN-RECORD §8.4)
                    // Light/Dark capsule toggle + named accent chips. Adapted to backup settings:
                    // updateAppearanceMode (SYSTEM/LIGHT/DARK) + updateAccent (orthogonal accent axis).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "THEME & ACCENT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )

                        // Light / Dark Quick Toggle
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val isDark = !settings.appearanceMode.equals("LIGHT", ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(if (!isDark) RoundedCornerShape(18.dp) else RoundedCornerShape(8.dp))
                                    .background(if (!isDark) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable {
                                        SettingsRepository.updateAppearanceMode("LIGHT")
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Light",
                                    fontSize = 11.sp,
                                    fontWeight = if (!isDark) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!isDark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(if (isDark) RoundedCornerShape(18.dp) else RoundedCornerShape(8.dp))
                                    .background(if (isDark) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable {
                                        SettingsRepository.updateAppearanceMode("DARK")
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Dark",
                                    fontSize = 11.sp,
                                    fontWeight = if (isDark) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isDark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppAccent.values().forEach { accent ->
                            val isAccentActive = settings.selectedAccent.equals(accent.name, ignoreCase = true)
                            val accentShape = if (isAccentActive) RoundedCornerShape(20.dp) else RoundedCornerShape(10.dp)
                            Box(
                                modifier = Modifier
                                    .clip(accentShape)
                                    .background(if (isAccentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(
                                        width = if (isAccentActive) 1.dp else 0.5.dp,
                                        color = if (isAccentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        shape = accentShape
                                    )
                                    .clickable {
                                        SettingsRepository.updateAccent(accent.name)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(accent.primary)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = accent.displayName,
                                        color = if (isAccentActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.sp,
                                        fontWeight = if (isAccentActive) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Settings Button
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onOpenSettings()
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Full Settings & Config",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "v${com.example.BuildConfig.VERSION_NAME}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        content = content
    )
}

data class DrawerNavItem(
    val route: String,
    val title: String,
    val icon: ImageVector
)
