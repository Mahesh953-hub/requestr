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
    /**
     * Master plan §2.13 — where an [AuthType.API_KEY] credential is placed on the wire.
     * Previously hardcoded to a request header; [apiKeyHeader] now doubles as the query
     * parameter name when this is [ApiKeyLocation.QUERY_PARAM]. Defaults to
     * [ApiKeyLocation.HEADER] so every already-saved request keeps its current behavior.
     */
    val apiKeyLocation: ApiKeyLocation = ApiKeyLocation.HEADER,
    val bodyType: RequestBodyType = RequestBodyType.NONE,
    val bodyContent: String = "{\n  \"message\": \"hello requestr\",\n  \"active\": true\n}",
    val formData: List<KeyValueItem> = emptyList(),
    val preScript: String = "// Pre-request script (JS/Python)\n// setHeader('X-Timestamp', Date.now());",
    val postScript: String = "// Post-response test script\n// assert(response.status === 200);",
    val isFavorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * v3.2 Part C — C2 (Request chaining): rules run against this request's *response* after a
     * successful Send. Each rule extracts one top-level JSON field from the response body and
     * stores it as a named variable on the active [com.example.data.env.Environment], so a
     * later request in the same chain can reference it via `{{variableName}}`.
     */
    val chainExtractions: List<ChainExtractionRule> = emptyList(),
    /**
     * v3.3 Part C — C3 (Test assertions / test runner): assertions evaluated against this
     * request's response by [com.example.data.repository.AssertionRunner]. Each one checks a
     * single target (status code / a response header / a top-level JSON body field / response
     * time) with a chosen comparison operator against an expected value.
     */
    val assertions: List<Assertion> = emptyList(),
    /**
     * Master plan §2.8 — per-saved-request mock mode. When true,
     * [com.example.data.repository.ApiTestrRepository.executeRequest] returns [mockStatusCode] /
     * [mockHeaders] / [mockBody] instead of dialing the network at all, so a screen can be
     * driven without a live backend. Deliberately distinct from the live-MITM
     * MOCK-stage rule (`MockConfig`, §1.5), which short-circuits
     * intercepted third-party traffic — this one is scoped to API Testr's own manual sends.
     */
    val mockEnabled: Boolean = false,
    val mockStatusCode: Int = 200,
    val mockStatusMessage: String = "OK",
    val mockHeaders: List<KeyValueItem> = emptyList(),
    /**
     * Canonical default mock body. This exact string is duplicated in
     * [com.example.data.local.SavedApiRequestEntity.mockBody] and in `MIGRATION_11_12`'s
     * `DEFAULT` clause, and all three must stay identical: if they diverge, a request that
     * existed before the upgrade behaves differently from an otherwise-identical request created
     * after it, which is a bug that only surfaces on real user devices. Kept to a single line
     * with no embedded newline so it round-trips through the migration's SQL literal unchanged.
     */
    val mockBody: String = "{\"mocked\": true}",
    /** Artificial latency applied before the mock is returned, so loading states stay testable. */
    val mockDelayMs: Long = 0L,
    /**
     * Master plan §2.x LOW-ADD — async polling for long-running operations that answer `202
     * Accepted` with a `Location` header instead of the final result right away. When enabled,
     * [com.example.data.repository.ApiTestrRepository.executeRequestWithScripts] follows a `202`
     * response's [pollingLocationHeader] header with repeated GETs (every [pollingIntervalMs],
     * up to [pollingMaxAttempts] times) until a non-202 (terminal) status is returned or the
     * attempt budget runs out, returning whichever response ended the loop.
     */
    val pollingEnabled: Boolean = false,
    val pollingIntervalMs: Long = 2000L,
    val pollingMaxAttempts: Int = 10,
    val pollingLocationHeader: String = "Location",
    /**
     * P2 fix (async-polling credential leak) — same-origin (scheme+host+port) is enforced by
     * default: [com.example.data.repository.ApiTestrRepository.executeRequestWithScripts] will
     * NOT follow a poll [Location] that resolves to a different origin than the original
     * request unless this is explicitly set. When it IS set, the poll request is still
     * sanitized -- credential-bearing headers (`Authorization`, `Cookie`, `Proxy-Authorization`,
     * the configured API-key header) and this request's auth/query-param state are stripped
     * rather than blindly resent to a third-party host, since a `Location` redirect legitimately
     * lands on a different origin (e.g. a signed cloud-storage URL) that must not receive the
     * original request's credentials.
     */
    val pollingAllowCrossOriginRedirect: Boolean = false,
)

/**
 * Master plan §2.13 — placement of an [AuthType.API_KEY] credential.
 * Stored as its `name` in [com.example.data.local.SavedApiRequestEntity.apiKeyLocation].
 */
enum class ApiKeyLocation { HEADER, QUERY_PARAM }

/**
 * v3.2 Part C — C2 (Request chaining): one "extract a response field into an Environment
 * variable" rule attached to a saved [ApiRequestItem]. [jsonPath] is fed to
 * [com.example.flow.JsonFieldExtractor] — the same extractor the Flow Transform node uses — so
 * chaining and Flow automation share one extraction implementation rather than two.
 */
data class ChainExtractionRule(
    val id: String = UUID.randomUUID().toString(),
    /** e.g. "token" or "$.token" — top-level JSON key in the response body to extract. */
    val jsonPath: String = "",
    /** Name the extracted value is stored under, referenced downstream as `{{variableName}}`. */
    val variableName: String = "",
    val enabled: Boolean = true,
)

/**
 * v3.3 Part C — C3 (Test assertions / test runner): what part of a response an [Assertion]
 * checks. [BODY_JSON_FIELD] reuses [com.example.flow.JsonFieldExtractor] (the same top-level-key
 * extractor C2 already shares with the Flow Transform node) rather than a third extraction
 * implementation.
 */
enum class AssertionTarget {
    STATUS_CODE, HEADER_VALUE, BODY_JSON_FIELD, RESPONSE_TIME_MS
}

/** Comparison applied between the actual extracted value and [Assertion.expectedValue]. */
enum class AssertionOperator {
    EQUALS, CONTAINS, GREATER_THAN, LESS_THAN, MATCHES_REGEX
}

/**
 * v3.3 Part C — C3: one pass/fail check attached to a saved [ApiRequestItem], evaluated by
 * [com.example.data.repository.AssertionRunner] against a real [ApiResponseData] after Send.
 *
 * [targetKey] is only meaningful for [AssertionTarget.HEADER_VALUE] (the header name, e.g.
 * "content-type") and [AssertionTarget.BODY_JSON_FIELD] (the JSON field name/path, e.g. "token")
 * — ignored for [AssertionTarget.STATUS_CODE] and [AssertionTarget.RESPONSE_TIME_MS], which have
 * exactly one value to check.
 */
data class Assertion(
    val id: String = UUID.randomUUID().toString(),
    val target: AssertionTarget = AssertionTarget.STATUS_CODE,
    val targetKey: String = "",
    val operator: AssertionOperator = AssertionOperator.EQUALS,
    val expectedValue: String = "",
    val enabled: Boolean = true,
)

/**
 * v3.3 Part C — C3: the outcome of evaluating one [Assertion] against a real response, shown in
 * the Test Results UI with the actual value alongside what was expected so a failure is
 * immediately explainable rather than a bare pass/fail flag.
 */
data class AssertionResult(
    val assertion: Assertion,
    val passed: Boolean,
    val actualValue: String,
    val message: String = "",
)

/**
 * v3.3 Part C — C3: one request's full test run — its response plus every assertion's result —
 * used both for a single "Run Tests" tap and as one row inside a folder-level
 * [CollectionTestReport].
 */
data class RequestTestResult(
    val request: ApiRequestItem,
    val response: ApiResponseData?,
    val results: List<AssertionResult>,
    /** True when the request itself failed to execute (network/DNS/etc.) before any assertion could run. */
    val requestFailed: Boolean = false,
)

/**
 * v3.3 Part C — C3: summary produced by running every assertion-bearing saved request in a
 * folder/collection in sequence ("Test runner mode").
 */
data class CollectionTestReport(
    val folderId: String?,
    val requestResults: List<RequestTestResult>,
) {
    val totalAssertions: Int get() = requestResults.sumOf { it.results.size }
    val passedAssertions: Int get() = requestResults.sumOf { r -> r.results.count { it.passed } }
    val failedAssertions: Int get() = totalAssertions - passedAssertions
}

/**
 * S2 task 18: the defaults below used to be plausible-looking fabrications (`protocol = "h2"`,
 * `latencyMs = 142`, a made-up `server` header, fixed phase splits). They are now
 * empty/zero/null so an unmeasured value renders as "not measured" and can never pass for data.
 * Phase fields are nullable: null means the phase was not observed on this call (no TLS on
 * plain http, no DNS/connect on a reused connection, nothing at all for a mock).
 */
data class ApiResponseData(
    val statusCode: Int = 200,
    val statusMessage: String = "OK",
    val protocol: String = "",
    val latencyMs: Long = 0,
    val sizeBytes: Long = 0,
    val headers: Map<String, String> = emptyMap(),
    /**
     * Duplicate-capable wire header lines. [headers] remains the convenient single-value view for
     * scripts, assertions, polling, and existing callers; this list preserves repeated fields
     * such as Set-Cookie without illegal comma folding (Expires itself contains a comma).
     */
    val headerLines: List<Pair<String, String>> = headers.entries.map { it.key to it.value },
    val body: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true,
    val dnsTimeMs: Long? = null,
    val connectTimeMs: Long? = null,
    val tlsTimeMs: Long? = null,
    /** Request headers + body written (send phase). */
    val requestTimeMs: Long? = null,
    /** Request fully sent -> first response header byte (wait / TTFB). */
    val responseTimeMs: Long? = null,
    /** Response headers -> body fully read (receive phase). */
    val receiveTimeMs: Long? = null,
    /** True when the call reused a pooled connection, so DNS/connect/TLS legitimately did not run. */
    val connectionReused: Boolean = false,
    val error: String? = null,
    /**
     * Master plan §2.8 — true when this response was synthesized locally from a saved request's
     * `mock*` fields instead of received over the network. This is the flag callers and the UI
     * should branch on; [protocol] is only a human-readable display string, so matching on a
     * `"mock"` value there would be a magic-string check that any future protocol rename would
     * silently break. Defaults to false so every existing construction site — all of which
     * describe real network responses — stays correct unchanged.
     */
    val isMocked: Boolean = false
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
    /** T2: request was proxied on behalf of another device on the LAN via GatewayEngine. */
    GATEWAY("Gateway"),
    /** Reported by a third-party app embedding the requestr Observer SDK (CaptureTier.SDK). */
    SDK("SDK"),
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


fun ApiResponseData.headerValues(name: String): List<String> {
    val values = headerLines
        .filter { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        .map { it.second }
    if (values.isNotEmpty()) return values
    return headers.entries
        .filter { it.key.equals(name, ignoreCase = true) }
        .map { it.value }
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
    /**
     * Item 1 (dossier Q2 / audit §7.5): true iff THIS row's own
     * [com.example.data.local.CapturedTrafficEntity.captureMethod] is
     * [com.example.data.local.CaptureRowMethod.VPN_TUN] -- the exact boolean the traffic row's
     * VPN-relay icon needs, derived from the richer per-row enum at mapping time
     * ([com.example.data.interception.CaptureLogRepository.toUiModel]) rather than duplicating
     * the enum itself onto this UI-facing model. Defaults to false, matching
     * [com.example.data.local.CaptureRowMethod.UNKNOWN]'s "render as absent" contract -- a false
     * value here must never be read as "confirmed not VPN," only as "not confirmed VPN."
     */
    val routedViaVpnRelay: Boolean = false,
    /**
     * Audit fix (StatusCodeBadge "0" defect): true iff [statusCode] is a real, decoded
     * HTTP/QUIC status. False means no status line was ever decoded for this row (an HTTP/3
     * OBSERVED/DETECTED row, or a fully opaque undecoded-TLS row) and [statusCode] is only
     * carrying the historical `0` placeholder that legacy numeric call sites (sorting,
     * `statusCode >= 400` filters) already rely on — UI code that renders a label/badge for a
     * human MUST check this flag rather than testing `statusCode == 0` itself, since 0 is not
     * a reserved "unknown" sentinel for those numeric call sites, just a convenient default.
     * Defaults to true so every pre-existing construction site (previews, tests, exporters,
     * mocked responses) — none of which ever had a genuinely statusless row — is unaffected.
     * Set truthfully from the entity's own nullable status in
     * [com.example.data.interception.CaptureLogRepository.toUiModel].
     */
    val hasKnownStatus: Boolean = true,
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

/**
 * Master plan §3 LOW-ADD (C9) — launcher-label fallback chain for the VPN app-picker.
 *
 * `PackageManager.getApplicationLabel()` occasionally returns a blank string or the raw
 * package name for apps whose `android:label` resolution is broken (mislabelled/obfuscated
 * manifests, some OEM system apps). Android's own launcher instead resolves a package's
 * display name via the *launcher activity's* `ResolveInfo.loadLabel()` (from
 * `queryIntentActivities` against `ACTION_MAIN`/`CATEGORY_LAUNCHER`), which is frequently
 * correct even when the application-level label lookup fails — so it is tried first here.
 *
 * Resolution order: [launcherLabel] -> [applicationLabel] -> [packageName]. Each rung is
 * skipped if it is blank or is literally the package name (i.e. that lookup silently fell
 * back to the identifier instead of producing a real label). The final rung, [packageName],
 * is never blank for a well-formed package name, so this function never returns an empty
 * string.
 */
fun resolveAppDisplayLabel(
    launcherLabel: String?,
    applicationLabel: String?,
    packageName: String
): String {
    val launcher = launcherLabel?.trim().orEmpty()
    if (launcher.isNotEmpty() && launcher != packageName) return launcher

    val application = applicationLabel?.trim().orEmpty()
    if (application.isNotEmpty() && application != packageName) return application

    return packageName
}

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
    OUTPUT("Output / Alert", 0xFFEF4444, "📤"),
    DELAY("Delay", 0xFF8B5CF6, "⏱"),
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

/**
 * Single source of truth for a flow connection's deterministic id. Previously
 * `AppRepositories.kt`'s `toEntity()` computed `"${fromNodeId}_${fromPort}_${toNodeId}_${toPort}"`
 * while `FlowScreen.kt`'s port-drag built a third format (`"${fromId}_out_${toId}_in"`) and
 * `toFlowConnectionItem()` dropped `id` entirely, taking the random-UUID default instead. All
 * three call sites now use this function so `removeConnection(id)` can never silently miss a
 * Room-loaded connection.
 */
fun flowConnectionId(fromNodeId: String, fromPort: String, toNodeId: String, toPort: String): String =
    "${fromNodeId}_${fromPort}_${toNodeId}_${toPort}"

/**
 * A persisted flow pipeline (transplant slice 11). Backed by [FlowPipelineEntity]
 * in Room; the node/connection graph for each pipeline lives in the existing
 * `flow_nodes`/`flow_connections` tables keyed by `flowId == pipeline.id`.
 * `nodeCount` is queried at load time rather than embedded so we don't pull
 * every node of every pipeline into memory.
 */
data class FlowPipelineItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled Flow",
    val description: String = "",
    val triggerType: String = "Manual",
    val status: String = "DRAFT", // "RUNNING", "PAUSED", "DRAFT"
    val lastRunText: String = "Never",
    val runsCount: Int = 0,
    val nodeCount: Int = 0,
)

// F3: SYNTH_LOFI/AMBIENT_CYBER/CHILL_CODING were fabricated sources with no producer anywhere
// in the codebase (only data/music/LocalMusicScanner.kt ever produces a MusicTrackItem, and it
// always sets LOCAL) — deleted so the enum can't misrepresent where a track came from.
enum class MusicSourceType(val label: String) {
    LOCAL("Local Storage")
}

data class MusicTrackItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "No Track Selected",
    val artist: String = "",
    val durationSec: Int = 0,
    val source: MusicSourceType = MusicSourceType.LOCAL,
    val albumCoverHex: Long = 0xFF1E3A8A,
    val contentUri: String? = null,
    val albumArtUri: String? = null,
    val filePath: String? = null  // absolute path for MediaPlayer playback
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
    // Fix #30: removed isVpnActive, isRootDetected, isShizukuAvailable,
    // isFridaReady, isLsposedInstalled — these were initialized as false and never
    // updated by CapabilityProbe (which writes to InterceptionStateRepository).
    // The Settings Diagnostics section reads capabilities directly, not these.
    val autoBeautify: Boolean = true,
    val sslVerification: Boolean = false,
    val defaultSnippetLang: String = "Python - Requests",
    val onboardingCompleted: Boolean = false,
    // Fix 01 — Theme appearance
    val appearanceMode: String = "SYSTEM",
    val selectedDarkTheme: String = "MONOCHROME_DARK",
    // v3.1 B1 — mirrors selectedDarkTheme so LIGHT-family theme choice is remembered
    // independently of the DARK-family choice; fixes the theme-selection "collision"
    // where picking a theme always overwrote themeMode with no regard to which
    // appearanceMode family it belonged to.
    val selectedLightTheme: String = "EDITORIAL_LIGHT",
    // v6.0 — orthogonal accent axis: independent of appearanceMode/selectedDarkTheme/
    // selectedLightTheme. Picking an accent never touches the light/dark family fields.
    val selectedAccent: String = "INDIGO",
    // Fix 02 — UI & UX preferences
    val swipeToDeleteEnabled: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val gestureNavigationEnabled: Boolean = true,
    val densityMode: String = "COMFORTABLE",
    val updateNotificationPolicy: String = "MAJOR_ONLY",
    val animationEnabled: Boolean = true,
    val transitionBehavior: String = "STANDARD",
    val displayScale: String = "NORMAL",
    // Fix 02 — Features preferences
    val autoRecordingEnabled: Boolean = false,
    // trafficLogEnabled removed (complaint #4 audit fix): it was pure dead UI, never read by
    // any capture/persistence code path. "Traffic Capture" (trafficCaptureEnabled below) is
    // the real toggle that gates whether captured transactions are persisted.
    val recordingNotificationsEnabled: Boolean = true,
    val remoteControlEnabled: Boolean = false,
    val remoteCommandsEnabled: Boolean = false,
    val lanDeviceDiscoveryEnabled: Boolean = false,
    val deviceBroadcastingEnabled: Boolean = false,
    val trafficCaptureEnabled: Boolean = true,
    val requestLoggingEnabled: Boolean = true,
    val responseLoggingEnabled: Boolean = true,
    // Fix 05 — Music
    val musicPermissionStatus: String = "NOT_REQUESTED",
    val musicBarDismissed: Boolean = false,
    val musicLibraryScanned: Boolean = false,
    val selectedLocalTrackId: String? = null,
    // v6.0 transplant (plan task 6, manifest §5.11) — Navigation and Header Modifiers.
    // These back the glass nav-pill / floating-header visual modifiers semantically
    // recreated from main. Backup's appearanceMode/selectedAccent semantics are unchanged;
    // these are purely additive visual-preference fields with safe defaults. Canonical
    // glass-style keys are FROSTED_GLASS / SOLID_M3 / OLED_BLACK / TRANSLUCENT (DESIGN-RECORD §11).
    val navBarBackgroundOpacity: Float = 0.92f,
    val navBarBlurEffect: String = "FROSTED_GLASS",
    val navBarHiddenItems: Set<String> = emptySet(),
    val headerTopPaddingDp: Int = 8,
    val headerBackgroundOpacity: Float = 0.90f,
    val headerBlurStyle: String = "FROSTED_GLASS",
    val fabBottomSpacingDp: Int = 84,
    // Phase B — TUNE_GLASS parameters. Read ONLY when the corresponding glass style is
    // TUNE_GLASS; every other mode ignores them, so switching away to compare and switching back
    // preserves the user's dialled-in look. Defaults deliberately match FROSTED_GLASS so the
    // first time a user selects Tune Glass nothing jumps — they start from the familiar look and
    // tune outward from there.
    val glassTuneBlurDp: Int = 24,
    val glassTuneTintOpacity: Float = 0.90f,
    val glassTuneBorderOpacity: Float = 0.35f,
    val glassTuneShadowDp: Int = 6
)

/** Chat message for the Chats section (#44). */
data class ChatMessage(
    val id: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Task 12 — the chat provider configuration `ChatRepository` exposes to the UI.
 *
 * `name`/`baseUrl`/`model`/`isActive` are backed by the `chat_providers` Room table (task 11).
 * `apiKey` is NOT a Room column — it is read from / written to Keystore-backed storage
 * ([com.example.data.prefs.SecureKeyStore]) keyed by [id], and only ever lives in memory here.
 */
data class ChatProviderConfig(
    val id: String = "default",
    val name: String = "Default",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val isActive: Boolean = true,
) {
    /** A provider needs both an endpoint and a key before [ChatRepository] will call it. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}
