package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorInactiveRed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.models.ApiRequestItem
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.NoteItem
import com.example.data.repository.ApiCollectionsRepository
import com.example.data.repository.MusicRepository
import com.example.data.repository.NotesRepository
import com.example.data.repository.SettingsRepository
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.data.interception.RootState
// TrafficInterceptorRepository import removed — all state flows now come from InterceptionStateRepository
import com.example.ui.components.AppNavigationDrawer
import com.example.ui.components.GlassCard
import com.example.ui.components.HoveredPillNavigationBar
import com.example.ui.components.InPageMiniSettingsSheet
import com.example.ui.components.MiniPlayerPersistentBar
import com.example.ui.screens.ApiTestrScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.FlowScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InterceptScreen
import com.example.ui.screens.MusicScreen
import com.example.ui.screens.NotesScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load persisted user settings before the UI tree reads them.
        // Fix: v2.1 initialised SettingsRepository with fake true defaults
        // (isRootDetected, isShizukuAvailable, isLsposedInstalled) and
        // never persisted theme choice, so the app lost its theme on restart
        // and briefly rendered a blank screen before the Compose tree settled.
        SettingsRepository.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            RequestrApp()
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Intercept : Screen("intercept", "Intercept", Icons.Default.Radar)
    object Apis : Screen("apis", "APIs", Icons.Default.Public)
    object Tools : Screen("tools", "Tools", Icons.Default.Tune)
    object Chats : Screen("chats", "Chats", Icons.Default.Chat)
    object Notes : Screen("notes", "Notes", Icons.Default.Notes)
    object Flow : Screen("flow", "Flow", Icons.Default.Code)
    object Music : Screen("music", "Music", Icons.Default.MusicNote)
    object Settings : Screen("settings", "Settings", Icons.Default.Tune)
    object Onboarding : Screen("onboarding", "Onboarding", Icons.Default.Home)
}

@Composable
fun RequestrApp() {
    val settings by SettingsRepository.settings.collectAsState()
    val themeMode = remember(settings.themeMode) {
        when (settings.themeMode.uppercase()) {
            "EDITORIAL_LIGHT", "LIGHT" -> AppThemeMode.EDITORIAL_LIGHT
            "MONOCHROME_DARK", "OBSIDIAN" -> AppThemeMode.MONOCHROME_DARK
            "MIDNIGHT_SAPPHIRE", "MIDNIGHT" -> AppThemeMode.MIDNIGHT_SAPPHIRE
            "KYOTO_MATCHA", "MATCHA" -> AppThemeMode.KYOTO_MATCHA
            "CYBER_TERMINAL", "TERMINAL" -> AppThemeMode.CYBER_TERMINAL
            "DUSK_TERRACOTTA", "DUSK" -> AppThemeMode.DUSK_TERRACOTTA
            "SOLAR_AMBER", "AMBER" -> AppThemeMode.SOLAR_AMBER
            else -> AppThemeMode.MIDNIGHT_SAPPHIRE
        }
    }

    MyApplicationTheme(themeMode = themeMode) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val coroutineScope = rememberCoroutineScope()

        val isMusicPlaying by MusicRepository.isPlaying.collectAsState()
        val currentTrack by MusicRepository.currentTrack.collectAsState()

        var showMiniSettingsForSection by remember { mutableStateOf<String?>(null) }

        val rootRoutes = listOf(
            Screen.Home.route,
            Screen.Intercept.route,
            Screen.Apis.route,
            Screen.Tools.route,
            Screen.Chats.route,
            Screen.Notes.route,
            Screen.Flow.route,
            Screen.Music.route
        )

        var isMiniPlayerDismissed by remember { mutableStateOf(false) }
        val showPillNav = currentRoute in rootRoutes && settings.onboardingCompleted

        AppNavigationDrawer(
            drawerState = drawerState,
            currentRoute = currentRoute,
            onNavigate = { route ->
                if (currentRoute != route) {
                    navController.navigate(route) {
                        popUpTo(Screen.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            onOpenSettings = {
                navController.navigate(Screen.Settings.route)
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (showPillNav) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                        ) {
                            // Persistent Mini Player above hovered pill dock
                            if (currentRoute != Screen.Music.route && !isMiniPlayerDismissed) {
                                MiniPlayerPersistentBar(
                                    track = currentTrack,
                                    isPlaying = isMusicPlaying,
                                    onTogglePlay = { MusicRepository.togglePlayPause() },
                                    onNext = { MusicRepository.nextTrack() },
                                    onClose = {
                                        MusicRepository.stopMusic()
                                        isMiniPlayerDismissed = true
                                    },
                                    onExpand = { navController.navigate(Screen.Music.route) }
                                )
                            }

                            // Hovered Floating Pill Style Navigation Bar
                            HoveredPillNavigationBar(
                                currentRoute = currentRoute,
                                onNavigate = { route ->
                                    if (currentRoute != route) {
                                        navController.navigate(route) {
                                            popUpTo(Screen.Home.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = if (settings.onboardingCompleted) Screen.Home.route else Screen.Onboarding.route
                    ) {
                        composable(Screen.Onboarding.route) {
                            OnboardingScreen(
                                onFinishOnboarding = { name, theme ->
                                    SettingsRepository.updateUserName(name)
                                    SettingsRepository.updateTheme(theme)
                                    SettingsRepository.setOnboardingCompleted(true)
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.Home.route) {
                            HomeScreen(
                                onNavigateToSection = { section ->
                                    val route = when (section) {
                                        "home" -> Screen.Home.route
                                        "intercept" -> Screen.Intercept.route
                                        "apis" -> Screen.Apis.route
                                        "tools" -> Screen.Tools.route
                                        "chats" -> Screen.Chats.route
                                        "notes" -> Screen.Notes.route
                                        "flow" -> Screen.Flow.route
                                        "music" -> Screen.Music.route
                                        "settings" -> Screen.Settings.route
                                        else -> Screen.Home.route
                                    }
                                    // Fix 04: use same nav options as the pill bar so the
                                    // back stack doesn't get corrupted and the bottom nav
                                    // stays responsive after Home shortcuts.
                                    navController.navigate(route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onOpenApiRequest = { req ->
                                    ApiCollectionsRepository.setActiveRequest(req)
                                    navController.navigate(Screen.Apis.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onOpenTrafficDetail = {
                                    navController.navigate(Screen.Intercept.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onOpenNote = {
                                    navController.navigate(Screen.Notes.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onOpenSettings = {
                                    navController.navigate(Screen.Settings.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }

                        composable(Screen.Intercept.route) {
                            InterceptScreen(
                                onSendToTestr = { reqItem ->
                                    ApiCollectionsRepository.saveRequest(reqItem)
                                    ApiCollectionsRepository.setActiveRequest(reqItem)
                                    navController.navigate(Screen.Apis.route)
                                },
                                onMiniSettings = { showMiniSettingsForSection = "Intercept" },
                                onNavigateFullSettings = { navController.navigate(Screen.Settings.route) }
                            )
                        }

                        composable(Screen.Apis.route) {
                            ApiTestrScreen(
                                onMiniSettings = { showMiniSettingsForSection = "APIs" },
                                onSendToIntercept = { reqItem ->
                                    navController.navigate(Screen.Intercept.route)
                                }
                            )
                        }

                        composable(Screen.Tools.route) {
                            ToolsScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Tools" }
                            )
                        }

                        composable(Screen.Chats.route) {
                            ChatScreen(
                                onMiniSettings = { showMiniSettingsForSection = "AI Chat" }
                            )
                        }

                        composable(Screen.Notes.route) {
                            NotesScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Notes" }
                            )
                        }

                        composable(Screen.Flow.route) {
                            FlowScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Flow Automation" }
                            )
                        }

                        composable(Screen.Music.route) {
                            MusicScreen(
                                onBack = { navController.popBackStack() },
                                onMiniSettings = { showMiniSettingsForSection = "Music Lounge" }
                            )
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onReRunWizard = {
                                    navController.navigate(Screen.Onboarding.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }

                    // In-Page Mini Settings Quick Sheet with Section Specific Controls
                    if (showMiniSettingsForSection != null) {
                        val sec = showMiniSettingsForSection!!
                        InPageMiniSettingsSheet(
                            title = "$sec Settings",
                            onDismiss = { showMiniSettingsForSection = null },
                            onNavigateFullSettings = {
                                showMiniSettingsForSection = null
                                navController.navigate(Screen.Settings.route)
                            }
                        ) {
                            SectionSpecificMiniSettings(
                                section = sec,
                                onOpenFullSettings = {
                                    showMiniSettingsForSection = null
                                    navController.navigate(Screen.Settings.route)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionSpecificMiniSettings(
    section: String,
    onOpenFullSettings: () -> Unit
) {
    // Real interception + capability state, shared with the Intercept screen.
    val interceptionState by InterceptionStateRepository.state.collectAsState()
    val capabilities by InterceptionStateRepository.capabilities.collectAsState()

    var autoFollowRedirects by remember { mutableStateOf(true) }
    var strictSsl by remember { mutableStateOf(false) }
    var codeHighlighting by remember { mutableStateOf(true) }
    var markdownPreview by remember { mutableStateOf(true) }
    var stopFlowOnError by remember { mutableStateOf(true) }
    var musicLoFiLoop by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (section) {
            "Intercept" -> {
                // These were toggles in v2.1, implying this sheet could start the VPN or grant
                // root/Frida. It could not -- each switch only flipped a local boolean
                // (APP_v2.md Bug 3.1). They are now honest read-only readouts; capture is
                // started from the Intercept screen FAB, which runs the real pre-flight gate.
                MiniSettingStatusRow(
                    title = "Capture engine",
                    subtitle = when (interceptionState.status) {
                        InterceptionStatus.ACTIVE ->
                            "Capturing on port ${interceptionState.port} - ${interceptionState.tierLabel}"
                        InterceptionStatus.ARMED -> "Armed on port ${interceptionState.port}"
                        InterceptionStatus.ERROR -> interceptionState.errorText ?: "Error"
                        InterceptionStatus.CONFIGURED_IDLE -> "Idle. Start it from the Intercept tab."
                        InterceptionStatus.NEVER_CONFIGURED -> "Not configured yet."
                    },
                    statusLabel = when (interceptionState.status) {
                        InterceptionStatus.ACTIVE -> "Active"
                        InterceptionStatus.ARMED -> "Armed"
                        InterceptionStatus.ERROR -> "Error"
                        InterceptionStatus.CONFIGURED_IDLE -> "Idle"
                        InterceptionStatus.NEVER_CONFIGURED -> "Off"
                    },
                    isPositive = interceptionState.isActive,
                    isNegative = interceptionState.status == InterceptionStatus.ERROR
                )
                MiniSettingStatusRow(
                    title = "frida-server",
                    subtitle = "Checked on 127.0.0.1:27042. Runs outside the app.",
                    statusLabel = when (capabilities.frida) {
                        FridaState.RUNNING -> "Running"
                        FridaState.NOT_RUNNING -> "Not running"
                        FridaState.UNKNOWN -> "Unknown"
                    },
                    isPositive = capabilities.frida == FridaState.RUNNING,
                    isNegative = capabilities.frida == FridaState.NOT_RUNNING
                )
                MiniSettingStatusRow(
                    title = "Root access",
                    subtitle = "Granted by your root manager, not by requestr.",
                    statusLabel = when (capabilities.root) {
                        RootState.GRANTED -> "Granted"
                        RootState.AVAILABLE -> "Available"
                        RootState.DENIED -> "Denied"
                        RootState.UNAVAILABLE -> "Unavailable"
                        RootState.UNKNOWN -> "Unknown"
                    },
                    isPositive = capabilities.root == RootState.GRANTED,
                    isNegative = capabilities.root == RootState.UNAVAILABLE ||
                        capabilities.root == RootState.DENIED
                )
            }
            "APIs" -> {
                MiniSettingToggleRow(
                    title = "Auto-Follow Redirects",
                    subtitle = "Follow 301, 302, 307 HTTP response headers",
                    checked = autoFollowRedirects,
                    onCheckedChange = { autoFollowRedirects = it }
                )
                MiniSettingToggleRow(
                    title = "Strict TLS Verification",
                    subtitle = "Require trusted root CA for outgoing requests",
                    checked = strictSsl,
                    onCheckedChange = { strictSsl = it }
                )
            }
            "AI Chat" -> {
                MiniSettingToggleRow(
                    title = "Code Syntax Highlighting",
                    subtitle = "Format code blocks with editorial luxury styles",
                    checked = codeHighlighting,
                    onCheckedChange = { codeHighlighting = it }
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Active AI Engine", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Gemini 2.5 Flash • Server-Side API Protocol", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            "Notes" -> {
                MiniSettingToggleRow(
                    title = "Real-Time Markdown Preview",
                    subtitle = "Render live headers, bold text and monospace blocks",
                    checked = markdownPreview,
                    onCheckedChange = { markdownPreview = it }
                )
            }
            "Flow Automation" -> {
                MiniSettingToggleRow(
                    title = "Halt Pipeline On Error",
                    subtitle = "Stop execution if any node returns non-2xx status",
                    checked = stopFlowOnError,
                    onCheckedChange = { stopFlowOnError = it }
                )
            }
            "Music Lounge" -> {
                MiniSettingToggleRow(
                    title = "Continuous Lo-Fi Loop",
                    subtitle = "Seamlessly repeat ambient coding tracks",
                    checked = musicLoFiLoop,
                    onCheckedChange = { musicLoFiLoop = it }
                )
            }
            else -> {
                Text(
                    text = "Quick configuration options for $section.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Full App Settings Link Button
        Button(
            onClick = onOpenFullSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Full App Settings", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MiniSettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/**
 * Read-only status row for capabilities the app cannot itself grant (root, Frida, VPN consent).
 *
 * Rendering these as switches was misleading: the user could flip them and nothing changed on
 * the device. This reports what the real probe found instead.
 */
@Composable
fun MiniSettingStatusRow(
    title: String,
    subtitle: String,
    statusLabel: String,
    isPositive: Boolean,
    isNegative: Boolean
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            isPositive -> InterceptorActiveGreen.copy(alpha = 0.18f)
                            isNegative -> InterceptorInactiveRed.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = statusLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        isPositive -> InterceptorActiveGreen
                        isNegative -> InterceptorInactiveRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
