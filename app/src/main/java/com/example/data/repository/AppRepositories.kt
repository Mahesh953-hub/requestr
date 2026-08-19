package com.example.data.repository

import android.content.Context
import com.example.data.local.RequestrDatabase
import com.example.data.local.toApiRequestItem
import com.example.data.local.toEntity
import com.example.data.models.ApiFolderItem
import com.example.data.models.ApiRequestItem
import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import com.example.data.models.HttpMethod
import com.example.data.models.MusicSourceType
import com.example.data.models.MusicTrackItem
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.models.RequestBodyType
import com.example.data.models.UserSettingsState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ==========================================
// 2. NOTES REPOSITORY
// ==========================================
data class NoteFolder(
    val id: String,
    val name: String,
    val colorHex: Long,
    val iconEmoji: String = "📁"
)

object NotesRepository {
    private val _folders = MutableStateFlow<List<NoteFolder>>(
        listOf(
            NoteFolder("f-1", "Security Audits", 0xFF3B82F6, "🛡️"),
            NoteFolder("f-2", "API Endpoints", 0xFF06B6D4, "🌐"),
            NoteFolder("f-3", "Frida Snippets", 0xFFF59E0B, "⚡"),
            NoteFolder("f-4", "Reverse Engineering", 0xFF8B5CF6, "🔍")
        )
    )
    val folders: StateFlow<List<NoteFolder>> = _folders.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteItem>>(
        listOf(
            NoteItem(
                id = "n-1",
                title = "Android 14+ APEX Certificate Store",
                content = "# Android 14+ APEX CA Certificates\n\nStarting with Android 14, system CA certificates moved from `/system/etc/security/cacerts/` to the Conscrypt APEX module at `/apex/com.android.conscrypt/cacerts/`.\n\n### Mounting steps with Magisk:\n```bash\n# Bind mount writable tmpfs over APEX directory\nmount -t tmpfs tmpfs /apex/com.android.conscrypt/cacerts\ncp /system/etc/security/cacerts/* /apex/com.android.conscrypt/cacerts/\ncp /data/local/tmp/requestr_ca.0 /apex/com.android.conscrypt/cacerts/\n```\n- Fully trusted by all system & user apps\n- Works seamlessly with requestr root installer",
                format = NoteFormatType.MARKDOWN,
                folderId = "f-1",
                folderName = "Security Audits",
                folderColorHex = 0xFF3B82F6,
                isPinned = true
            ),
            NoteItem(
                id = "n-2",
                title = "OAuth 2.0 PKCE Checklist",
                content = "- [x] Code Verifier (43-128 chars random Base64URL)\n- [x] Code Challenge = BASE64URL-ENCODE(SHA256(code_verifier))\n- [ ] Authorization request with `code_challenge_method=S256`\n- [ ] Token exchange with raw `code_verifier`\n- [ ] Validate state token prevents CSRF",
                format = NoteFormatType.CHECKLIST,
                folderId = "f-2",
                folderName = "API Endpoints",
                folderColorHex = 0xFF06B6D4,
                isPinned = true
            ),
            NoteItem(
                id = "n-3",
                title = "Custom HTML Report Template",
                content = "<div style=\"font-family: monospace; background: #0A0E1A; color: #60A5FA; padding: 16px; border-radius: 8px;\">\n  <h2>requestr API Audit Report</h2>\n  <p>Endpoint: <code>https://api.internal/v2/user</code></p>\n  <p>Status: <span style=\"color: #22C55E;\">PASSED</span></p>\n</div>",
                format = NoteFormatType.HTML,
                folderId = "f-4",
                folderName = "Reverse Engineering",
                folderColorHex = 0xFF8B5CF6,
                isPinned = false
            )
        )
    )
    val notes: StateFlow<List<NoteItem>> = _notes.asStateFlow()

    fun saveNote(note: NoteItem) {
        val current = _notes.value
        val exists = current.any { it.id == note.id }
        if (exists) {
            _notes.value = current.map { if (it.id == note.id) note.copy(updatedAt = System.currentTimeMillis()) else it }
        } else {
            _notes.value = listOf(note.copy(updatedAt = System.currentTimeMillis())) + current
        }
    }

    fun deleteNote(id: String) {
        _notes.value = _notes.value.filter { it.id != id }
    }

    fun togglePin(id: String) {
        _notes.value = _notes.value.map {
            if (it.id == id) it.copy(isPinned = !it.isPinned) else it
        }
    }

    fun addFolder(name: String, colorHex: Long) {
        _folders.value = _folders.value + NoteFolder(UUID.randomUUID().toString(), name, colorHex)
    }
}

// ==========================================
// 3. FLOW (AUTOMATION GRAPH) REPOSITORY
// ==========================================
object FlowRepository {

    private val _nodes = MutableStateFlow<List<FlowNodeItem>>(
        listOf(
            FlowNodeItem(
                id = "node-1",
                title = "Intercept Trigger",
                type = FlowNodeType.INTERCEPT_TRIGGER,
                x = 60f,
                y = 80f,
                inputPorts = emptyList(),
                outputPorts = listOf("traffic"),
                configData = "Pattern: .*/auth/login.*"
            ),
            FlowNodeItem(
                id = "node-2",
                title = "Extract JWT Token",
                type = FlowNodeType.TRANSFORM,
                x = 340f,
                y = 80f,
                inputPorts = listOf("traffic"),
                outputPorts = listOf("token"),
                configData = "JSONPath: $.data.token"
            ),
            FlowNodeItem(
                id = "node-3",
                title = "Verify with AI",
                type = FlowNodeType.TRANSFORM,
                x = 620f,
                y = 80f,
                inputPorts = listOf("token"),
                outputPorts = listOf("analysis"),
                configData = "Prompt: Audit token entropy & claims"
            ),
            FlowNodeItem(
                id = "node-4",
                title = "Save Audit Note",
                type = FlowNodeType.OUTPUT,
                x = 900f,
                y = 80f,
                inputPorts = listOf("analysis"),
                outputPorts = emptyList(),
                configData = "Folder: Security Audits"
            )
        )
    )
    val nodes: StateFlow<List<FlowNodeItem>> = _nodes.asStateFlow()

    private val _connections = MutableStateFlow<List<FlowConnectionItem>>(
        listOf(
            FlowConnectionItem(fromNodeId = "node-1", fromPort = "traffic", toNodeId = "node-2", toPort = "traffic"),
            FlowConnectionItem(fromNodeId = "node-2", fromPort = "token", toNodeId = "node-3", toPort = "token"),
            FlowConnectionItem(fromNodeId = "node-3", fromPort = "analysis", toNodeId = "node-4", toPort = "analysis")
        )
    )
    val connections: StateFlow<List<FlowConnectionItem>> = _connections.asStateFlow()

    fun addNode(node: FlowNodeItem) {
        _nodes.value = _nodes.value + node
    }

    fun updateNodePosition(id: String, x: Float, y: Float) {
        _nodes.value = _nodes.value.map {
            if (it.id == id) it.copy(x = x, y = y) else it
        }
    }

    fun removeNode(id: String) {
        _nodes.value = _nodes.value.filter { it.id != id }
        _connections.value = _connections.value.filter { it.fromNodeId != id && it.toNodeId != id }
    }

    fun addConnection(conn: FlowConnectionItem) {
        _connections.value = _connections.value + conn
    }

    suspend fun executeFlow(): String {
        val nodes = _nodes.value.toList()
        val connections = _connections.value.toList()

        if (nodes.isEmpty()) return "No nodes to execute. Add a trigger node to begin."

        val executor = com.example.flow.FlowExecutor { url, method, headers, body ->
            val request = com.example.data.models.ApiRequestItem(
                url = url,
                method = try { com.example.data.models.HttpMethod.valueOf(method) }
                    catch (_: Exception) { com.example.data.models.HttpMethod.GET },
                headers = headers.map { com.example.data.models.KeyValueItem(it.key, it.value) },
                bodyContent = body,
                bodyType = com.example.data.models.RequestBodyType.JSON,
            )
            val response = com.example.data.repository.ApiTestrRepository.executeRequest(request, true)
            response.body.ifBlank { "{\"status\":${response.statusCode}}" }
        }

        val result = executor.execute(nodes, connections)

        return buildString {
            appendLine("Flow execution ${if (result.success) "completed" else "finished with errors"}.")
            appendLine("${result.nodesExecuted} node(s) executed in ${result.totalDurationMs}ms.")
            if (result.errors.isNotEmpty()) {
                appendLine("Errors:")
                result.errors.forEach { appendLine("  - $it") }
            }
            if (result.outputs.isNotEmpty()) {
                appendLine("Outputs:")
                result.outputs.entries.take(5).forEach { (id, out) ->
                    val label = nodes.find { it.id == id }?.title ?: id
                    appendLine("  $label: ${out.take(80)}")
                }
                if (result.outputs.size > 5) appendLine("  ... and ${result.outputs.size - 5} more")
            }
        }.trimEnd()
    }
}

// ==========================================
// 4. MUSIC REPOSITORY
// ==========================================
object MusicRepository {
    private val _tracks = MutableStateFlow<List<MusicTrackItem>>(emptyList())
    val tracks: StateFlow<List<MusicTrackItem>> = _tracks.asStateFlow()

    private val _currentTrack = MutableStateFlow(
        MusicTrackItem("", "No Track", "No Artist", 0, MusicSourceType.LOCAL, 0xFF1E3A8A)
    )
    val currentTrack: StateFlow<MusicTrackItem> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionSec = MutableStateFlow(0)
    val currentPositionSec: StateFlow<Int> = _currentPositionSec.asStateFlow()

    /** Scan the device's local music library and populate the track list. */
    suspend fun loadLocalLibrary(context: android.content.Context) {
        val local = com.example.data.music.LocalMusicScanner.scanLocalTracks(context)
        _tracks.value = local
    }

    /** Scan and pick a random track. If library is empty, current track unchanged. */
    suspend fun pickRandomTrack(context: android.content.Context): MusicTrackItem {
        val local = com.example.data.music.LocalMusicScanner.scanLocalTracks(context)
        _tracks.value = local
        val random = local.randomOrNull() ?: _currentTrack.value
        _currentTrack.value = random
        return random
    }

    fun togglePlayPause() {
        _isPlaying.value = !_isPlaying.value
    }

    fun nextTrack() {
        val current = _tracks.value
        if (current.isEmpty()) return
        val curId = _currentTrack.value?.id ?: return
        val index = current.indexOfFirst { it.id == curId }
        val nextIndex = (index + 1) % current.size
        _currentTrack.value = current[nextIndex]
        _currentPositionSec.value = 0
    }

    fun prevTrack() {
        val current = _tracks.value
        if (current.isEmpty()) return
        val curId = _currentTrack.value?.id ?: return
        val index = current.indexOfFirst { it.id == curId }
        val prevIndex = if (index - 1 < 0) current.size - 1 else index - 1
        _currentTrack.value = current[prevIndex]
        _currentPositionSec.value = 0
    }

    fun seekTo(sec: Int) {
        _currentPositionSec.value = sec.coerceIn(0, _currentTrack.value?.durationSec ?: 0)
    }

    fun selectTrack(id: String) {
        val found = _tracks.value.find { it.id == id }
        if (found != null) {
            _currentTrack.value = found
            _currentPositionSec.value = 0
            _isPlaying.value = true
        }
    }

    fun stopMusic() {
        _isPlaying.value = false
        _currentPositionSec.value = 0
    }
}

// ==========================================
// 5. APIS / COLLECTIONS REPOSITORY
// ==========================================
object ApiCollectionsRepository {

    private val _folders = MutableStateFlow<List<ApiFolderItem>>(
        listOf(
            ApiFolderItem("f-1", "Public APIs", 0xFF3B82F6),
            ApiFolderItem("f-2", "Auth & Identity", 0xFF06B6D4),
            ApiFolderItem("f-3", "Internal Microservices", 0xFFF59E0B),
            ApiFolderItem("f-4", "WebSockets & Stream", 0xFF8B5CF6)
        )
    )
    val folders: StateFlow<List<ApiFolderItem>> = _folders.asStateFlow()

    // No seed data by design.
    //
    // v2.1 shipped sample requests (HttpBin JSON Echo, GitHub User Profile, Post JSON Payload)
    // which surfaced on Home's "Recently opened" even on a fresh install (APP_v2.md Bug 1.6).
    // A new install now genuinely has no saved requests.
    private val _requests = MutableStateFlow<List<ApiRequestItem>>(emptyList())
    val requests: StateFlow<List<ApiRequestItem>> = _requests.asStateFlow()

    /**
     * The request currently open in the editor.
     *
     * v2.1 (line ~461) called `_requests.value.first()` which crashed on a fresh install
     * with NoSuchElementException because the seed data was removed. Now defaults to an
     * empty placeholder request so the API Testr screen opens safely.
     */
    private val _currentActiveRequest = MutableStateFlow(
        ApiRequestItem(
            id = "new-request",
            name = "New Request",
            method = com.example.data.models.HttpMethod.GET,
            url = "https://httpbin.org/get",
        )
    )
    val currentActiveRequest: StateFlow<ApiRequestItem> = _currentActiveRequest.asStateFlow()

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * v3.1 fix (A4): saved requests previously lived only in [_requests], a plain in-memory
     * `MutableStateFlow` — Save/Star looked like it worked but every request was lost on process
     * death or app restart. Call once from Application/MainActivity.onCreate() (mirrors
     * [SettingsRepository.init]) to back [_requests] with the real `saved_api_requests` Room
     * table; every subsequent read reflects the DB, and writes below persist to it.
     */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        repoScope.launch {
            RequestrDatabase.get(ctx).savedApiRequestDao().observeAll().collect { rows ->
                _requests.value = rows.map { it.toApiRequestItem() }
            }
        }
    }

    fun setActiveRequest(req: ApiRequestItem) {
        _currentActiveRequest.value = req
    }

    fun saveRequest(req: ApiRequestItem) {
        val updated = req.copy(updatedAt = System.currentTimeMillis())
        _currentActiveRequest.value = updated

        // Optimistic in-memory update so the UI (e.g. Saved APIs list) reflects the save
        // immediately, without waiting on the DB round-trip / Flow re-collection.
        val current = _requests.value
        val exists = current.any { it.id == updated.id }
        _requests.value = if (exists) {
            current.map { if (it.id == updated.id) updated else it }
        } else {
            listOf(updated) + current
        }

        val ctx = appContext
        if (ctx != null) {
            repoScope.launch {
                RequestrDatabase.get(ctx).savedApiRequestDao().upsert(updated.toEntity())
            }
        }
    }

    fun deleteRequest(id: String) {
        _requests.value = _requests.value.filter { it.id != id }
        val ctx = appContext
        if (ctx != null) {
            repoScope.launch {
                RequestrDatabase.get(ctx).savedApiRequestDao().deleteById(id)
            }
        }
    }

    fun toggleFavorite(id: String) {
        var newFavorite = false
        _requests.value = _requests.value.map {
            if (it.id == id) {
                newFavorite = !it.isFavorite
                it.copy(isFavorite = newFavorite)
            } else it
        }
        val ctx = appContext
        if (ctx != null) {
            repoScope.launch {
                RequestrDatabase.get(ctx).savedApiRequestDao().setFavorite(id, newFavorite)
            }
        }
    }
}

// ==========================================
// 6. USER SETTINGS REPOSITORY
// ==========================================
object SettingsRepository {
    private const val TAG = "SettingsRepository"
    private const val PREFS_NAME = "requestr_user_settings"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_AUTO_BEAUTIFY = "auto_beautify"
    private const val KEY_SSL_VERIFY = "ssl_verify"
    private const val KEY_SNIPPET_LANG = "snippet_lang"
    private const val KEY_ONBOARDING = "onboarding_completed"
    // Fix 01 — Theme appearance
    private const val KEY_APPEARANCE_MODE = "appearance_mode"
    private const val KEY_SELECTED_DARK_THEME = "selected_dark_theme"
    private const val KEY_SELECTED_LIGHT_THEME = "selected_light_theme"
    // Fix 02 — UI & UX
    private const val KEY_SWIPE_TO_DELETE = "swipe_to_delete"
    private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    private const val KEY_GESTURE_NAVIGATION = "gesture_navigation"
    private const val KEY_DENSITY_MODE = "density_mode"
    private const val KEY_UPDATE_NOTIFICATION_POLICY = "update_notification_policy"
    private const val KEY_ANIMATION_ENABLED = "animation_enabled"
    private const val KEY_TRANSITION_BEHAVIOR = "transition_behavior"
    private const val KEY_DISPLAY_SCALE = "display_scale"
    // Fix 02 — Features
    private const val KEY_AUTO_RECORDING = "auto_recording"
    private const val KEY_TRAFFIC_LOG = "traffic_log"
    private const val KEY_RECORDING_NOTIFICATIONS = "recording_notifications"
    private const val KEY_REMOTE_CONTROL = "remote_control"
    private const val KEY_REMOTE_COMMANDS = "remote_commands"
    private const val KEY_LAN_DISCOVERY = "lan_discovery"
    private const val KEY_DEVICE_BROADCASTING = "device_broadcasting"
    private const val KEY_TRAFFIC_CAPTURE = "traffic_capture"
    private const val KEY_REQUEST_LOGGING = "request_logging"
    private const val KEY_RESPONSE_LOGGING = "response_logging"
    // Fix 05 — Music
    private const val KEY_MUSIC_PERMISSION_STATUS = "music_permission_status"
    private const val KEY_MUSIC_BAR_DISMISSED = "music_bar_dismissed"
    private const val KEY_MUSIC_LIBRARY_SCANNED = "music_library_scanned"
    private const val KEY_SELECTED_LOCAL_TRACK_ID = "selected_local_track_id"

    private var prefs: android.content.SharedPreferences? = null
    private var initialized = false

    /**
     * Call once from Application.onCreate() or early in MainActivity before any
     * read of [settings]. Loads persisted values; runtime-only flags (root,
     * Shizuku, Frida, VPN) stay false until [CapabilityProbe] runs.
     */
    fun init(context: android.content.Context) {
        if (initialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        initialized = true

        val p = prefs!!
        _settings.value = _settings.value.copy(
            userName = p.getString(KEY_USER_NAME, "") ?: "",
            themeMode = p.getString(KEY_THEME_MODE, "MONOCHROME_DARK") ?: "MONOCHROME_DARK",
            cornerRadiusDp = p.getInt(KEY_CORNER_RADIUS, 16),
            proxyPort = p.getInt(KEY_PROXY_PORT, 8080),
            autoBeautify = p.getBoolean(KEY_AUTO_BEAUTIFY, true),
            sslVerification = p.getBoolean(KEY_SSL_VERIFY, false),
            defaultSnippetLang = p.getString(KEY_SNIPPET_LANG, "Python - Requests") ?: "Python - Requests",
            onboardingCompleted = p.getBoolean(KEY_ONBOARDING, false),
            // Fix 01
            appearanceMode = p.getString(KEY_APPEARANCE_MODE, "SYSTEM") ?: "SYSTEM",
            selectedDarkTheme = p.getString(KEY_SELECTED_DARK_THEME, "MONOCHROME_DARK") ?: "MONOCHROME_DARK",
            selectedLightTheme = p.getString(KEY_SELECTED_LIGHT_THEME, "EDITORIAL_LIGHT") ?: "EDITORIAL_LIGHT",
            // Fix 02 — UI & UX
            swipeToDeleteEnabled = p.getBoolean(KEY_SWIPE_TO_DELETE, true),
            hapticFeedbackEnabled = p.getBoolean(KEY_HAPTIC_FEEDBACK, true),
            gestureNavigationEnabled = p.getBoolean(KEY_GESTURE_NAVIGATION, true),
            densityMode = p.getString(KEY_DENSITY_MODE, "COMFORTABLE") ?: "COMFORTABLE",
            updateNotificationPolicy = p.getString(KEY_UPDATE_NOTIFICATION_POLICY, "MAJOR_ONLY") ?: "MAJOR_ONLY",
            animationEnabled = p.getBoolean(KEY_ANIMATION_ENABLED, true),
            transitionBehavior = p.getString(KEY_TRANSITION_BEHAVIOR, "STANDARD") ?: "STANDARD",
            displayScale = p.getString(KEY_DISPLAY_SCALE, "NORMAL") ?: "NORMAL",
            // Fix 02 — Features
            autoRecordingEnabled = p.getBoolean(KEY_AUTO_RECORDING, false),
            trafficLogEnabled = p.getBoolean(KEY_TRAFFIC_LOG, true),
            recordingNotificationsEnabled = p.getBoolean(KEY_RECORDING_NOTIFICATIONS, true),
            remoteControlEnabled = p.getBoolean(KEY_REMOTE_CONTROL, false),
            remoteCommandsEnabled = p.getBoolean(KEY_REMOTE_COMMANDS, false),
            lanDeviceDiscoveryEnabled = p.getBoolean(KEY_LAN_DISCOVERY, false),
            deviceBroadcastingEnabled = p.getBoolean(KEY_DEVICE_BROADCASTING, false),
            trafficCaptureEnabled = p.getBoolean(KEY_TRAFFIC_CAPTURE, true),
            requestLoggingEnabled = p.getBoolean(KEY_REQUEST_LOGGING, true),
            responseLoggingEnabled = p.getBoolean(KEY_RESPONSE_LOGGING, true),
            // Fix 05 — Music
            musicPermissionStatus = p.getString(KEY_MUSIC_PERMISSION_STATUS, "NOT_REQUESTED") ?: "NOT_REQUESTED",
            musicBarDismissed = p.getBoolean(KEY_MUSIC_BAR_DISMISSED, false),
            musicLibraryScanned = p.getBoolean(KEY_MUSIC_LIBRARY_SCANNED, false),
            selectedLocalTrackId = p.getString(KEY_SELECTED_LOCAL_TRACK_ID, null),
        )
    }

    private val _settings = MutableStateFlow(
        UserSettingsState(
            userName = "",
            // Default to MonochromeDark. v2.1 defaulted to "MIDNIGHT" which maps to
            // "Midnight Sapphire" — a fine theme, but the welcome screen uses it
            // before the user picks, which confuses the first-run experience.
            // MonochromeDark is the universal dark starting point.
            themeMode = "MONOCHROME_DARK",
            cornerRadiusDp = 16,
            proxyPort = 8080,
            // Runtime-only flags — all start false.
            // v2.1 hardcoded isRootDetected, isShizukuAvailable, isLsposedInstalled to true,
            // which made the Settings screen show "Ready" for everything on a fresh install.
            // CapabilityProbe populates these at runtime.
            isVpnActive = false,
            isRootDetected = false,
            isShizukuAvailable = false,
            isFridaReady = false,
            isLsposedInstalled = false,
            autoBeautify = true,
            sslVerification = false,
            defaultSnippetLang = "Python - Requests",
            // Onboarding not yet completed until the user finishes the wizard.
            // v2.1 set this to true, skipping onboarding entirely.
            onboardingCompleted = false,
        )
    )
    val settings: StateFlow<UserSettingsState> = _settings.asStateFlow()

    fun updateUserName(name: String) {
        _settings.value = _settings.value.copy(userName = name)
        persist(KEY_USER_NAME, name)
    }

    fun updateTheme(themeMode: String) {
        _settings.value = _settings.value.copy(themeMode = themeMode)
        persist(KEY_THEME_MODE, themeMode)
    }

    // v3.1 B1 — the single, unified theme-selection write path. Every UI entry point
    // that lets the user pick a root theme (Setup Wizard's AppearanceStep, Settings'
    // AppearanceSelector + AccentColorGrid, CommonComponents' ThemeToggleButton, and
    // AppNavigationDrawer's quick-switcher) must route through THIS function instead
    // of calling updateTheme()/updateSelectedDarkTheme() directly and separately —
    // that dual-write divergence (mutating themeMode identity without regard to which
    // appearanceMode family the pick belongs to) was exactly the "theme selection
    // collision" bug. This function:
    //  1. Writes themeMode (the value MyApplicationTheme actually renders) once.
    //  2. Remembers the pick under the correct per-family slot (selectedLightTheme or
    //     selectedDarkTheme) keyed off the theme's own isLight flag — NOT off whatever
    //     appearanceMode happened to be active — so light/dark choices never bleed into
    //     each other regardless of which mode the picker was shown under.
    //  3. Never touches appearanceMode — picking a root theme must never silently flip
    //     SYSTEM/LIGHT/DARK; that stays a fully independent, orthogonal setting.
    fun updateThemeSelection(theme: com.example.ui.theme.AppThemeMode) {
        val current = _settings.value
        val updated = if (theme.isLight) {
            current.copy(themeMode = theme.name, selectedLightTheme = theme.name)
        } else {
            current.copy(themeMode = theme.name, selectedDarkTheme = theme.name)
        }
        _settings.value = updated
        persist(KEY_THEME_MODE, updated.themeMode)
        if (theme.isLight) persist(KEY_SELECTED_LIGHT_THEME, updated.selectedLightTheme)
        else persist(KEY_SELECTED_DARK_THEME, updated.selectedDarkTheme)
    }

    // Convenience overload for call sites that only have the string key (e.g. from a
    // DropdownMenu/grid tap using AppThemeMode.name as the value). Falls back to the
    // legacy single-field updateTheme() if the string doesn't map to a known theme, so
    // this can never silently no-op.
    fun updateThemeSelection(themeName: String) {
        val theme = com.example.ui.theme.AppThemeMode.values()
            .find { it.name.equals(themeName, ignoreCase = true) }
        if (theme != null) updateThemeSelection(theme) else updateTheme(themeName)
    }

    fun updateCornerRadius(radius: Int) {
        _settings.value = _settings.value.copy(cornerRadiusDp = radius)
        persist(KEY_CORNER_RADIUS, radius)
    }

    fun updateProxyPort(port: Int) {
        _settings.value = _settings.value.copy(proxyPort = port)
        persist(KEY_PROXY_PORT, port)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        _settings.value = _settings.value.copy(onboardingCompleted = completed)
        persist(KEY_ONBOARDING, completed)
    }

    // Fix 01 — Appearance
    fun updateAppearanceMode(mode: String) {
        _settings.value = _settings.value.copy(appearanceMode = mode)
        persist(KEY_APPEARANCE_MODE, mode)
    }
    fun updateSelectedDarkTheme(theme: String) {
        _settings.value = _settings.value.copy(selectedDarkTheme = theme)
        persist(KEY_SELECTED_DARK_THEME, theme)
    }
    fun updateSelectedLightTheme(theme: String) {
        _settings.value = _settings.value.copy(selectedLightTheme = theme)
        persist(KEY_SELECTED_LIGHT_THEME, theme)
    }
    // Fix 02 — UI & UX
    fun updateSwipeToDelete(enabled: Boolean) {
        _settings.value = _settings.value.copy(swipeToDeleteEnabled = enabled)
        persist(KEY_SWIPE_TO_DELETE, enabled)
    }
    fun updateHapticFeedback(enabled: Boolean) {
        _settings.value = _settings.value.copy(hapticFeedbackEnabled = enabled)
        persist(KEY_HAPTIC_FEEDBACK, enabled)
    }
    fun updateGestureNavigation(enabled: Boolean) {
        _settings.value = _settings.value.copy(gestureNavigationEnabled = enabled)
        persist(KEY_GESTURE_NAVIGATION, enabled)
    }
    fun updateDensityMode(mode: String) {
        _settings.value = _settings.value.copy(densityMode = mode)
        persist(KEY_DENSITY_MODE, mode)
    }
    fun updateNotificationPolicy(policy: String) {
        _settings.value = _settings.value.copy(updateNotificationPolicy = policy)
        persist(KEY_UPDATE_NOTIFICATION_POLICY, policy)
    }
    fun updateAnimationEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(animationEnabled = enabled)
        persist(KEY_ANIMATION_ENABLED, enabled)
    }
    fun updateTransitionBehavior(behavior: String) {
        _settings.value = _settings.value.copy(transitionBehavior = behavior)
        persist(KEY_TRANSITION_BEHAVIOR, behavior)
    }
    fun updateDisplayScale(scale: String) {
        _settings.value = _settings.value.copy(displayScale = scale)
        persist(KEY_DISPLAY_SCALE, scale)
    }
    // Fix 02 — Features
    fun updateAutoRecording(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoRecordingEnabled = enabled)
        persist(KEY_AUTO_RECORDING, enabled)
    }
    fun updateTrafficLog(enabled: Boolean) {
        _settings.value = _settings.value.copy(trafficLogEnabled = enabled)
        persist(KEY_TRAFFIC_LOG, enabled)
    }
    fun updateRecordingNotifications(enabled: Boolean) {
        _settings.value = _settings.value.copy(recordingNotificationsEnabled = enabled)
        persist(KEY_RECORDING_NOTIFICATIONS, enabled)
    }
    fun updateRemoteControl(enabled: Boolean) {
        _settings.value = _settings.value.copy(remoteControlEnabled = enabled)
        persist(KEY_REMOTE_CONTROL, enabled)
    }
    fun updateRemoteCommands(enabled: Boolean) {
        _settings.value = _settings.value.copy(remoteCommandsEnabled = enabled)
        persist(KEY_REMOTE_COMMANDS, enabled)
    }
    fun updateLanDiscovery(enabled: Boolean) {
        _settings.value = _settings.value.copy(lanDeviceDiscoveryEnabled = enabled)
        persist(KEY_LAN_DISCOVERY, enabled)
    }
    fun updateDeviceBroadcasting(enabled: Boolean) {
        _settings.value = _settings.value.copy(deviceBroadcastingEnabled = enabled)
        persist(KEY_DEVICE_BROADCASTING, enabled)
    }
    fun updateTrafficCapture(enabled: Boolean) {
        _settings.value = _settings.value.copy(trafficCaptureEnabled = enabled)
        persist(KEY_TRAFFIC_CAPTURE, enabled)
    }
    fun updateRequestLogging(enabled: Boolean) {
        _settings.value = _settings.value.copy(requestLoggingEnabled = enabled)
        persist(KEY_REQUEST_LOGGING, enabled)
    }
    fun updateResponseLogging(enabled: Boolean) {
        _settings.value = _settings.value.copy(responseLoggingEnabled = enabled)
        persist(KEY_RESPONSE_LOGGING, enabled)
    }
    // Fix 05 — Music
    fun updateMusicPermissionStatus(status: String) {
        _settings.value = _settings.value.copy(musicPermissionStatus = status)
        persist(KEY_MUSIC_PERMISSION_STATUS, status)
    }
    fun updateMusicBarDismissed(dismissed: Boolean) {
        _settings.value = _settings.value.copy(musicBarDismissed = dismissed)
        persist(KEY_MUSIC_BAR_DISMISSED, dismissed)
    }
    fun updateMusicLibraryScanned(scanned: Boolean) {
        _settings.value = _settings.value.copy(musicLibraryScanned = scanned)
        persist(KEY_MUSIC_LIBRARY_SCANNED, scanned)
    }
    fun updateSelectedLocalTrackId(id: String?) {
        _settings.value = _settings.value.copy(selectedLocalTrackId = id)
        if (id != null) persist(KEY_SELECTED_LOCAL_TRACK_ID, id)
    }

    fun updateSettings(newSettings: UserSettingsState) {
        _settings.value = newSettings
        // Bulk persist
        persist(KEY_USER_NAME, newSettings.userName)
        persist(KEY_THEME_MODE, newSettings.themeMode)
        persist(KEY_CORNER_RADIUS, newSettings.cornerRadiusDp)
        persist(KEY_PROXY_PORT, newSettings.proxyPort)
        persist(KEY_AUTO_BEAUTIFY, newSettings.autoBeautify)
        persist(KEY_SSL_VERIFY, newSettings.sslVerification)
        persist(KEY_SNIPPET_LANG, newSettings.defaultSnippetLang)
        persist(KEY_ONBOARDING, newSettings.onboardingCompleted)
        // Fix 01
        persist(KEY_APPEARANCE_MODE, newSettings.appearanceMode)
        persist(KEY_SELECTED_DARK_THEME, newSettings.selectedDarkTheme)
        persist(KEY_SELECTED_LIGHT_THEME, newSettings.selectedLightTheme)
        // Fix 02 — UI & UX
        persist(KEY_SWIPE_TO_DELETE, newSettings.swipeToDeleteEnabled)
        persist(KEY_HAPTIC_FEEDBACK, newSettings.hapticFeedbackEnabled)
        persist(KEY_GESTURE_NAVIGATION, newSettings.gestureNavigationEnabled)
        persist(KEY_DENSITY_MODE, newSettings.densityMode)
        persist(KEY_UPDATE_NOTIFICATION_POLICY, newSettings.updateNotificationPolicy)
        persist(KEY_ANIMATION_ENABLED, newSettings.animationEnabled)
        persist(KEY_TRANSITION_BEHAVIOR, newSettings.transitionBehavior)
        persist(KEY_DISPLAY_SCALE, newSettings.displayScale)
        // Fix 02 — Features
        persist(KEY_AUTO_RECORDING, newSettings.autoRecordingEnabled)
        persist(KEY_TRAFFIC_LOG, newSettings.trafficLogEnabled)
        persist(KEY_RECORDING_NOTIFICATIONS, newSettings.recordingNotificationsEnabled)
        persist(KEY_REMOTE_CONTROL, newSettings.remoteControlEnabled)
        persist(KEY_REMOTE_COMMANDS, newSettings.remoteCommandsEnabled)
        persist(KEY_LAN_DISCOVERY, newSettings.lanDeviceDiscoveryEnabled)
        persist(KEY_DEVICE_BROADCASTING, newSettings.deviceBroadcastingEnabled)
        persist(KEY_TRAFFIC_CAPTURE, newSettings.trafficCaptureEnabled)
        persist(KEY_REQUEST_LOGGING, newSettings.requestLoggingEnabled)
        persist(KEY_RESPONSE_LOGGING, newSettings.responseLoggingEnabled)
        // Fix 05 — Music
        persist(KEY_MUSIC_PERMISSION_STATUS, newSettings.musicPermissionStatus)
        persist(KEY_MUSIC_BAR_DISMISSED, newSettings.musicBarDismissed)
        persist(KEY_MUSIC_LIBRARY_SCANNED, newSettings.musicLibraryScanned)
        if (newSettings.selectedLocalTrackId != null) persist(KEY_SELECTED_LOCAL_TRACK_ID, newSettings.selectedLocalTrackId)
    }

    private fun persist(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    private fun persist(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
    }

    private fun persist(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }
}
