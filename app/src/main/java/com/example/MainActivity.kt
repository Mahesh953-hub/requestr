package com.example

import android.content.Intent
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
import com.example.data.repository.FlowRepository
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
import com.example.ui.components.CollapsibleNavPill
import com.example.ui.components.InPageMiniSettingsSheet
import com.example.ui.components.MusicMiniPlayer
import com.example.ui.screens.ApiTestrScreen
import com.example.ui.screens.FlowScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InterceptScreen
import com.example.ui.screens.MusicScreen
import com.example.ui.screens.NotesScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SavedApisScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.screens.ToolWorkspaceScreen
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Deep-link navigation requests received while the activity was alive. */
        val pendingDeeplinks = java.util.concurrent.ConcurrentLinkedQueue<android.net.Uri>()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Fix #22 (TODO): handle requestr:// deep links arriving while the app is open.
        val data = intent.data
        if (data?.scheme == "requestr") {
            pendingDeeplinks.add(data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fix #43 (TODO): Global keyboard shortcuts for power users.
        // Ctrl+Enter = send request (API Testr), Ctrl+Shift+N = new request,
        // Escape = back navigation.
        window.decorView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val ctrl = event.isCtrlPressed
                val shift = event.isShiftPressed
                when {
                    ctrl && keyCode == android.view.KeyEvent.KEYCODE_ENTER -> {
                        // Broadcast send-request intent — ApiTestrScreen picks it up
                        sendBroadcast(Intent("com.example.requestr.SEND_REQUEST"))
                        true
                    }
                    ctrl && shift && keyCode == android.view.KeyEvent.KEYCODE_N -> {
                        sendBroadcast(Intent("com.example.requestr.NEW_REQUEST"))
                        true
                    }
                    keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> {
                        onBackPressedDispatcher.onBackPressed()
                        true
                    }
                    else -> false
                }
            } else false
        }
        // Load persisted user settings before the UI tree reads them.
        // Fix: v2.1 initialised SettingsRepository with fake true defaults
        // (isRootDetected, isShizukuAvailable, isLsposedInstalled) and
        // never persisted theme choice, so the app lost its theme on restart
        // and briefly rendered a blank screen before the Compose tree settled.
        SettingsRepository.init(applicationContext)
        // v3.1 fix (A4): backs ApiCollectionsRepository's saved-requests state with the real
        // Room-persisted `saved_api_requests` table instead of an in-memory-only StateFlow.
        ApiCollectionsRepository.init(applicationContext)
        // v3.5: backs FlowRepository's nodes/connections from Room so positions survive restart.
        FlowRepository.init(applicationContext)
        // v3.5.1: Seed default intercept rules on first launch (ad blockers, auth loggers, etc.)
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            com.example.data.interception.CaptureLogRepository.seedDefaultRules(applicationContext)
        }
        // v4.0: Register built-in plugins at startup
        com.example.plugin.PluginManager.registerBuiltinPlugins(listOf(
            com.example.plugin.JsonPrettifierPlugin()
        ))
        // v4.0: Scan for external plugins on /sdcard/requestr/plugins/
        com.example.plugin.PluginManager.scanExternalPlugins(applicationContext)
        // v4.0: Initialize Local API server (doesn't start it)
        com.example.api.LocalApiServer.init(applicationContext)
        // v3.2 Part C (C1): loads persisted Environments from the unified DataStore (was never
        // initialised before C1, so ApiTestrScreen's EnvironmentRepository.active() always read
        // the in-memory seed defaults only — now it reflects migrated + newly-saved data too).
        com.example.data.env.EnvironmentRepository.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            RequestrApp()
        }
    }
}

/**
 * Fix #22 (TODO): routes a requestr:// deep link to the matching screen.
 * Supported routes: requestr://flow/execute, requestr://intercept,
 * requestr://apis, requestr://tools, requestr://notes, requestr://music,
 * requestr://settings, requestr://saved
 */
private fun handleRequestrDeeplink(
    navController: androidx.navigation.NavHostController,
    uri: android.net.Uri,
) {
    val route = when (uri.host) {
        "flow" -> Screen.Flow.route
        "intercept" -> Screen.Intercept.route
        "apis" -> Screen.Apis.route
        "tools" -> Screen.Tools.route
        "notes" -> Screen.Notes.route
        "music" -> Screen.Music.route
        "settings" -> Screen.Settings.route
        "chat" -> "chat"
        "saved" -> Screen.SavedApis.route
        "home" -> Screen.Home.route
        "flow/execute" -> Screen.Flow.route
        else -> uri.path?.trim('/')?.takeIf { it.isNotBlank() }?.let { path ->
            when (path.split("/").first()) {
                "flow" -> Screen.Flow.route
                "intercept" -> Screen.Intercept.route
                "apis", "api" -> Screen.Apis.route
                "tools" -> Screen.Tools.route
                "notes" -> Screen.Notes.route
                "music" -> Screen.Music.route
                "settings" -> Screen.Settings.route
                "saved" -> Screen.SavedApis.route
                else -> null
            }
        }
    } ?: return

    try {
        navController.navigate(route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    } catch (_: Exception) {
        // Navigation not ready yet — ignore, deep link is best-effort
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Intercept : Screen("intercept", "Intercept", Icons.Default.Radar)
    object Apis : Screen("apis", "APIs", Icons.Default.Public)
    object Tools : Screen("tools", "Tools", Icons.Default.Tune)
    object Notes : Screen("notes", "Notes", Icons.Default.Notes)
    object Flow : Screen("flow", "Flow", Icons.Default.Code)
    object Music : Screen("music", "Music", Icons.Default.MusicNote)
    object Settings : Screen("settings", "Settings", Icons.Default.Tune)
    object SavedApis : Screen("saved_apis", "Saved APIs", Icons.Default.Save)
    object Onboarding : Screen("onboarding", "Onboarding", Icons.Default.Home)
    data class ToolWorkspace(val toolId: String) : Screen(
        "tool_workspace/{toolId}", "Tool", Icons.Default.Tune
    ) {
        companion object {
            fun createRoute(toolId: String) = "tool_workspace/$toolId"
        }
    }
}

@Composable
fun RequestrApp() {
    val settings by SettingsRepository.settings.collectAsState()
    // v3.1 B1: SYSTEM appearance mode must actually follow the OS light/dark setting —
    // previously this function only ever inspected settings.themeMode, so "SYSTEM" had
    // zero effect on which theme rendered (themeMode was always whatever the user last
    // explicitly picked, regardless of the OS setting). Now: when appearanceMode is
    // SYSTEM, resolve to the user's remembered per-family theme (selectedLightTheme /
    // selectedDarkTheme) based on the real OS dark-theme state; LIGHT/DARK modes ignore
    // the OS state entirely and just render settings.themeMode as already resolved by
    // SettingsScreen's AppearanceSelector.
    val systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val effectiveThemeKey = remember(settings.themeMode, settings.appearanceMode, settings.selectedLightTheme, settings.selectedDarkTheme, systemInDarkTheme) {
        when (settings.appearanceMode.uppercase()) {
            "SYSTEM" -> if (systemInDarkTheme) settings.selectedDarkTheme else settings.selectedLightTheme
            else -> settings.themeMode
        }
    }
    val themeMode = remember(effectiveThemeKey) {
        when (effectiveThemeKey.uppercase()) {
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
      // v3.1 B8: "Display Scale" (SMALL/NORMAL/LARGE) — override LocalDensity's fontScale
      // so every sp-sized text and dp-based layout below actually renders larger/smaller,
      // instead of the setting being stored and never read.
      val baseDensity = androidx.compose.ui.platform.LocalDensity.current
      val displayScaleFactor = when (settings.displayScale.uppercase()) {
          "SMALL" -> 0.9f
          "LARGE" -> 1.15f
          else -> 1.0f
      }
      val scaledDensity = remember(baseDensity, displayScaleFactor) {
          androidx.compose.ui.unit.Density(
              density = baseDensity.density,
              fontScale = baseDensity.fontScale * displayScaleFactor
          )
      }
      CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides scaledDensity) {
      // v3.1 B8: provide the real animation gating (LocalAnimationsEnabled /
      // LocalAnimationDurationScale) from live settings so every consumer below reads
      // the user's actual Animation / Transition Behavior preference instead of always
      // animating at full effect.
      com.example.ui.theme.ProvideAnimationConfig(
          animationsEnabled = settings.animationEnabled,
          transitionBehavior = settings.transitionBehavior
      ) {
      // v3.1 B7: provide the real density gating (LocalAppSpacing) from live settings so
      // list/card composables read the user's actual Compact/Comfortable preference
      // instead of hardcoding their own spacing regardless of the setting.
      com.example.ui.theme.ProvideDensityConfig(densityMode = settings.densityMode) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

        // Fix #22 (TODO): drain pending requestr:// deep links and navigate.
        val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
        LaunchedEffect(Unit) {
            val initialUri = activity?.intent?.data
            if (initialUri?.scheme == "requestr") {
                handleRequestrDeeplink(navController, initialUri)
            }
            while (true) {
                val uri = MainActivity.pendingDeeplinks.poll() ?: break
                handleRequestrDeeplink(navController, uri)
            }
        }

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
            Screen.Notes.route,
            Screen.Flow.route,
            Screen.Music.route,
            "chat"
        )

        var isMiniPlayerDismissed by remember { mutableStateOf(false) }
        val showPillNav = currentRoute in rootRoutes && settings.onboardingCompleted

        // ── Scroll-driven nav pill collapse/expand signals ──────
        var scrollCollapseSignal by remember { mutableStateOf(false) }
        var scrollExpandSignal by remember { mutableStateOf(false) }
        val scrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                    if (available.y < -2f) {
                        scrollCollapseSignal = true; scrollExpandSignal = false
                    } else if (available.y > 2f) {
                        scrollCollapseSignal = false; scrollExpandSignal = true
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (available.y < -500f) {
                        scrollCollapseSignal = true; scrollExpandSignal = false
                    }
                    return Velocity.Zero
                }
            }
        }

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
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollConnection),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (showPillNav) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                        ) {
                            // Music mini-player — independent floating element per spec §1-B
                            if (currentRoute != Screen.Music.route && !isMiniPlayerDismissed) {
                                MusicMiniPlayer(
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

                            // Collapsible glassmorphic navigation pill per requestr_nav_pill_spec.md
                            CollapsibleNavPill(
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
                                scrollSignalCollapse = scrollCollapseSignal,
                                scrollSignalExpand = scrollExpandSignal
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
                                    SettingsRepository.updateThemeSelection(theme)
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
                                        "notes" -> Screen.Notes.route
                                        "flow" -> Screen.Flow.route
                                        "music" -> Screen.Music.route
                                        "chat" -> "chat"
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
                                onNavigateToSaved = {
                                    navController.navigate(Screen.SavedApis.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onSendToIntercept = { reqItem ->
                                    navController.navigate(Screen.Intercept.route)
                                }
                            )
                        }

                        composable(Screen.SavedApis.route) {
                            SavedApisScreen(
                                onBack = { navController.popBackStack() },
                                onOpenRequest = { req ->
                                    ApiCollectionsRepository.setActiveRequest(req)
                                    navController.navigate(Screen.Apis.route) {
                                        popUpTo(Screen.SavedApis.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.Tools.route) {
                            ToolsScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Tools" },
                                onNavigateToTool = { toolId ->
                                    navController.navigate(Screen.ToolWorkspace.createRoute(toolId))
                                }
                            )
                        }

                        composable(
                            route = "tool_workspace/{toolId}",
                            arguments = listOf(
                                androidx.navigation.navArgument("toolId") {
                                    type = androidx.navigation.NavType.StringType
                                }
                            )
                        ) { backStackEntry ->
                            val toolId = backStackEntry.arguments?.getString("toolId") ?: ""
                            ToolWorkspaceScreen(
                                toolId = toolId,
                                onBack = { navController.popBackStack() }
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

                        composable("chat") {
                            ChatScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Chats" }
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
        // B12: the "Open Full App Settings" affordance now lives exactly once, in the
        // InPageMiniSettingsSheet chrome that wraps this content (see MainActivity's call site
        // and CommonComponents.kt's InPageMiniSettingsSheet). This composable no longer renders
        // its own duplicate copy.
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
