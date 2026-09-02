package com.example

// TrafficInterceptorRepository import removed — all state flows now come from InterceptionStateRepository
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.OverscrollConfiguration
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.InterceptionStatus
import com.example.data.interception.RootState
import com.example.data.models.ApiRequestItem
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.NoteItem
import com.example.data.repository.ApiCollectionsRepository
import com.example.data.repository.FlowRepository
import com.example.data.repository.MusicRepository
import com.example.data.repository.NotesRepository
import com.example.data.repository.RecentActivityRepository
import com.example.data.repository.SettingsRepository
import com.example.ui.components.AppNavigationDrawer
import com.example.ui.components.CollapsibleNavPill
import com.example.ui.components.GlassBackdropSource
import com.example.ui.components.GlassBackdropState
import com.example.ui.components.GlassCard
import com.example.ui.components.InPageMiniSettingsSheet
import com.example.ui.components.LocalGlassBackdropState
import com.example.ui.components.LocalParallaxState
import com.example.ui.components.MusicMiniPlayer
import com.example.ui.components.ParallaxBackground
import com.example.ui.components.ParallaxState
import com.example.ui.components.rememberGlassBackdropState
import com.example.ui.screens.ApiTestrScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.FlowScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InterceptScreen
import com.example.ui.screens.MusicScreen
import com.example.ui.screens.NotesScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SavedApisScreen
import com.example.ui.screens.RecentlyOpenedScreen
import com.example.ui.screens.RecentActivityNavigator
import com.example.data.repository.ChatRepository
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolWorkspaceScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorInactiveRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.rememberEffectiveDurationScale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Deep-link URIs received while the activity is alive. Replaces the previous
         * `ConcurrentLinkedQueue` + one-shot `LaunchedEffect(Unit)` drain, which missed every
         * link that arrived after the initial poll loop hit its first empty poll and `break`d —
         * i.e. every link received via onNewIntent while the app was already open, exactly the
         * case the old "Fix #22" comment claimed to handle. A SharedFlow collected in a
         * LaunchedEffect keyed on navController stays subscribed for the composable's lifetime.
         */
        val deeplinkEvents = kotlinx.coroutines.flow.MutableSharedFlow<android.net.Uri>(extraBufferCapacity = 8)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data
        if (data?.scheme == "requestr") {
            deeplinkEvents.tryEmit(data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Global keyboard shortcuts for power users: Ctrl+Enter = send request (API Testr),
        // Ctrl+Shift+N = new request, Escape = back navigation. Send/New now go through
        // com.example.AppShortcuts (an in-process SharedFlow ApiTestrScreen collects) instead
        // of sendBroadcast() to a receiver that never existed.
        window.decorView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val ctrl = event.isCtrlPressed
                val shift = event.isShiftPressed
                when {
                    ctrl && keyCode == android.view.KeyEvent.KEYCODE_ENTER -> {
                        com.example.AppShortcuts.emit(com.example.ShortcutEvent.SendRequest)
                        true
                    }
                    ctrl && shift && keyCode == android.view.KeyEvent.KEYCODE_N -> {
                        com.example.AppShortcuts.emit(com.example.ShortcutEvent.NewRequest)
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
        // Task 11: backs NotesRepository (notes/note_folders), ToolsRepository's custom-tool
        // map, and ChatRepository's message history from the five app-scope Room tables
        // transport shipped in schema v9 -- previously all three lived only in a plain
        // MutableStateFlow/ConcurrentHashMap and were wiped on process death.
        NotesRepository.init(applicationContext)
        RecentActivityRepository.init(applicationContext)
        com.example.data.repository.ToolsRepository.init(applicationContext)
        com.example.data.repository.ChatRepository.init(applicationContext)
        // R3 fix: gives ApiTestrRepository a Context so its OkHttpClient can attach
        // OkHttpProxyInterceptor, wiring requestr's own API Testr traffic into the OkHttp
        // runtime-instrumentation plane (see OkHttpAdapter in HttpStackAdapters.kt).
        com.example.data.repository.ApiTestrRepository.init(applicationContext)
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
        // T2: Initialize Gateway mode engine (doesn't start it -- see SettingsScreen's
        // "GATEWAY MODE" card for the manual start/stop toggle).
        com.example.interception.gateway.GatewayEngine.init(applicationContext)
        // v5.5 (R1+R3), extended in W1-W3 (runtime-instrumentation scope): register the
        // HTTP-stack instrumentation adapters and start the engine so
        // RuntimeInstrumentationEngine is no longer fully dead code — it polls adapter
        // events, feeds them into CorrelationEngine as RUNTIME-plane ObservationEvents,
        // and (via RuntimeTrafficSink) persists them as real CapturedTrafficEntity rows.
        // All three adapters now report genuine captured traffic: OkHttp via
        // OkHttpProxyInterceptor, Cronet via a real RequestFinishedInfo.Listener
        // (CronetInstrumentation), and WebView via shouldInterceptRequest + a MAIN-world
        // fetch/XHR JS bridge (WebViewInstrumentation).
        com.example.runtime.RuntimeInstrumentationEngine.registerAdapter(com.example.runtime.http.OkHttpAdapter())
        com.example.runtime.RuntimeInstrumentationEngine.registerAdapter(com.example.runtime.http.CronetAdapter())
        com.example.runtime.RuntimeInstrumentationEngine.registerAdapter(com.example.runtime.http.WebViewAdapter())
        com.example.runtime.RuntimeInstrumentationEngine.registerSink(com.example.runtime.RuntimeTrafficSink(applicationContext))
        com.example.runtime.RuntimeInstrumentationEngine.start(applicationContext)
        // B1-B6: initialize the Browser Bridge manager with an app context so its
        // CdpFetchAdapter (B3) can read the shared intercept_rules Room table. This does NOT
        // connect to Chrome yet -- that only happens when the user taps "Connect" in
        // Settings -> Browser Bridge (see SettingsScreen.kt), same lazy-start pattern as
        // LocalApiServer.init() above.
        com.example.browser.BrowserBridgeManager.init(applicationContext)
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
 * Executes a parsed [com.example.DeeplinkRouter.DeeplinkAction] — navigating **and** acting,
 * unlike the previous `handleRequestrDeeplink` which only ever mapped a host to a route (so
 * the rows advertised in Settings — `api/run`, `flow/execute`, `note/create` — never did
 * anything beyond whatever plain navigation their host happened to also match).
 *
 * `intercept/toggle` is deliberately not one of these actions: driving start/stop needs the
 * Activity-scoped VPN consent launcher that only `rememberCaptureController` can provide, and
 * that row was removed from the Settings deep-link list to match (see DeeplinkRouter.kt).
 */
private suspend fun executeDeeplinkAction(
    action: com.example.DeeplinkRouter.DeeplinkAction,
    navController: androidx.navigation.NavHostController,
) {
    fun navigate(route: String) {
        try {
            navController.navigate(route) {
                popUpTo(Screen.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } catch (e: Exception) {
            android.util.Log.w("Deeplink", "Navigation to '$route' failed: ${e.message}")
        }
    }

    when (action) {
        is com.example.DeeplinkRouter.DeeplinkAction.Navigate -> navigate(action.route)

        is com.example.DeeplinkRouter.DeeplinkAction.RunApi -> {
            navigate(Screen.Apis.route)
            val saved = ApiCollectionsRepository.requests.value.find { it.id == action.requestId }
            if (saved == null) {
                android.util.Log.w("Deeplink", "api/run: no saved request with id '${action.requestId}'")
            } else {
                ApiCollectionsRepository.setActiveRequest(saved)
                try {
                    com.example.data.repository.ApiTestrRepository.executeRequest(saved)
                } catch (e: Exception) {
                    android.util.Log.w("Deeplink", "api/run: request '${action.requestId}' failed: ${e.message}")
                }
            }
        }

        is com.example.DeeplinkRouter.DeeplinkAction.ExecuteFlow -> {
            navigate(Screen.Flow.route)
            try {
                FlowRepository.executeFlow()
            } catch (e: Exception) {
                android.util.Log.w("Deeplink", "flow/execute failed: ${e.message}")
            }
        }

        is com.example.DeeplinkRouter.DeeplinkAction.CreateNote -> {
            NotesRepository.saveNote(NoteItem(title = action.title))
            navigate(Screen.Notes.route)
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Intercept : Screen("intercept", "Intercept", Icons.Default.Radar)
    object Apis : Screen("apis", "APIs", Icons.Default.Public)
    object Tools : Screen("tools", "Tools", Icons.Default.Tune)
    object Notes : Screen("notes", "Notes", Icons.AutoMirrored.Filled.Notes)
    object Flow : Screen("flow", "Flow", Icons.Default.Code)
    object Music : Screen("music", "Music", Icons.Default.MusicNote)
    object Settings : Screen("settings", "Settings", Icons.Default.Tune)
    object SavedApis : Screen("saved_apis", "Saved APIs", Icons.Default.Save)
    object RecentlyOpened : Screen("recently_opened", "Recently opened", Icons.Default.History)
    object Onboarding : Screen("onboarding", "Onboarding", Icons.Default.Home)
    data class ToolWorkspace(val toolId: String) : Screen(
        "tool_workspace/{toolId}", "Tool", Icons.Default.Tune
    ) {
        companion object {
            fun createRoute(toolId: String) = "tool_workspace/$toolId"
        }
    }
}

// =============================================================================================
// Design system — screen_transitions
// =============================================================================================

/**
 * Left-to-right order of the bottom-nav tabs. Mirrors `CollapsibleNavPill.DEFAULT_NAV`; hidden
 * tabs (settings.navBarHiddenItems) do not change the relative order of the remaining ones, so
 * the canonical list is the right basis for direction even when some tabs are hidden.
 */
val BOTTOM_TAB_ORDER: List<String> = listOf(
    Screen.Home.route,
    Screen.Intercept.route,
    Screen.Apis.route,
    Screen.Tools.route,
    Screen.Flow.route,
    Screen.Music.route,
)

/** Base duration of a screen transition before the user's animation scale is applied. */
const val SCREEN_TRANSITION_MS = 320

/**
 * The spatial relationship between the screen being left and the screen being entered.
 *
 * - [LATERAL_FORWARD] / [LATERAL_BACKWARD]: both routes are bottom tabs. Content slides
 *   sideways and fades in the direction the user moved along the tab bar — the spatial cue
 *   the brief asks for. Forward = the target tab sits to the right.
 * - [DEPTH_FORWARD] / [DEPTH_BACKWARD]: one side is not a tab (Settings, Saved APIs, Tool
 *   Workspace, Notes, Chat). Those routes have no position on the tab bar, so a lateral
 *   direction would be arbitrary; they read as a *push* into a detail (content arrives from the
 *   right while the tab recedes) and a *pop* back out of it. Notes and Chat are deliberately in
 *   this group: they are reached from Home cards / the drawer, never from the pill.
 * - [FADE]: no spatial relationship (Onboarding in or out, or the same route re-selected).
 */
enum class ScreenTransition { LATERAL_FORWARD, LATERAL_BACKWARD, DEPTH_FORWARD, DEPTH_BACKWARD, FADE }

/**
 * Pure resolver — unit-tested in `ScreenTransitionResolverTest`.
 *
 * @param fromRoute route pattern of the destination being left (`null` on first composition).
 * @param toRoute route pattern of the destination being entered.
 * @param isPop whether this navigation is a back-stack pop (NavHost's popEnter/popExit).
 * @param tabOrder left-to-right tab routes; defaults to [BOTTOM_TAB_ORDER].
 */
fun resolveScreenTransition(
    fromRoute: String?,
    toRoute: String?,
    isPop: Boolean,
    tabOrder: List<String> = BOTTOM_TAB_ORDER,
): ScreenTransition {
    if (fromRoute == null || toRoute == null) return ScreenTransition.FADE
    if (fromRoute == Screen.Onboarding.route || toRoute == Screen.Onboarding.route) return ScreenTransition.FADE
    val fromIdx = tabOrder.indexOf(fromRoute)
    val toIdx = tabOrder.indexOf(toRoute)
    return when {
        fromIdx >= 0 && toIdx >= 0 -> when {
            toIdx > fromIdx -> ScreenTransition.LATERAL_FORWARD
            toIdx < fromIdx -> ScreenTransition.LATERAL_BACKWARD
            else -> ScreenTransition.FADE
        }
        // Target is a tab, source is a detail: we are coming back out of the detail regardless
        // of whether the user pressed back or tapped a tab in the drawer.
        toIdx >= 0 -> ScreenTransition.DEPTH_BACKWARD
        // Target is a detail: push forward unless this is a back-stack pop between two details.
        isPop -> ScreenTransition.DEPTH_BACKWARD
        else -> ScreenTransition.DEPTH_FORWARD
    }
}

/**
 * Fraction of the container width the lateral slide travels. Combined with the fade this reads
 * as a Material "shared axis" move rather than a full page push, which is what keeps six tabs
 * from feeling like a carousel.
 */
private const val LATERAL_SLIDE_FRACTION = 0.22f

/** Fraction of the container width a depth push/pop travels. */
private const val DEPTH_SLIDE_FRACTION = 0.10f

/** Scale the receding screen settles at during a depth push (and starts from on pop). */
private const val DEPTH_RECEDE_SCALE = 0.96f

/**
 * Enter transition for [transition] at [durationMs]. A zero duration (animations disabled or
 * Transition Behavior = NONE) returns [EnterTransition.None] so the swap is instant.
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.screenEnterTransition(
    transition: ScreenTransition,
    durationMs: Int,
): EnterTransition {
    if (durationMs <= 0) return EnterTransition.None
    val fade = fadeIn(tween(durationMs, delayMillis = durationMs / 6, easing = LinearOutSlowInEasing))
    return when (transition) {
        ScreenTransition.LATERAL_FORWARD -> slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        ) { (it * LATERAL_SLIDE_FRACTION).toInt() } + fade
        ScreenTransition.LATERAL_BACKWARD -> slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        ) { (it * LATERAL_SLIDE_FRACTION).toInt() } + fade
        ScreenTransition.DEPTH_FORWARD -> slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        ) { (it * DEPTH_SLIDE_FRACTION).toInt() } + fade
        ScreenTransition.DEPTH_BACKWARD -> scaleIn(
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
            initialScale = DEPTH_RECEDE_SCALE,
        ) + fade
        ScreenTransition.FADE -> fadeIn(tween(durationMs, easing = LinearOutSlowInEasing))
    }
}

/**
 * Exit transition for [transition] at [durationMs]. Mirrors [screenEnterTransition]; the
 * outgoing screen fades over the first half so the two never sit fully blended mid-flight.
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.screenExitTransition(
    transition: ScreenTransition,
    durationMs: Int,
): ExitTransition {
    if (durationMs <= 0) return ExitTransition.None
    val fade = fadeOut(tween((durationMs / 2).coerceAtLeast(1), easing = FastOutSlowInEasing))
    return when (transition) {
        ScreenTransition.LATERAL_FORWARD -> slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        ) { (it * LATERAL_SLIDE_FRACTION).toInt() } + fade
        ScreenTransition.LATERAL_BACKWARD -> slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        ) { (it * LATERAL_SLIDE_FRACTION).toInt() } + fade
        ScreenTransition.DEPTH_FORWARD -> scaleOut(
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
            targetScale = DEPTH_RECEDE_SCALE,
        ) + fade
        ScreenTransition.DEPTH_BACKWARD -> slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        ) { (it * DEPTH_SLIDE_FRACTION).toInt() } + fade
        ScreenTransition.FADE -> fadeOut(tween(durationMs, easing = FastOutSlowInEasing))
    }
}

// =============================================================================================
// Design system — glass nav pill hosting (bottom overlay + refraction backdrop)
// =============================================================================================

/**
 * Height (excluding the navigation-bar inset) of the floating bottom overlay — mini player plus
 * nav pill — currently drawn over the content. 0.dp when the pill is hidden. Provided by
 * `RequestrApp` around the NavHost so a screen that scrolls under the pill can size its own
 * bottom content padding from the real overlay instead of a fixed guess.
 */
val LocalBottomOverlayHeight: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }

/**
 * Routes whose content is laid out *under* the floating nav pill so the pill's glass actually
 * has moving content to blur and refract. Every other route keeps the pre-glass behaviour —
 * its content box is inset by the overlay height, exactly what `Scaffold(bottomBar)` used to do —
 * because those screens do not reserve any bottom space of their own (the only screens that pad
 * for the pill via `fabBottomSpacingDp` are Home, RecentlyOpened and part of ApiTestr).
 *
 * To let another screen scroll under the pill: give its scroll container a bottom
 * `contentPadding` of at least `LocalBottomOverlayHeight.current` (+ breathing room) and add its
 * route here. Doing this without the padding hides the last row behind the pill.
 *
 * Rule applied per route: added when the default view is a scrolling container AND every other
 * mode of that screen reserves the overlay height explicitly.
 *  - home   — LazyColumn pads `LocalBottomOverlayHeight + 16.dp`.
 *  - tools  — LazyVerticalGrid pads the overlay; search/chips are top-anchored.
 *  - music  — playlist LazyColumn and the snackbar pad the overlay; permission/empty states are
 *             top-anchored; the mini player is hidden on this route.
 *  - notes  — list LazyColumn pads the overlay; the editor mode pads its root column and
 *             snackbar by the overlay height instead.
 * Deliberately NOT added: flow (canvas mini-map and snackbar are bottom-anchored; the canvas is
 * an opaque surface card anyway), apis (fixed SendFloatingBar at the bottom), intercept (owned
 * by a different lane).
 */
val ROUTES_SCROLLING_UNDER_PILL: Set<String> = setOf(
    Screen.Home.route,
    Screen.Tools.route,
    Screen.Music.route,
    Screen.Notes.route,
)

/** Bottom inset a destination's content box gets; pure so it is unit-testable. */
fun bottomOverlayInsetFor(route: String, overlayHeight: Dp): Dp =
    if (route in ROUTES_SCROLLING_UNDER_PILL) 0.dp else overlayHeight

/**
 * `composable(...)` plus the per-destination bottom inset. Applying the inset per destination
 * (rather than on the NavHost) means a lateral/depth transition between an under-pill route and
 * an inset route never resizes mid-flight: each screen is laid out at its own final size.
 */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        val inset = bottomOverlayInsetFor(route, LocalBottomOverlayHeight.current)
        Box(modifier = Modifier.fillMaxSize().padding(bottom = inset)) {
            content(entry)
        }
    }
}

// =============================================================================================
// Design system — theme_switching (activity side)
// =============================================================================================

/**
 * Resolve a persisted theme key to the family to render. Delegates to
 * [AppThemeMode.fromKey] (enum name or legacy alias) and falls back to Midnight Sapphire for
 * an unrecognised key, exactly as the previous inline `when` did.
 */
fun resolveAppThemeMode(effectiveThemeKey: String): AppThemeMode =
    AppThemeMode.fromKey(effectiveThemeKey) ?: AppThemeMode.MIDNIGHT_SAPPHIRE

/**
 * Keeps the Activity window in lock-step with the composed theme so a Light <-> Dark switch is a
 * single-frame colour inversion with nothing at the window level lagging behind:
 *
 *  - System-bar icon appearance follows the *in-app* theme, not the OS setting. The default
 *    `enableEdgeToEdge()` in `onCreate` decides light/dark icons from the OS configuration, so
 *    a user on a light OS who picks a dark in-app theme got dark icons on a dark background
 *    until now. Re-applying `enableEdgeToEdge` with an explicit `detectDarkMode` flips the
 *    WindowInsetsController flags immediately; there is no animation on that path.
 *  - The window background is set to the theme background. Compose paints every pixel once
 *    the tree is up, but the window background is what shows through during an IME-driven
 *    relayout or a surface resize; leaving it at the XML default (device-default light or dark)
 *    is how a white flash sneaks into an otherwise instant switch.
 *
 * Runs as a [SideEffect] so it is applied in the same frame as the recomposition that changed
 * the colours, and only when a value actually changed.
 */
@Composable
private fun SyncWindowWithTheme(isDarkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    val applied = remember { WindowThemeApplied() }
    SideEffect {
        if (applied.isDark == isDarkTheme && applied.backgroundArgb == backgroundArgb) return@SideEffect
        val activity = view.context as? ComponentActivity ?: return@SideEffect
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { isDarkTheme },
            navigationBarStyle = SystemBarStyle.auto(
                EDGE_TO_EDGE_LIGHT_SCRIM,
                EDGE_TO_EDGE_DARK_SCRIM,
            ) { isDarkTheme },
        )
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(backgroundArgb))
        applied.isDark = isDarkTheme
        applied.backgroundArgb = backgroundArgb
    }
}

private class WindowThemeApplied {
    var isDark: Boolean? = null
    var backgroundArgb: Int? = null
}

// The scrims androidx.activity applies on API < 29 three-button navigation bars; the library
// keeps its own copies private, so they are mirrored here to keep the default look.
private val EDGE_TO_EDGE_LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val EDGE_TO_EDGE_DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

// OverscrollConfiguration / LocalOverscrollConfiguration are @ExperimentalFoundationApi in
// Compose Foundation 1.7.2 (the pinned version). They are the only public overscroll surface in
// this version; see the scrolling_physics comment inside for why the BOM must not be bumped.
@OptIn(ExperimentalFoundationApi::class)
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
    // Design system — theme_switching. Resolution goes through AppThemeMode.fromKey so the two
    // Phase B families (PASTEL_LIGHT / DEEP_OLIVE_DARK) resolve to their own canvas instead of
    // falling through to the dark default, and the composed (family × accent) engine renders
    // them. The switch itself is a plain recomposition: no Crossfade, no animateColorAsState,
    // nothing between the setting flipping and every colorScheme reader repainting in one frame.
    val themeMode = remember(effectiveThemeKey) { resolveAppThemeMode(effectiveThemeKey) }

    val selectedAccent = remember(settings.selectedAccent) { AppAccent.fromKey(settings.selectedAccent) }
    MyApplicationTheme(themeMode = themeMode, accent = selectedAccent) {
      // Window-level pieces (system-bar icon appearance, window background) follow the same
      // frame, so the inversion is complete — see SyncWindowWithTheme.
      SyncWindowWithTheme(isDarkTheme = !themeMode.isLight)
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
      // Design system — scrolling_physics. One app-wide overscroll configuration: every
      // LazyColumn / LazyRow / verticalScroll below resolves its overscroll effect from this
      // CompositionLocal (ScrollableDefaults.overscrollEffect()), so no screen needs touching.
      // On API 31+ the effect is the platform stretch — the kinetic, velocity-absorbing elastic
      // bounce at both ends of a list; on API 24–30 the platform has no stretch and this
      // resolves to the edge glow, tinted with the accent instead of the stock grey.
      // Compose Foundation here is 1.7.2: OverscrollConfiguration / LocalOverscrollConfiguration
      // are the supported surface (marked @ExperimentalFoundationApi; deprecated only from 1.8),
      // and rememberOverscrollEffect / OverscrollFactory do not exist yet. Do not bump the BOM
      // to chase the newer API.
      val overscrollGlow = MaterialTheme.colorScheme.primary
      val overscrollConfiguration = remember(overscrollGlow) { OverscrollConfiguration(glowColor = overscrollGlow) }
      // Design system — glassmorphism base layer. One ParallaxState for the whole app; screens
      // bind their scroll container with DriveParallaxFrom(...) and glass components read the
      // current shift from LocalParallaxState.
      val parallaxState = remember { ParallaxState() }
      CompositionLocalProvider(
          LocalOverscrollConfiguration provides overscrollConfiguration,
          LocalParallaxState provides parallaxState,
      ) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

        // Lateral parallax drift follows the active tab; detail routes leave it where the
        // underlying tab put it so returning from Settings does not move the background.
        val activeTabIndex = BOTTOM_TAB_ORDER.indexOf(currentRoute)
        if (activeTabIndex >= 0) {
            parallaxState.setLateralTabPosition(activeTabIndex, BOTTOM_TAB_ORDER.size)
        }

        // Design system — screen_transitions. Duration runs through the same animation scale as
        // scaledTween; 0 means every NavHost transition resolves to EnterTransition.None /
        // ExitTransition.None and the swap is instant.
        val transitionDurationMs = (SCREEN_TRANSITION_MS * rememberEffectiveDurationScale()).toInt()

        // Drain requestr:// deep links: the initial launch intent (if any) plus every uri
        // MainActivity.onNewIntent() emits afterwards. Keyed on navController (not Unit) so
        // the collector stays subscribed for the composable's lifetime instead of draining
        // once and then going deaf to subsequent deep-link taps while the app is foregrounded.
        val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
        LaunchedEffect(navController) {
            val initialUri = activity?.intent?.data
            if (initialUri?.scheme == "requestr") {
                com.example.DeeplinkRouter.parse(initialUri)?.let { executeDeeplinkAction(it, navController) }
            }
            MainActivity.deeplinkEvents.collect { uri ->
                com.example.DeeplinkRouter.parse(uri)?.let { executeDeeplinkAction(it, navController) }
            }
        }

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val coroutineScope = rememberCoroutineScope()

        val isMusicPlaying by MusicRepository.isPlaying.collectAsState()
        val currentTrack by MusicRepository.currentTrack.collectAsState()

        var showMiniSettingsForSection by remember { mutableStateOf<String?>(null) }

        // Routes that show the floating nav pill. Chat (Reqy) is intentionally absent: the
        // navigation_showcase mockup disables the pill there and gives it a dedicated back
        // capsule (ChatScreen.onBack), the same treatment Settings gets.
        val rootRoutes = listOf(
            Screen.Home.route,
            Screen.Intercept.route,
            Screen.Apis.route,
            Screen.Tools.route,
            Screen.Notes.route,
            Screen.Flow.route,
            Screen.Music.route,
        )

        var isMiniPlayerDismissed by remember { mutableStateOf(false) }
        val showPillNav = currentRoute in rootRoutes && settings.onboardingCompleted

        // Scroll-driven nav pill collapse signals
        var scrollDown by remember { mutableStateOf(false) }
        var scrollUp by remember { mutableStateOf(false) }
        val scrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < -3f) { scrollDown = true; scrollUp = false }
                    else if (available.y > 3f) { scrollDown = false; scrollUp = true }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (available.y < -400f) { scrollDown = true; scrollUp = false }
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
          // Design system — glass. The refraction under the nav pill needs the pill to be drawn
          // AFTER, and NOT inside, the content it samples (GlassBackdropSource records its
          // subtree into a GraphicsLayer; a consumer inside that subtree would be drawing the very
          // layer it is being recorded into). Scaffold(bottomBar) made the pill a descendant of
          // the content, so it is now a later sibling in this Box instead.
          val backdrop = rememberGlassBackdropState()
          var bottomOverlayHeightPx by remember { mutableIntStateOf(0) }
          val bottomOverlayHeight = with(LocalDensity.current) {
              if (showPillNav) bottomOverlayHeightPx.toDp() else 0.dp
          }
          Box(modifier = Modifier.fillMaxSize()) {
            GlassBackdropSource(
                state = backdrop,
                modifier = Modifier.fillMaxSize()
            ) {
              Box(modifier = Modifier.fillMaxSize()) {
                // Base layer: the parallax background is the page background and is INSIDE the
                // backdrop source, so the glass refracts the moving base layer as well as the
                // content (the "base layer for glassmorphism refraction" in the brief). It paints
                // colorScheme.background itself; nothing above it fills the page.
                ParallaxBackground(modifier = Modifier.fillMaxSize(), state = parallaxState)
                // Scaffold is kept only as the inset provider it always was: innerPadding is the
                // status bar (top) and navigation bar (bottom) — IME handling was never Scaffold's
                // here (manifest windowSoftInputMode) and is unchanged. The bottom-bar height it
                // used to add is re-applied per destination by NavGraphBuilder.screen(...).
                Scaffold(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                    containerColor = Color.Transparent
                ) { innerPadding ->
                  Box(
                      modifier = Modifier
                          .fillMaxSize()
                          .padding(innerPadding)
                  ) {
                    CompositionLocalProvider(LocalBottomOverlayHeight provides bottomOverlayHeight) {
                    NavHost(
                        navController = navController,
                        startDestination = if (settings.onboardingCompleted) Screen.Home.route else Screen.Onboarding.route,
                        enterTransition = {
                            screenEnterTransition(
                                resolveScreenTransition(initialState.destination.route, targetState.destination.route, isPop = false),
                                transitionDurationMs,
                            )
                        },
                        exitTransition = {
                            screenExitTransition(
                                resolveScreenTransition(initialState.destination.route, targetState.destination.route, isPop = false),
                                transitionDurationMs,
                            )
                        },
                        popEnterTransition = {
                            screenEnterTransition(
                                resolveScreenTransition(initialState.destination.route, targetState.destination.route, isPop = true),
                                transitionDurationMs,
                            )
                        },
                        popExitTransition = {
                            screenExitTransition(
                                resolveScreenTransition(initialState.destination.route, targetState.destination.route, isPop = true),
                                transitionDurationMs,
                            )
                        },
                    ) {
                        screen(Screen.Onboarding.route) {
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

                        screen(Screen.Home.route) {
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
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onOpenToolWorkspace = { toolId ->
                                    navController.navigate(Screen.ToolWorkspace.createRoute(toolId))
                                },
                                onOpenChatConversation = { conversationId ->
                                    // switchToConversation records the open in RecentActivityRepository.
                                    ChatRepository.switchToConversation(conversationId)
                                    navController.navigate("chat") {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onOpenAllRecents = { navController.navigate(Screen.RecentlyOpened.route) },
                                onOpenNote = { note ->
                                    // Registers the note as "recently opened" (RecentActivityRepository)
                                    // and makes it the active note before Notes comes up.
                                    NotesRepository.openNote(note)
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

                        screen(Screen.Intercept.route) {
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

                        screen(Screen.Apis.route) {
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

                        screen(Screen.RecentlyOpened.route) {
                            // "See all" behind Home's Recently opened section. The navigator mirrors
                            // the one HomeScreen builds for its own rows so both surfaces open items
                            // identically; the by-id lookups fall back to the section when the source
                            // item has already been deleted.
                            val savedApis by ApiCollectionsRepository.requests.collectAsState()
                            val notes by NotesRepository.notes.collectAsState()
                            fun toTab(route: String) = navController.navigate(route) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            RecentlyOpenedScreen(
                                onBack = { navController.popBackStack() },
                                navigator = RecentActivityNavigator(
                                    openApi = { id ->
                                        savedApis.firstOrNull { it.id == id }?.let { ApiCollectionsRepository.setActiveRequest(it) }
                                        toTab(Screen.Apis.route)
                                    },
                                    openNote = { id ->
                                        notes.firstOrNull { it.id == id }?.let { NotesRepository.openNote(it) }
                                        toTab(Screen.Notes.route)
                                    },
                                    openTool = { toolId ->
                                        navController.navigate(Screen.ToolWorkspace.createRoute(toolId))
                                    },
                                    openChat = { conversationId ->
                                        ChatRepository.switchToConversation(conversationId)
                                        toTab("chat")
                                    },
                                    openFlow = { flowId ->
                                        FlowRepository.setActiveFlow(flowId)
                                        toTab(Screen.Flow.route)
                                    },
                                ),
                            )
                        }

                        screen(Screen.SavedApis.route) {
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

                        screen(Screen.Tools.route) {
                            ToolsScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Tools" },
                                onNavigateToTool = { toolId ->
                                    navController.navigate(Screen.ToolWorkspace.createRoute(toolId))
                                }
                            )
                        }

                        screen(
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

                        screen(Screen.Notes.route) {
                            NotesScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Notes" }
                            )
                        }

                        screen(Screen.Flow.route) {
                            FlowScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Flow Automation" }
                            )
                        }

                        screen(Screen.Music.route) {
                            MusicScreen(
                                onBack = { navController.popBackStack() },
                                onMiniSettings = { showMiniSettingsForSection = "Music Lounge" }
                            )
                        }

                        screen("chat") {
                            ChatScreen(
                                onMiniSettings = { showMiniSettingsForSection = "Chats" },
                                onNavigateHome = {
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                // "Collections" in Reqy's drawer means the saved/organized API
                                // requests list -- Screen.SavedApis ("Saved APIs"), the same
                                // destination HomeScreen's own "APIs" power-module card already
                                // treats as the collections surface.
                                onNavigateToCollections = {
                                    navController.navigate(Screen.SavedApis.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToFlows = {
                                    navController.navigate(Screen.Flow.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                // Chat is always entered with popUpTo(Home){saveState}, so the
                                // stack is [Home, chat] and a pop lands on Home; the fallback
                                // covers a cold entry where nothing is beneath it.
                                onBack = {
                                    if (!navController.popBackStack()) {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.Home.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                            )
                        }

                        screen(Screen.Settings.route) {
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

            // Floating bottom overlay — mini player + nav pill — a LATER sibling of the source so
            // its glass can sample (blur + refract) whatever is scrolling underneath. Position and
            // dismiss behaviour are unchanged: bottom-centre above the navigation bar, mini player
            // hidden on the Music route or once closed (isMiniPlayerDismissed).
            if (showPillNav) {
              CompositionLocalProvider(LocalGlassBackdropState provides backdrop) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        // Measured inside the inset padding: overlay content height only. The
                        // navigation-bar inset itself is already in Scaffold's innerPadding.
                        .onSizeChanged { bottomOverlayHeightPx = it.height }
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
                        scrollCollapseSignal = scrollDown,
                        scrollExpandSignal = scrollUp,
                        backdrop = backdrop
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
