package com.example.data.models

import java.util.UUID

enum class HttpMethod(val colorHex: Long) {
    GET(0xFF22C55E),
    POST(0xFF3B82F6),
    PUT(0xFFF59E0B),
    DELETE(0xFFEF4444),
    PATCH(0xFF8B5CF6),
    HEAD(0xFF64748B),
    OPTIONS(0xFFEC4899),
    WS(0xFF06B6D4)
}

enum class RequestBodyType {
    NONE, JSON, FORM, MULTIPART, RAW, GRAPHQL, BINARY
}

enum class AuthType {
    NONE, BEARER, BASIC, API_KEY, OAUTH2, CUSTOM
}

data class KeyValueItem(
    val id: String = UUID.randomUUID().toString(),
    val key: String = "",
    val value: String = "",
    val isEnabled: Boolean = true,
    val description: String = ""
)

data class ApiRequestItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled Request",
    val folderId: String? = null,
    val method: HttpMethod = HttpMethod.GET,
    val url: String = "https://httpbin.org/get",
    val queryParams: List<KeyValueItem> = listOf(
        KeyValueItem(key = "format", value = "json", isEnabled = true),
        KeyValueItem(key = "debug", value = "1", isEnabled = false)
    ),
    val headers: List<KeyValueItem> = listOf(
        KeyValueItem(key = "Accept", value = "application/json", isEnabled = true),
        KeyValueItem(key = "User-Agent", value = "requestr/2.0-Android", isEnabled = true)
    ),
    val authType: AuthType = AuthType.NONE,
    val bearerToken: String = "",
    val basicUsername: String = "",
    val basicPassword: String = "",
    val apiKeyHeader: String = "X-API-Key",
    val apiKeyValue: String = "",
    val bodyType: RequestBodyType = RequestBodyType.NONE,
    val bodyContent: String = "{\n  \"message\": \"hello requestr\",\n  \"active\": true\n}",
    val formData: List<KeyValueItem> = emptyList(),
    val preScript: String = "// Pre-request script (JS/Python)\n// setHeader('X-Timestamp', Date.now());",
    val postScript: String = "// Post-response test script\n// assert(response.status === 200);",
    val isFavorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ApiResponseData(
    val statusCode: Int = 200,
    val statusMessage: String = "OK",
    val protocol: String = "h2",
    val latencyMs: Long = 142,
    val sizeBytes: Long = 1024,
    val headers: Map<String, String> = mapOf(
        "content-type" to "application/json; charset=utf-8",
        "server" to "requestr-proxy/2.0",
        "x-request-id" to UUID.randomUUID().toString().take(8)
    ),
    val body: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true,
    val dnsTimeMs: Long = 18,
    val connectTimeMs: Long = 24,
    val tlsTimeMs: Long = 38,
    val requestTimeMs: Long = 12,
    val responseTimeMs: Long = 50,
    val error: String? = null
)

data class ApiFolderItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "General",
    val colorHex: Long = 0xFF3B82F6,
    val iconName: String = "folder"
)

enum class ProxyTierType(val displayName: String, val badgeColor: Long) {
    VPN("VPN Service", 0xFF3B82F6),
    PROXY("127.0.0.1 Proxy", 0xFF06B6D4),
    ROOT("Root (Magisk/KSU)", 0xFF10B981),
    SHIZUKU("Shizuku Elevated", 0xFF8B5CF6),
    FRIDA("Frida Companion", 0xFFF43F5E),
    LSPOSED("LSPosed Module", 0xFFF59E0B)
}

/** Where a captured request originated. */
enum class TrafficSource(val label: String) {
    NATIVE("Native"),
    WEBVIEW("WebView"),
    BROWSER("Browser"),
    FRIDA("Frida"),
    UNKNOWN("Unknown"),
}

/** Protocol observed for a captured request. */
enum class ProtocolHint(val label: String, val shortLabel: String) {
    HTTP11("HTTP/1.1", "h1"),
    HTTP2("HTTP/2", "h2"),
    HTTP3("HTTP/3", "h3"),
    WEBSOCKET("WebSocket", "ws"),
    SSE("SSE", "sse"),
    QUIC("QUIC", "quic"),
    UNKNOWN("Unknown", "?"),
}

data class CapturedTrafficItem(
    val id: String = UUID.randomUUID().toString(),
    val method: HttpMethod = HttpMethod.GET,
    val url: String = "https://api.github.com/users/octocat",
    val host: String = "api.github.com",
    val path: String = "/users/octocat",
    val statusCode: Int = 200,
    val statusMessage: String = "OK",
    val protocol: String = "h2",
    val latencyMs: Long = 118,
    val requestSize: Long = 340,
    val responseSize: Long = 1840,
    val requestHeaders: Map<String, String> = mapOf(
        "Host" to "api.github.com",
        "User-Agent" to "requestr-engine/2.0",
        "Accept" to "application/json"
    ),
    val responseHeaders: Map<String, String> = mapOf(
        "content-type" to "application/json; charset=utf-8",
        "server" to "GitHub.com",
        "x-ratelimit-remaining" to "59"
    ),
    val requestBody: String = "",
    val responseBody: String = "{\n  \"login\": \"octocat\",\n  \"id\": 583231,\n  \"type\": \"User\",\n  \"public_repos\": 8\n}",
    val timestamp: Long = System.currentTimeMillis(),
    val appPackage: String = "com.github.android",
    val appName: String = "GitHub",
    val isPinned: Boolean = false,
    val isBlocked: Boolean = false,
    val isModified: Boolean = false,
    // ── V3: Traffic classification ──────────────────────────────────────
    val trafficSource: TrafficSource = TrafficSource.NATIVE,
    val protocolHint: ProtocolHint = ProtocolHint.UNKNOWN,
    // ── V3: Timing breakdown (ms) ──────────────────────────────────────
    val dnsMs: Long = -1,
    val connectMs: Long = -1,
    val tlsMs: Long = -1,
    val waitMs: Long = -1,
    val downloadMs: Long = -1,
    // ── V3: TLS diagnostics ────────────────────────────────────────────
    val tlsVersion: String = "",
    val tlsCipher: String = "",
    val alpnProtocol: String = "",
    // ── V3: Tags ───────────────────────────────────────────────────────
    val tags: List<String> = emptyList(),
)

enum class RuleAction(val label: String, val colorHex: Long) {
    LOG_ONLY("Log Only", 0xFF64748B),
    BLOCK("Block Request", 0xFFEF4444),
    MODIFY_REQUEST("Modify Request", 0xFF3B82F6),
    MODIFY_RESPONSE("Modify Response", 0xFF8B5CF6),
    BREAKPOINT("Breakpoint (Pause)", 0xFFF59E0B),
    MOCK_RESPONSE("Mock Response", 0xFF10B981)
}

data class TrafficRuleItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mock Auth API",
    val isEnabled: Boolean = true,
    val urlPattern: String = ".*auth/login.*",
    val method: HttpMethod? = HttpMethod.POST,
    val action: RuleAction = RuleAction.MOCK_RESPONSE,
    val mockStatusCode: Int = 200,
    val mockResponseBody: String = "{\"status\":\"success\",\"token\":\"mock_jwt_requestr_eyJhbGciOi...\"}",
    val mockHeaders: Map<String, String> = mapOf("content-type" to "application/json"),
    val priority: Int = 1
)

data class CertificateDetails(
    val commonName: String = "requestr CA Root",
    val organization: String = "requestr Security Lab",
    val serialNumber: String = "7A:B4:89:E2:01:C9:4F:9D",
    val validFrom: String = "2026-01-01",
    val validTo: String = "2036-01-01",
    val sha256Fingerprint: String = "3F:A9:41:88:C2:5E:7B:19:D4:62:00:81:AA:9C:3E:45:B1:09:88:2F:E1:92:43:88:99:A1:00:CC:23:41:7A:B9",
    val isInstalledUser: Boolean = true,
    val isInstalledSystem: Boolean = false,
    val isMagiskModuleGenerated: Boolean = false
)

data class AppPackageItem(
    val packageName: String,
    val appName: String,
    val isInterceptEnabled: Boolean = true,
    val isSystemApp: Boolean = false
)

enum class NoteFormatType(val label: String) {
    PLAIN("Plain"),
    MARKDOWN("Markdown"),
    HTML("HTML"),
    CHECKLIST("Checklist")
}

data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Untitled Note",
    val content: String = "",
    val format: NoteFormatType = NoteFormatType.MARKDOWN,
    val folderId: String? = null,
    val folderName: String = "General",
    val folderColorHex: Long = 0xFF3B82F6,
    val isPinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val wordCount: Int get() = if (content.isBlank()) 0 else content.trim().split("\\s+".toRegex()).size
    val charCount: Int get() = content.length
}

enum class FlowNodeType(val label: String, val colorHex: Long, val iconEmoji: String) {
    API("API Node", 0xFF3B82F6, "🌐"),
    INTERCEPT_TRIGGER("Intercept Trigger", 0xFF06B6D4, "📡"),
    TOOL("Tool Node", 0xFFF59E0B, "🔧"),
    CONDITION("Condition Node", 0xFFEC4899, "🔀"),
    TRANSFORM("Transform (JSONPath)", 0xFF10B981, "🔄"),
    TEXT("Template Text", 0xFF64748B, "📝"),
    OUTPUT("Output / Alert", 0xFFEF4444, "📤")
}

data class FlowNodeItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "API Request",
    val type: FlowNodeType = FlowNodeType.API,
    val x: Float = 100f,
    val y: Float = 100f,
    val inputPorts: List<String> = listOf("in"),
    val outputPorts: List<String> = listOf("out"),
    val configData: String = "https://httpbin.org/json",
    val lastResult: String = ""
)

data class FlowConnectionItem(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val fromPort: String = "out",
    val toNodeId: String,
    val toPort: String = "in"
)

enum class MusicSourceType(val label: String) {
    SYNTH_LOFI("requestr Synth Beats"),
    AMBIENT_CYBER("Cyber Cyberpunk Ambience"),
    CHILL_CODING("Chill Coding Terminal"),
    LOCAL("Local Storage")
}

data class MusicTrackItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neon Trace (Cyber Synth)",
    val artist: String = "requestr Audio Engine",
    val durationSec: Int = 184,
    val source: MusicSourceType = MusicSourceType.SYNTH_LOFI,
    val albumCoverHex: Long = 0xFF1E3A8A,
    val contentUri: String? = null,
    val albumArtUri: String? = null
)

enum class AppearanceMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");
}

data class UserSettingsState(
    val userName: String = "",
    val themeMode: String = "MONOCHROME_DARK",
    val cornerRadiusDp: Int = 16,
    val proxyPort: Int = 8080,
    val isVpnActive: Boolean = false,
    val isRootDetected: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val isFridaReady: Boolean = false,
    val isLsposedInstalled: Boolean = false,
    val autoBeautify: Boolean = true,
    val sslVerification: Boolean = false,
    val defaultSnippetLang: String = "Python - Requests",
    val onboardingCompleted: Boolean = false,
    // Fix 01 — Theme appearance
    val appearanceMode: String = "SYSTEM",
    val selectedDarkTheme: String = "MONOCHROME_DARK",
    // Fix 02 — UI & UX preferences
    val swipeToDeleteEnabled: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val gestureNavigationEnabled: Boolean = true,
    val pictureInPictureEnabled: Boolean = false,
    val tableModeEnabled: Boolean = false,
    val densityMode: String = "COMFORTABLE",
    val updateNotificationPolicy: String = "MAJOR_ONLY",
    val animationEnabled: Boolean = true,
    val transitionBehavior: String = "STANDARD",
    val displayScale: String = "NORMAL",
    // Fix 02 — Features preferences
    val autoRecordingEnabled: Boolean = false,
    val trafficLogEnabled: Boolean = true,
    val recordingNotificationsEnabled: Boolean = true,
    val remoteControlEnabled: Boolean = false,
    val remoteCommandsEnabled: Boolean = false,
    val lanDeviceDiscoveryEnabled: Boolean = false,
    val deviceBroadcastingEnabled: Boolean = false,
    val trafficCaptureEnabled: Boolean = true,
    val requestLoggingEnabled: Boolean = true,
    val responseLoggingEnabled: Boolean = true,
    val experimentalFeaturesEnabled: Boolean = false,
    val developerOptionsEnabled: Boolean = false,
    // Fix 05 — Music
    val musicPermissionStatus: String = "NOT_REQUESTED",
    val musicBarDismissed: Boolean = false,
    val musicLibraryScanned: Boolean = false,
    val selectedLocalTrackId: String? = null
)
