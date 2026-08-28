package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single captured HTTP(S) transaction, written by the real capture engine.
 *
 * There is deliberately no sample/seed data for this table. If interception has never run,
 * this table is empty and the UI must render a real empty state (APP_v2.md Bug 3.2 / 3.3).
 */
@Entity(
    tableName = "captured_traffic",
    indices = [Index("sessionId"), Index("host"), Index("timestamp"), Index("connectionId")],
)
data class CapturedTrafficEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val method: String,
    val url: String,
    val host: String,
    val path: String = "",
    val statusCode: Int? = null,
    val protocol: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val responseBody: String? = null,
    val requestSizeBytes: Long = 0,
    val responseSizeBytes: Long = 0,
    val latencyMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** Owning app resolved from the tun socket UID, when resolvable. */
    val appPackage: String? = null,
    val appUid: Int? = null,
    /** True when the transaction was decrypted via MITM rather than observed opaquely. */
    val wasDecrypted: Boolean = false,
    /** True when this row is an SNI-only observation (no body decrypted). */
    val isUndecoded: Boolean = false,
    /**
     * T2 (Gateway mode): the remote LAN client's IP address, set only when this row was
     * captured by [com.example.interception.gateway.GatewayEngine] proxying traffic that
     * originated on ANOTHER device (not this one). Null for every other capture path.
     */
    val sourceIp: String? = null,
    /**
     * Task 12: the real 5-tuple-derived connection key (same value as
     * [TcpConnectionEntity.connectionId]/[TlsSessionEntity.connectionId]) this HTTP transaction
     * was parsed out of, when known. Threaded through every persist path in task 12 so
     * [com.example.correlation.graph.ConnectionLineage] can join by exact key instead of the
     * `getByHostInWindow` 30s heuristic. Null for rows persisted before this column existed, or
     * for producers that genuinely have no connection to attach to (e.g. SDK-observed traffic).
     */
    val connectionId: String? = null,
)

/**
 * Capture-tier actually used for a session. Never inferred by the UI — recorded by the engine.
 *
 * [SDK] marks sessions populated by third-party apps embedding the `requestr-sdk` Observer
 * module (see `RequestrObserver` + `LocalApiServer`'s `/api/observe`) rather than by this app's
 * own VPN/root/Shizuku capture engines.
 *
 * [GATEWAY] marks the single dedicated session used by
 * [com.example.interception.gateway.GatewayEngine] for traffic proxied on behalf of OTHER
 * devices on the same LAN/hotspot (T2), independent of whatever on-device VPN/root/Shizuku
 * tier is (or isn't) simultaneously running.
 */
enum class CaptureTier { NONE, VPN, SHIZUKU, ROOT, SDK, GATEWAY, RUNTIME }

/**
 * One interception run. `packetCount` here is the real counter the Home card and status
 * readouts must read from (APP_v2.md Bug 1.5) — never a hardcoded number.
 */
@Entity(tableName = "capture_sessions")
data class CaptureSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val tier: CaptureTier = CaptureTier.NONE,
    val port: Int = 0,
    val packetCount: Int = 0,
    val bytesTransferred: Long = 0,
    val transactionCount: Int = 0,
    /** Null/empty means "all apps"; otherwise the explicit per-app filter used. */
    val filteredPackages: List<String> = emptyList(),
    val errorText: String? = null,
)

/**
 * What a matching [InterceptRuleEntity] does to a transaction.
 *
 * [MOCK_RESPONSE] (master plan §1.5) is the only action that never dials the real upstream
 * server: every other action — including [MODIFY_RESPONSE], which rewrites only *after* the
 * real round trip completes — performs the network call first. Its `replacementValue` reuses
 * the existing `{"statusCode","addHeaders","bodyReplace"}` schema already understood by
 * [com.example.interception.RuleEnforcer.applyResponseModification].
 *
 * Persisted by name via [RequestrTypeConverters.ruleActionToName], so adding a member needs no
 * schema migration — unknown/legacy names decode to [LOG_ONLY].
 *
 * [SCRIPT_REWRITE] (master plan §1.11) runs a real JavaScript `pm.*` script -- via
 * [com.example.interception.RuleEnforcer.applyScriptRewrite] -- against the live request or
 * response, the same engine ApiTestr's pre/post scripts use
 * ([com.example.scripting.JsScriptEngine]/[com.example.scripting.PmPrelude]). Its
 * `replacementValue` is `{"script": "<js source>", "variables": {"key":"value"}}` rather than the
 * fixed `{"addHeaders",...}` schema [MODIFY_REQUEST]/[MODIFY_RESPONSE] use -- kept as a distinct
 * action (not a replacement for them) so existing fixed-modification rules keep working
 * unchanged for users who don't need scripting.
 */
enum class RuleAction { BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY, FORCE_MITM, MOCK_RESPONSE, SCRIPT_REWRITE, CHAOS }

/**
 * User-defined intercept rules. The "Block this domain" long-press action must be gated on a
 * real existence query against this table (APP_v2.md Bug 3.8) rather than a visual-only
 * disabled state.
 */
@Entity(tableName = "intercept_rules", indices = [Index("domain")])
data class InterceptRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    /** Regex or glob matched against the request URL. */
    val pattern: String = "",
    /** Bare host this rule targets, used for fast block-existence checks. */
    val domain: String = "",
    val action: RuleAction = RuleAction.LOG_ONLY,
    val enabled: Boolean = true,
    val replacementValue: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A user-saved API Testr request.
 *
 * v3.1 fix (A4): previously `ApiCollectionsRepository` held saved requests only in an in-memory
 * `MutableStateFlow`, so Save/Star looked persistent but every request was lost on process death
 * or app restart (v3.1 spec Bug A4). This table is the real persistence layer; the id matches
 * [com.example.data.models.ApiRequestItem.id] (a client-generated UUID string) so saves are a
 * straightforward upsert rather than requiring an id round-trip.
 */
@Entity(tableName = "saved_api_requests", indices = [Index("isFavorite"), Index("updatedAt")])
data class SavedApiRequestEntity(
    @PrimaryKey val id: String,
    val name: String = "Untitled Request",
    val folderId: String? = null,
    val method: String = "GET",
    val url: String = "",
    val queryParams: List<SavedKeyValue> = emptyList(),
    val headers: List<SavedKeyValue> = emptyList(),
    val authType: String = "NONE",
    val bearerToken: String = "",
    val basicUsername: String = "",
    val basicPassword: String = "",
    val apiKeyHeader: String = "X-API-Key",
    val apiKeyValue: String = "",
    /** Master plan §2.13 — [com.example.data.models.ApiKeyLocation] name. */
    val apiKeyLocation: String = "HEADER",
    val bodyType: String = "NONE",
    val bodyContent: String = "",
    val formData: List<SavedKeyValue> = emptyList(),
    val preScript: String = "",
    val postScript: String = "",
    val isFavorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** v3.2 Part C — C2 (Request chaining): see [com.example.data.models.ChainExtractionRule]. */
    val chainExtractions: List<SavedChainExtractionRule> = emptyList(),
    /** v3.3 Part C — C3 (Test assertions): see [com.example.data.models.Assertion]. */
    val assertions: List<SavedAssertion> = emptyList(),
    /** Master plan §2.8 — per-request mock mode; see [com.example.data.models.ApiRequestItem]. */
    val mockEnabled: Boolean = false,
    val mockStatusCode: Int = 200,
    val mockStatusMessage: String = "OK",
    val mockHeaders: List<SavedKeyValue> = emptyList(),
    /** Must stay identical to [com.example.data.models.ApiRequestItem.mockBody]'s default and to
     *  `MIGRATION_11_12`'s `DEFAULT` clause -- see that field's KDoc for why. */
    val mockBody: String = "{\"mocked\": true}",
    val mockDelayMs: Long = 0L,
    /** Master plan §2.x LOW-ADD — async 202-polling; see [com.example.data.models.ApiRequestItem]. */
    val pollingEnabled: Boolean = false,
    val pollingIntervalMs: Long = 2000L,
    val pollingMaxAttempts: Int = 10,
    val pollingLocationHeader: String = "Location",
    /** P2 fix — see [com.example.data.models.ApiRequestItem.pollingAllowCrossOriginRedirect]. */
    val pollingAllowCrossOriginRedirect: Boolean = false,
)

/**
 * Plain-data mirror of [com.example.data.models.KeyValueItem] used only for JSON (de)serialization
 * inside [SavedApiRequestEntity]'s list columns. Kept separate from the UI model so the DB schema
 * never depends on `data.models` shapes changing.
 */
data class SavedKeyValue(
    val id: String = "",
    val key: String = "",
    val value: String = "",
    val isEnabled: Boolean = true,
    val description: String = "",
)

/**
 * Plain-data mirror of [com.example.data.models.ChainExtractionRule], same rationale as
 * [SavedKeyValue] above (v3.2 Part C — C2).
 */
data class SavedChainExtractionRule(
    val id: String = "",
    val jsonPath: String = "",
    val variableName: String = "",
    val enabled: Boolean = true,
)

/**
 * Plain-data mirror of [com.example.data.models.Assertion], same rationale as [SavedKeyValue]
 * above (v3.3 Part C — C3). Enum fields stored as `String` names, same pattern as
 * [SavedApiRequestEntity.method]/`authType`/`bodyType`.
 */
data class SavedAssertion(
    val id: String = "",
    val target: String = "STATUS_CODE",
    val targetKey: String = "",
    val operator: String = "EQUALS",
    val expectedValue: String = "",
    val enabled: Boolean = true,
)

// ── Flow persistence (Fix 8 / AUDIT_REPORT, Fix 15 / GAP_ANALYSIS) ─────

/**
 * Persisted Flow node. Previously `FlowRepository` held nodes in-memory only — positions
 * and configuration were lost on restart (UNTESTED_AREAS §5.2, GAP_ANALYSIS Tier 3 #15).
 */
@Entity(tableName = "flow_nodes", indices = [Index("flowId")])
data class FlowNodeEntity(
    @PrimaryKey val id: String,
    /** Which flow graph this node belongs to. */
    val flowId: String = "default",
    val type: String,  // FlowNodeType enum name
    val label: String = "",
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    /** JSON-encoded configuration (tool ID, API ID, script, etc.) */
    val configData: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Persisted Flow connection between two node ports.
 */
@Entity(
    tableName = "flow_connections",
    indices = [Index("flowId"), Index("fromNodeId"), Index("toNodeId")]
)
data class FlowConnectionEntity(
    @PrimaryKey val id: String,
    val flowId: String = "default",
    val fromNodeId: String,
    val fromPort: String = "output",
    val toNodeId: String,
    val toPort: String = "input",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Persisted Flow pipeline (multi-flow support, transplant slice 11).
 * Each pipeline owns a set of [FlowNodeEntity]/[FlowConnectionEntity] rows
 * keyed by the same `flowId` as this pipeline's `id`.
 */
@Entity(tableName = "flow_pipelines")
data class FlowPipelineEntity(
    @PrimaryKey val id: String,
    val name: String = "",
    val description: String = "",
    val triggerType: String = "Manual",
    val status: String = "DRAFT", // "RUNNING", "PAUSED", "DRAFT"
    val lastRunText: String = "Never",
    val runsCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

// ═══════════════════════════════════════════════════════════════════
// v5 Connection model entities
// ═══════════════════════════════════════════════════════════════════

@Entity(tableName = "tcp_connections", indices = [Index("sessionId"), Index("destIp")])
data class TcpConnectionEntity(
    @PrimaryKey val connectionId: String,
    val sessionId: Long,
    val srcIp: String, val destIp: String,
    val srcPort: Int, val destPort: Int,
    val state: String = "ESTABLISHED",      // SYN_SENT, ESTABLISHED, CLOSE_WAIT, etc.
    val establishedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val bytesSent: Long = 0, val bytesReceived: Long = 0,
    val retransmissions: Int = 0,
)

@Entity(tableName = "udp_flows", indices = [Index("sessionId"), Index("destIp")])
data class UdpFlowEntity(
    @PrimaryKey val flowId: String,
    val sessionId: Long,
    val srcIp: String, val destIp: String,
    val srcPort: Int, val destPort: Int,
    val transportProtocol: String = "udp",   // udp, quic, dns, etc.
    val startedAt: Long = System.currentTimeMillis(),
    val byteCount: Long = 0,
)

/**
 * Decode status of a QUIC connection, independent of lifecycle state.
 * Closing a connection (lifecycleState -> CLOSED) never changes this value.
 */
enum class QuicDecodeStatus {
    /** Detected by 5-tuple but never inspected for content. */
    DETECTED_ONLY,
    /** Key material is missing; connection was observed but never decrypted. */
    OBSERVED_NO_KEYS,
    /** Initial or Handshake was decrypted but full HTTP/3 was not. */
    PARTIALLY_DECODED,
    /** Handshake and 1-RTT application data were fully decrypted. */
    FULLY_DECODED
}

@Entity(tableName = "quic_connections", indices = [Index("sessionId"), Index("sniHost")])

data class QuicConnectionEntity(
    @PrimaryKey val connectionId: String,
    val sessionId: Long,
    val srcIp: String, val destIp: String,
    val srcPort: Int, val destPort: Int,
    val sniHost: String?,
    val alpn: String?,
    val quicVersion: Int?,
    val dstConnectionId: String,
    val establishedAt: Long = System.currentTimeMillis(),
    val decodeStatus: QuicDecodeStatus = QuicDecodeStatus.DETECTED_ONLY,
    val lifecycleState: String = "INITIALIZING",
    val updatedAt: Long = System.currentTimeMillis(),
    val clientRandomHex: String? = null,
    val keylogLabels: String? = null,
)

// ═══════════════════════════════════════════════════════════════════
// v5.6 TLS session model (M2)
// ═══════════════════════════════════════════════════════════════════

/**
 * Persisted counterpart of [com.example.interception.tls.TlsSession].
 *
 * Two producers write this table:
 *  - [com.example.interception.MitmProxyEngine] — one row per MITM'd CONNECT tunnel,
 *    populated from the *real* client-side [javax.net.ssl.SSLSession] (negotiated cipher
 *    suite, protocol version, ALPN, peer certificate chain from the upstream leg) plus the
 *    ClientHello JA3 fingerprint parsed from the raw bytes before we terminated TLS.
 *  - [com.example.interception.CaptureEngine] — one row per non-MITM'd (VPN/undecoded) TLS
 *    stream, populated from [com.example.interception.tls.TlsDecoder.parseClientHello] only
 *    (SNI/JA3/offered ciphers — no server-side fields since we never decrypt that path).
 *
 * [handshakeState] and [wasDecrypted] tell the UI which producer wrote the row so it can be
 * honest about what is/isn't known (mirrors the wasDecrypted/isUndecoded pattern already used
 * on [CapturedTrafficEntity]).
 */
@Entity(tableName = "tls_sessions", indices = [Index("sessionId"), Index("sniHost"), Index("connectionId")])
data class TlsSessionEntity(
    @PrimaryKey val tlsSessionId: String,
    val sessionId: Long,
    val connectionId: String,
    val sniHost: String?,
    val version: String?,               // negotiated (MITM) or ClientHello-offered (undecoded)
    val cipherSuite: String?,           // negotiated cipher suite name; null when undecoded
    val negotiatedProtocol: String?,    // ALPN result: h2, http/1.1, null if unknown
    val ja3Hash: String?,
    val handshakeState: String,         // CLIENT_HELLO, ESTABLISHED, FAILED (TlsHandshakeState.name) —
                                         // FAILED is real and reachable: see TcpStream.resetByPeer,
                                         // TlsDecoder.parseAlert/findAlertInRecords (task 7), and
                                         // TlsHandshakeStateMachine (task 11).
    val wasDecrypted: Boolean,          // true = MITM'd (real cipher/cert data), false = SNI/JA3-only
    val peerCertSubject: String?,
    val peerCertIssuer: String?,
    val establishedAt: Long = System.currentTimeMillis(),
    val handshakeDurationMs: Long = 0,
    /** Task 10/11: human-readable reason the handshake reached [handshakeState] == FAILED
     * (e.g. "RST from peer during handshake", "fatal alert: handshake_failure"). Null unless
     * FAILED. */
    val failureReason: String? = null,
    /** Task 10/11: wall-clock time (ms) the handshake was determined to have failed. Null
     * unless FAILED. */
    val failedAt: Long? = null,
)

// ═══════════════════════════════════════════════════════════════════
// v5.7 DNS query/response model (D2 prerequisite)
// ═══════════════════════════════════════════════════════════════════

/**
 * A single DNS query or response observed on the tun interface (UDP port 53), written by
 * [com.example.interception.PacketPump] via [com.example.interception.dns.DnsParser].
 *
 * Previously [com.example.interception.dns.DnsParser] parsed real DNS traffic but nothing
 * consumed the result -- `PacketPump.dnsCallback` was declared but never set by any caller, so
 * every DNS query/response was parsed and immediately discarded (logged, not persisted). This
 * table is what makes DNS a genuine first step in [com.example.correlation.graph.ConnectionLineage]
 * (D2) instead of a documented gap: [resolvedIps] on a response row lets the lineage builder
 * find the DNS answer that resolved the hostname a later TCP/TLS connection actually dialed.
 *
 * Same "never fabricate" rule as the rest of this schema: [resolvedIps] is empty unless
 * [com.example.interception.dns.DnsParser] genuinely decoded A/AAAA answer records.
 */
@Entity(tableName = "dns_queries", indices = [Index("sessionId"), Index("hostname"), Index("timestamp")])
data class DnsQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val transactionId: Int,
    val hostname: String,
    val queryType: String,              // A, AAAA, CNAME, ... (DnsQuery.qtypeName)
    val isResponse: Boolean,
    /** IPv4/IPv6 addresses this response resolved [hostname] to; empty for queries. */
    val resolvedIps: List<String> = emptyList(),
    val resolver: String = "UDP:53",    // UDP:53 today; DoT/DoH would need a different capture path
    val timestamp: Long = System.currentTimeMillis(),
)

// ═══════════════════════════════════════════════════════════════════
// v9 app-scope tables (task 10, DDL per docs/plans/plan-app.md H1 — column shapes owned by the
// app scope; this file only owns the @Entity registration + DDL so MIGRATION_8_9 has a real
// CREATE TABLE to run instead of a destructive wipe). Entity fields mirror the app scope's
// domain models: NoteItem/NoteFolder (data/models/AppModels.kt), ToolsRepository.CustomTool
// (data/repository/ToolsRepository.kt), ChatMessage (data/models/AppModels.kt). Two fields have
// no equivalent in today's domain model yet (NoteItem/CustomTool have no createdAt) -- H1's DDL
// still requires createdAt NOT NULL on notes/custom_tools, so it is included here with a
// System.currentTimeMillis() default; the app scope's repository mapping fills it in on insert.
// NoteFolder's Kotlin field is named iconEmoji; H1's column is named emoji -- the DDL/column name
// wins here since Room maps by @Entity/@ColumnInfo, not by matching a field name to a domain
// class the app scope owns.
// ═══════════════════════════════════════════════════════════════════

/** Backs [com.example.data.repository.NotesRepository]'s persisted notes (H1). */
@Entity(
    tableName = "notes",
    indices = [Index("isPinned"), Index("updatedAt")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val format: String,                 // NoteFormatType.name: PLAIN, MARKDOWN, HTML, CHECKLIST
    val folderId: String? = null,
    val folderName: String,
    val folderColorHex: Long,
    val isPinned: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Backs [com.example.data.repository.NotesRepository]'s persisted folders (H1). */
@Entity(tableName = "note_folders")
data class NoteFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: Long,
    val emoji: String,
)

/** Backs `ToolsRepository.customTools` (H1). */
@Entity(tableName = "custom_tools")
data class CustomToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val operation: String,              // "regex-replace" or "template"
    val pattern: String,
    val replacement: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Backs `ChatRepository`'s persisted chat history (H1). */
@Entity(
    tableName = "chat_messages",
    indices = [Index("timestamp")],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String,                   // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Backs H2's provider list. Deliberately has NO API-key column -- the key stays in
 * Keystore-backed storage only, never Room or DataStore. Do not add one here.
 */
@Entity(tableName = "chat_providers")
data class ChatProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val isActive: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

// ═══════════════════════════════════════════════════════════════
// CONNECT Control subsystem
// ═══════════════════════════════════════════════════════════════════

/**
 * One record per CONNECT (HTTPS tunnel establishment) attempt observed by
 * [com.example.interception.MitmProxyEngine], [com.example.interception.HttpProxyEngine], or
 * [com.example.interception.gateway.GatewayEngine].
 *
 * Previously every CONNECT tunnel was opaque: no rule evaluation beyond BLOCK, no persistence,
 * zero observability into target/disposition/bytes. This table is what makes a CONNECT attempt
 * a first-class, inspectable record instead of a blind byte relay.
 *
 * Modeled as its own entity rather than crammed into [CapturedTrafficEntity] (which the old
 * GatewayEngine workaround did with `method = "CONNECT"` / `isUndecoded = true`) because a
 * CONNECT is a tunnel *establishment* — no request/response body, no status code semantics, and
 * a different lifecycle (opened, then possibly MITM'd/relayed/blocked/redirected, then closed).
 */
@Entity(
    tableName = "connect_attempts",
    indices = [Index("sessionId"), Index("targetHost"), Index("timestamp")],
)
data class ConnectAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val targetHost: String,
    val targetPort: Int,
    /** UID of the local app that opened the tunnel, when resolvable via ConnectivityManager. */
    val clientUid: Int? = null,
    /** Remote LAN client IP, set only for gateway-proxied CONNECTs (mirrors CapturedTrafficEntity.sourceIp). */
    val sourceIp: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val negotiatedAlpn: String? = null,
    /** ALLOWED_OPAQUE, MITM_DECRYPTED, BLOCKED, REDIRECTED, FAILED. */
    val disposition: String,
    val redirectTarget: String? = null,
    val bytesToClient: Long = 0,
    val bytesToUpstream: Long = 0,
    val failureReason: String? = null,
    /** NEGOTIATED, FAILED — mirrors TlsSessionEntity.handshakeState's vocabulary. */
    val handshakeState: String? = null,
    val finishedAt: Long? = null,
)
