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
    private val _tracks = MutableStateFlow<List<MusicTrackItem>>(
        listOf(
            MusicTrackItem("t-1", "Neon Interceptor (Lofi Mix)", "requestr Synth Lab", 195, MusicSourceType.SYNTH_LOFI, 0xFF1E3A8A),
            MusicTrackItem("t-2", "Midnight Packet Stream", "Cyber Ambient", 240, MusicSourceType.AMBIENT_CYBER, 0xFF083344),
            MusicTrackItem("t-3", "Root Shell Flow", "Terminal Beats", 180, MusicSourceType.CHILL_CODING, 0xFF312E81),
            MusicTrackItem("t-4", "Bypass & Decrypt (Synthwave)", "Glitch Master", 215, MusicSourceType.SYNTH_LOFI, 0xFF701A75)
        )
    )
    val tracks: StateFlow<List<MusicTrackItem>> = _tracks.asStateFlow()

    private val _currentTrack = MutableStateFlow(_tracks.value.first())
    val currentTrack: StateFlow<MusicTrackItem> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionSec = MutableStateFlow(45)
    val currentPositionSec: StateFlow<Int> = _currentPositionSec.asStateFlow()

    fun togglePlayPause() {
        _isPlaying.value = !_isPlaying.value
    }

    fun nextTrack() {
        val current = _tracks.value
        val index = current.indexOfFirst { it.id == _currentTrack.value.id }
        val nextIndex = (index + 1) % current.size
        _currentTrack.value = current[nextIndex]
        _currentPositionSec.value = 0
    }

    fun prevTrack() {
        val current = _tracks.value
        val index = current.indexOfFirst { it.id == _currentTrack.value.id }
        val prevIndex = if (index - 1 < 0) current.size - 1 else index - 1
        _currentTrack.value = current[prevIndex]
        _currentPositionSec.value = 0
    }

    fun seekTo(sec: Int) {
        _currentPositionSec.value = sec.coerceIn(0, _currentTrack.value.durationSec)
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

    private val _requests = MutableStateFlow<List<ApiRequestItem>>(
        listOf(
            ApiRequestItem(
                id = "req-1",
                name = "HttpBin JSON Echo",
                folderId = "f-1",
                method = HttpMethod.GET,
                url = "https://httpbin.org/json",
                isFavorite = true
            ),
            ApiRequestItem(
                id = "req-2",
                name = "GitHub User Profile",
                folderId = "f-1",
                method = HttpMethod.GET,
                url = "https://api.github.com/users/octocat",
                isFavorite = true
            ),
            ApiRequestItem(
                id = "req-3",
                name = "Post JSON Payload",
                folderId = "f-1",
                method = HttpMethod.POST,
                url = "https://httpbin.org/post",
                bodyType = RequestBodyType.JSON,
                bodyContent = "{\n  \"client\": \"requestr\",\n  \"version\": \"2.0\",\n  \"active\": true\n}",
                isFavorite = false
            ),
            ApiRequestItem(
                id = "req-4",
                name = "Echo WebSocket",
                folderId = "f-4",
                method = HttpMethod.WS,
                url = "wss://echo.websocket.org",
                isFavorite = false
            )
        )
    )
    val requests: StateFlow<List<ApiRequestItem>> = _requests.asStateFlow()

    private val _currentActiveRequest = MutableStateFlow(_requests.value.first())
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
    private val _settings = MutableStateFlow(
        UserSettingsState(
            userName = "Kailash",
            themeMode = "MIDNIGHT",
            cornerRadiusDp = 16,
            proxyPort = 8080,
            isVpnActive = false,
            isRootDetected = true,
            isShizukuAvailable = true,
            isFridaReady = false,
            isLsposedInstalled = true,
            autoBeautify = true,
            sslVerification = false,
            defaultSnippetLang = "Python - Requests",
            onboardingCompleted = true
        )
    )
    val settings: StateFlow<UserSettingsState> = _settings.asStateFlow()

    fun updateUserName(name: String) {
        _settings.value = _settings.value.copy(userName = name)
    }

    fun updateTheme(themeMode: String) {
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun updateCornerRadius(radius: Int) {
        _settings.value = _settings.value.copy(cornerRadiusDp = radius)
    }

    fun updateProxyPort(port: Int) {
        _settings.value = _settings.value.copy(proxyPort = port)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        _settings.value = _settings.value.copy(onboardingCompleted = completed)
    }

    fun updateSettings(newSettings: UserSettingsState) {
        _settings.value = newSettings
    }
}
