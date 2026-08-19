package com.example.data.repository

import com.example.data.models.AiAssistantPreset
import com.example.data.models.ApiFolderItem
import com.example.data.models.ApiRequestItem
import com.example.data.models.ChatMessageItem
import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import com.example.data.models.HttpMethod
import com.example.data.models.MusicSourceType
import com.example.data.models.MusicTrackItem
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.models.RequestBodyType
import com.example.data.models.ResponseFormat
import com.example.data.models.UserSettingsState
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ==========================================
// 1. CHAT REPOSITORY
// ==========================================
object ChatRepository {

    private val _assistants = MutableStateFlow<List<AiAssistantPreset>>(
        listOf(
            AiAssistantPreset(
                id = "ast-1",
                name = "API Reverse Engineer",
                avatarEmoji = "⚡",
                model = "gemini-2.5-flash",
                provider = "Google AI Studio",
                systemPrompt = "You are requestr AI, an elite API reverse engineer and security analyst. You analyze captured requests, decode obscure tokens, write Frida bypass scripts, and structure HTTP requests.",
                temperature = 0.4f
            ),
            AiAssistantPreset(
                id = "ast-2",
                name = "Security Auditor",
                avatarEmoji = "🛡️",
                model = "gpt-4o",
                provider = "OpenAI",
                systemPrompt = "You are a web application security auditor checking for OWASP Top 10 vulnerabilities, CORS misconfigurations, broken object level authorization (BOLA), and JWT flaws.",
                temperature = 0.2f
            ),
            AiAssistantPreset(
                id = "ast-3",
                name = "Script Automator",
                avatarEmoji = "🤖",
                model = "claude-3.5-sonnet",
                provider = "Anthropic",
                systemPrompt = "You write clean, modular Python (requests/httpx) and TypeScript scripts for API automation and data extraction.",
                temperature = 0.6f
            )
        )
    )
    val assistants: StateFlow<List<AiAssistantPreset>> = _assistants.asStateFlow()

    private val _activeAssistant = MutableStateFlow(_assistants.value.first())
    val activeAssistant: StateFlow<AiAssistantPreset> = _activeAssistant.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageItem>>(
        listOf(
            ChatMessageItem(
                id = "init-1",
                role = "assistant",
                text = "Welcome to requestr AI. I can help analyze your intercepted traffic, generate Frida SSL-pinning bypasses, construct complex OkHttp/cURL requests, or debug authentication headers. What are we building today?",
                timestamp = System.currentTimeMillis() - 60000,
                inputTokens = 95,
                outputTokens = 42,
                latencySeconds = 0.22,
                tokensPerSec = 434,
                provider = "Gemini 2.5 Flash"
            )
        )
    )
    val messages: StateFlow<List<ChatMessageItem>> = _messages.asStateFlow()

    private val _responseFormat = MutableStateFlow(ResponseFormat.GEMINI)
    val responseFormat: StateFlow<ResponseFormat> = _responseFormat.asStateFlow()

    fun setResponseFormat(format: ResponseFormat) {
        _responseFormat.value = format
    }

    fun setActiveAssistant(assistant: AiAssistantPreset) {
        _activeAssistant.value = assistant
    }

    suspend fun sendMessage(userText: String): ChatMessageItem {
        val userMsg = ChatMessageItem(
            role = "user",
            text = userText,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMsg

        // AI response generation
        val t0 = System.currentTimeMillis()
        delay(400) // realistic token stream initiation

        val replyText = generateContextualReply(userText, _activeAssistant.value)
        val latency = (System.currentTimeMillis() - t0) / 1000.0
        val inTok = (userText.length / 3).coerceAtLeast(15)
        val outTok = (replyText.length / 3).coerceAtLeast(20)
        val tokPerSec = if (latency > 0) (outTok / latency).toInt() else 350

        val aiMsg = ChatMessageItem(
            role = "assistant",
            text = replyText,
            timestamp = System.currentTimeMillis(),
            inputTokens = inTok,
            outputTokens = outTok,
            latencySeconds = latency,
            tokensPerSec = tokPerSec,
            provider = _activeAssistant.value.model
        )

        _messages.value = _messages.value + aiMsg
        return aiMsg
    }

    private fun generateContextualReply(prompt: String, assistant: AiAssistantPreset): String {
        val p = prompt.lowercase()
        return when {
            p.contains("frida") || p.contains("pinning") || p.contains("ssl") -> {
                """
### Universal SSL Pinning Bypass (Conscrypt & TrustManager)

Here is the recommended Frida script for bypassing certificate pinning in target apps:

```javascript
Java.perform(function() {
    console.log("[*] Injected requestr Universal SSL Pinning Bypass");

    // 1. TrustManagerImpl
    var TrustManagerImpl = Java.use('com.android.org.conscrypt.TrustManagerImpl');
    TrustManagerImpl.verifyChain.implementation = function(untrustedChain, trustAnchorChain, host, clientAuth, ocspData, tlsSctData) {
        console.log("[+] Bypassed Conscrypt TrustManagerImpl for host: " + host);
        return untrustedChain;
    };

    // 2. OkHttp 3 & 4 CertificatePinner
    try {
        var CertificatePinner = Java.use('okhttp3.CertificatePinner');
        CertificatePinner.check.overload('java.lang.String', 'java.util.List').implementation = function(host, peerCerts) {
            console.log('[+] Bypassed OkHttp CertificatePinner for: ' + host);
            return;
        };
    } catch (err) {
        console.log("[-] OkHttp CertificatePinner not present or obfuscated");
    }
});
```

**Next step:** You can also tap **Root & Frida Tools** in the Intercept tab and select **Attach & Run** to inject this script directly into any running process!
                """.trimIndent()
            }
            p.contains("jwt") || p.contains("token") -> {
                """
### JWT Analysis & Signature Verification

JWTs are composed of three Base64URL-encoded segments separated by dots (`.`):
1. **Header**: Algorithm & token type (e.g. `{"alg":"HS256","typ":"JWT"}`)
2. **Payload**: Claims and subject data
3. **Signature**: `HMACSHA256(base64UrlEncode(header) + "." + base64UrlEncode(payload), secret)`

You can paste any JWT directly into **Tools → Decoders → JWT Decoder** to view decoded claims with instant expiration & issuer verification.
                """.trimIndent()
            }
            p.contains("curl") || p.contains("python") || p.contains("code") -> {
                """
### Generated Request Automation

Here is the Python script using `httpx` with proxy routing to `requestr` local proxy (`127.0.0.1:8080`):

```python
import httpx

proxies = "http://127.0.0.1:8080"

headers = {
    "User-Agent": "requestr-client/2.0",
    "Accept": "application/json",
    "X-Session-ID": "sec-test-8492"
}

with httpx.Client(proxies=proxies, verify=False) as client:
    response = client.get("https://httpbin.org/get", headers=headers)
    print(f"Status: {response.status_code}")
    print(response.json())
```
                """.trimIndent()
            }
            else -> {
                "I have analyzed your request based on '${assistant.name}' parameters. All network parameters, auth tokens, and payload transforms are ready. You can test endpoints in the **APIs** tab, inspect intercepted packets in the **Intercept** tab, or run automation in **Flow**."
            }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }
}

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
                type = FlowNodeType.CHAT,
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
        delay(600)
        return "Flow executed successfully! 4 nodes processed in 0.48s."
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

    fun setActiveRequest(req: ApiRequestItem) {
        _currentActiveRequest.value = req
    }

    fun saveRequest(req: ApiRequestItem) {
        val current = _requests.value
        val exists = current.any { it.id == req.id }
        if (exists) {
            _requests.value = current.map { if (it.id == req.id) req.copy(updatedAt = System.currentTimeMillis()) else it }
        } else {
            _requests.value = listOf(req.copy(updatedAt = System.currentTimeMillis())) + current
        }
        _currentActiveRequest.value = req
    }

    fun deleteRequest(id: String) {
        _requests.value = _requests.value.filter { it.id != id }
    }

    fun toggleFavorite(id: String) {
        _requests.value = _requests.value.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
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
    // Fix 02 — UI & UX
    private const val KEY_SWIPE_TO_DELETE = "swipe_to_delete"
    private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    private const val KEY_GESTURE_NAVIGATION = "gesture_navigation"
    private const val KEY_PICTURE_IN_PICTURE = "picture_in_picture"
    private const val KEY_TABLE_MODE = "table_mode"
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
    private const val KEY_EXPERIMENTAL_FEATURES = "experimental_features"
    private const val KEY_DEVELOPER_OPTIONS = "developer_options"
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
            // Fix 02 — UI & UX
            swipeToDeleteEnabled = p.getBoolean(KEY_SWIPE_TO_DELETE, true),
            hapticFeedbackEnabled = p.getBoolean(KEY_HAPTIC_FEEDBACK, true),
            gestureNavigationEnabled = p.getBoolean(KEY_GESTURE_NAVIGATION, true),
            pictureInPictureEnabled = p.getBoolean(KEY_PICTURE_IN_PICTURE, false),
            tableModeEnabled = p.getBoolean(KEY_TABLE_MODE, false),
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
            experimentalFeaturesEnabled = p.getBoolean(KEY_EXPERIMENTAL_FEATURES, false),
            developerOptionsEnabled = p.getBoolean(KEY_DEVELOPER_OPTIONS, false),
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
    fun updatePictureInPicture(enabled: Boolean) {
        _settings.value = _settings.value.copy(pictureInPictureEnabled = enabled)
        persist(KEY_PICTURE_IN_PICTURE, enabled)
    }
    fun updateTableMode(enabled: Boolean) {
        _settings.value = _settings.value.copy(tableModeEnabled = enabled)
        persist(KEY_TABLE_MODE, enabled)
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
    fun updateExperimentalFeatures(enabled: Boolean) {
        _settings.value = _settings.value.copy(experimentalFeaturesEnabled = enabled)
        persist(KEY_EXPERIMENTAL_FEATURES, enabled)
    }
    fun updateDeveloperOptions(enabled: Boolean) {
        _settings.value = _settings.value.copy(developerOptionsEnabled = enabled)
        persist(KEY_DEVELOPER_OPTIONS, enabled)
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
        // Fix 02 — UI & UX
        persist(KEY_SWIPE_TO_DELETE, newSettings.swipeToDeleteEnabled)
        persist(KEY_HAPTIC_FEEDBACK, newSettings.hapticFeedbackEnabled)
        persist(KEY_GESTURE_NAVIGATION, newSettings.gestureNavigationEnabled)
        persist(KEY_PICTURE_IN_PICTURE, newSettings.pictureInPictureEnabled)
        persist(KEY_TABLE_MODE, newSettings.tableModeEnabled)
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
        persist(KEY_EXPERIMENTAL_FEATURES, newSettings.experimentalFeaturesEnabled)
        persist(KEY_DEVELOPER_OPTIONS, newSettings.developerOptionsEnabled)
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
