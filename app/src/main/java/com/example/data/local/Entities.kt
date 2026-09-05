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
    /**
     * True when [com.example.interception.http.HttpBodyDecoder.isProbablyUtf8] flagged this
     * transaction's request/response body as binary (images, protobufs, undecodable
     * Content-Encoding, ...). [requestBody]/[responseBody] (the text columns) are null/empty
     * for such a body -- [requestBodyBytes]/[responseBodyBytes] below are where the real bytes
     * live instead, when they were small enough to keep (see those fields' doc).
     *
     * This is the disambiguator a consumer needs: `isXBinary == false` means "this is (or would
     * have been, if present) a text body" -- [requestBody]/[responseBody] are authoritative and
     * [requestBodyBytes]/[responseBodyBytes] are always null. `isXBinary == true` means the real
     * content lives only in the `*BodyBytes` BLOB column (or was too large to keep -- see
     * [requestBodyBytesTruncated]/[responseBodyBytesTruncated]).
     */
    val isRequestBodyBinary: Boolean = false,
    val isResponseBodyBinary: Boolean = false,
    /**
     * The real, byte-exact request/response body when [isRequestBodyBinary]/
     * [isResponseBodyBinary] is true -- e.g. actual JPEG/PNG bytes for an image response, where
     * [responseBody] (text) only ever holds
     * [com.example.interception.http.HttpBodyDecoder.BINARY_BODY_PLACEHOLDER] (empty). This is
     * what a consumer (e.g. the image gallery) must decode instead of the text column whenever
     * the corresponding `isXBodyBinary` flag is set.
     *
     * Null in three distinct cases a consumer must NOT conflate:
     *  1. The body was text, not binary (`isXBodyBinary == false`) -- nothing was ever meant to
     *     be here; [requestBody]/[responseBody] hold the real content instead.
     *  2. The body was binary but exceeded
     *     [com.example.interception.http.HttpBodyDecoder.MAX_STORED_BODY_BYTES] --
     *     `isXBodyBinary == true` AND `XBodyBytesTruncated == true`: real bytes existed
     *     on the wire but were deliberately not persisted (too big). The true original size is
     *     still available from `requestSizeBytes`/`responseSizeBytes` even though the bytes
     *     themselves are gone, so the UI can still say "N KB, not stored".
     *  3. There was no body at all -- `isXBodyBinary == false` and `requestBody`/`responseBody`
     *     is also null/empty and the corresponding size field is 0.
     *
     * A non-null value here always means `isXBodyBinary == true` AND
     * `XBodyBytesTruncated == false`, and its size always equals the matching
     * `requestSizeBytes`/`responseSizeBytes`.
     *
     * EQUALITY NOTE: same caveat as [com.example.interception.http.ParsedHttpMessage.rawBodyBytes]
     * -- a `ByteArray` property in a `data class` downgrades the generated `equals`/`hashCode`
     * to array identity for this field. Nothing compares whole [CapturedTrafficEntity] instances
     * today; if that changes, this field needs deliberate handling.
     */
    val requestBodyBytes: ByteArray? = null,
    val responseBodyBytes: ByteArray? = null,
    /**
     * True iff the request/response body was flagged binary
     * ([isRequestBodyBinary]/[isResponseBodyBinary] true) but exceeded
     * [com.example.interception.http.HttpBodyDecoder.MAX_STORED_BODY_BYTES], so
     * [requestBodyBytes]/[responseBodyBytes] is null even though a real binary body existed.
     * This is the "not stored because it exceeded the cap" row flag distinguishing that case
     * from "no body at all" (where this stays false) -- see [requestBodyBytes]'s doc for the
     * full three-state contract. Never true when the corresponding `isXBodyBinary` is false.
     */
    val requestBodyBytesTruncated: Boolean = false,
    val responseBodyBytesTruncated: Boolean = false,
    /**
     * Item 1 (dossier Q2 / audit §7.5): which capture tier actually produced THIS row, recorded
     * by the engine at persist time rather than read off the current global capture setting --
     * a single database can contain rows captured under different tiers across sessions (e.g. a
     * VPN_TUN session followed by a MITM_PROXY one), so the row must record how IT was captured,
     * not what the app's capture mode happens to be right now.
     *
     * [CaptureRowMethod.UNKNOWN] is the default for every row persisted before this column
     * existed (backfilled by `MIGRATION_15_16`) -- the UI must render "unknown" as genuinely
     * absent, never as "definitely not VPN", since an old row's real capture method is simply
     * not recorded.
     */
    val captureMethod: CaptureRowMethod = CaptureRowMethod.UNKNOWN,
    /**
     * S2 task 16 (v19) -- the FOURTH body state: spilled to a file. When a body exceeds
     * [com.example.data.persistence.BodyStore.INLINE_LIMIT_BYTES] it is streamed to
     * `filesDir/captures/<sessionId>/<trafficId>.<req|res>.bin` and the row records the
     * relative path, the SHA-256 of the FULL body, a bounded UTF-8 preview, how many bytes were
     * actually stored (file size) and how many were on the wire. The inline text/BLOB columns are
     * null for a spilled body. A non-null `*BodyFilePath` whose file is missing renders as
     * "body evicted" (orphan sweep / pruning), never as an error.
     *
     * `*BodyTotalBytes` is 0 when unknown (rows older than v19 or paths that never measured it);
     * `requestSizeBytes`/`responseSizeBytes` remain the wire sizes the engines already record.
     */
    val requestBodyFilePath: String? = null,
    val responseBodyFilePath: String? = null,
    val requestBodySha256: String? = null,
    val responseBodySha256: String? = null,
    val requestBodyPreview: String? = null,
    val responseBodyPreview: String? = null,
    val requestBodyStoredBytes: Long = 0,
    val responseBodyStoredBytes: Long = 0,
    val requestBodyTotalBytes: Long = 0,
    val responseBodyTotalBytes: Long = 0,
    /**
     * API-Testr HTTP/3 (tasks 58-60, columns reserved in v19 per §4): the protocol the client
     * actually negotiated (`h3`, `h2`, `http/1.1`) and, when the request asked for more than it
     * got under `AUTO`, why it was downgraded. Null for captured (non-API-Testr) traffic.
     */
    val negotiatedProtocol: String? = null,
    val protocolDowngradeReason: String? = null,
    /**
     * S3 task 21 (v21): non-null on a parent row that [MIGRATION_20_21] synthesized from the
     * pre-v20 flattened `WS→`/`WS←` or `x-grpc-*` rows (`"v21-ws"` / `"v21-grpc"`), so a
     * backfilled flow is distinguishable from a live-captured 101 upgrade or gRPC call and the
     * backfill itself is idempotent. Null for every row a capture engine wrote.
     */
    val legacyBackfill: String? = null,
)

/**
 * Item 1: honest, per-row record of which capture tier produced a [CapturedTrafficEntity] row --
 * richer than a bare `routedViaVpnRelay: Boolean` because it also distinguishes MITM/gateway
 * rows from VPN ones, while the VPN-relay-icon boolean the UI needs is trivially
 * `captureMethod == VPN_TUN`.
 *
 * Deliberately NOT the same type as [com.example.data.interception.CaptureMethod] (a different,
 * pre-existing enum in an unrelated package) or [CaptureTier] above (that one is the *session's*
 * declared tier, e.g. for the capture-setup UI; this one is the *row's own* observed tier, set by
 * the engine that actually persisted it -- the two can legitimately disagree, e.g. a GATEWAY
 * session proxying another device's traffic never touches this device's VPN/root/Shizuku tier at
 * all).
 *
 * Every value below has a real, confirmed persist site as of this wave:
 *  - [VPN_TUN]: `CaptureEngine` (driven by `PacketPump`, itself only ever instantiated by
 *    `RequestrVpnService`) and `PacketPump`'s own HTTP/3 persist path.
 *  - [MITM_PROXY]: `MitmProxyEngine`, which only ever reaches its persist call sites after
 *    terminating TLS itself (`wasDecrypted` is always true for these rows).
 *  - [GATEWAY]: `gateway/GatewayEngine`'s `persistTransaction`.
 *  - [HTTP_PROXY]: `HttpProxyEngine`'s own `persistTransaction` (added after this enum was
 *    introduced — a separate audit pass found the plain, non-CONNECT relay path forwarded and
 *    counted every request but never persisted a `CapturedTrafficEntity` at all, permanently
 *    starving the traffic list for this tier; the fix mirrors [GATEWAY]'s `persistTransaction`
 *    exactly, including `HttpBodyDecoder.forStorage`'s binary/text/truncation contract).
 *
 *  - [RUNTIME]: `runtime/RuntimeTrafficSink` -- in-process Cronet/WebView/adapter observations
 *    (S2 task 15; previously written as [UNKNOWN]).
 *  - [SDK_OBSERVER]: `api/LocalApiServer.receiveObserverEvents` -- rows POSTed by the
 *    `requestr-sdk` Observer module or the MV3 extension (S2 task 15; previously [UNKNOWN]).
 *  - [HAR_IMPORT]: `data/import/HarImporter` -- rows reconstructed from a HAR file (S2 task 15;
 *    previously [UNKNOWN]).
 *
 * [UNKNOWN] is now only the default for legacy rows (see [CapturedTrafficEntity.captureMethod]'s
 * doc) written before the enum existed.
 */
enum class CaptureRowMethod { UNKNOWN, VPN_TUN, MITM_PROXY, GATEWAY, HTTP_PROXY, RUNTIME, SDK_OBSERVER, HAR_IMPORT }


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

/*
 * S4 task 34 (v24): the legacy `intercept_rules` table, `InterceptRuleEntity` and `RuleAction`
 * were dropped by MIGRATION_23_24. The v1 flat shape survives only as the input DTO
 * `com.example.interception.rules.LegacyInterceptRule` (imports, v1 API bodies); typed rules live
 * in `interceptor_rules` (RuleEntities.kt).
 */

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
    /**
     * v19 (§4, task 58): `AUTO` / `H1` / `H2` / `H3`. Stored as text so the enum can live in
     * `data/models/RequestProtocol.kt` when task 58 lands; every pre-v19 row reads `AUTO`.
     */
    val protocolPreference: String = "AUTO",
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
    FULLY_DECODED,
    /**
     * S1 task 9 (D6): teed for passive inspection, then dropped by the capture-scoped UDP/QUIC
     * policy so the client fell back to HTTP/2 over TCP. Terminal and sticky: no later packet can
     * promote it, because none is forwarded. Full QUIC MITM is not implemented (Chromium never
     * trusts user-added CAs for QUIC), so "blocked" or "observed" is the honest ceiling.
     */
    BLOCKED_BY_POLICY
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
    indices = [Index("timestamp"), Index("conversationId")],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String,                   // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // v16 -> v17: which conversation this message belongs to (see [ChatConversationEntity]).
    // Every pre-v17 row gets backfilled to MIGRATION_16_17's 'default-migrated' conversation id
    // -- see RequestrDatabase.MIGRATION_16_17's doc for the full backfill story. The Kotlin
    // default here is a convenience for tests/newly-constructed objects only; real production
    // callers (ChatRepository) always pass an explicit conversationId.
    val conversationId: String = "default",
)

/**
 * Multi-conversation chat history (Reqy Assistant completeness pass, item 1): one row per
 * conversation shown in the drawer's "AI History" list. [title] is either a user-set override
 * ([titleIsOverride] = true, set via the drawer's Rename action) or kept in sync with the
 * conversation's own derived title (see `deriveChatTitle`) as its messages change.
 */
@Entity(
    tableName = "chat_conversations",
    indices = [Index("updatedAt")],
)
data class ChatConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val titleIsOverride: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
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

/**
 * One row per item the user has actually opened, backing Home's "Recently opened" section via
 * [com.example.data.repository.RecentActivityRepository] (v17 -> v18).
 *
 * Before this table existed, Home fabricated its recents list from the first two saved API
 * requests and the first two notes with hardcoded `"Recent"`/`"Saved"` timestamp strings -- a
 * list that changed whenever the API list was reordered and never reflected anything the user
 * had done. This is the real usage history that replaces it.
 *
 * The row is a self-contained snapshot ([title]/[preview]/[subtype]) so the UI can render it
 * without re-querying the source table; the source item may legitimately have changed since.
 * Snapshots are refreshed by the owning repository when the item is saved/renamed, and the row
 * is removed when the item is deleted, so a row never points at nothing.
 *
 * Composite primary key ([itemType], [itemId]): re-opening an item updates its single row's
 * [openedAt] rather than appending a duplicate -- this is "recently opened", not "every open
 * ever". Ships with NO seed rows: an empty table means nothing has been opened yet.
 */
@Entity(
    tableName = "recent_activity",
    primaryKeys = ["itemType", "itemId"],
    indices = [Index("openedAt")],
)
data class RecentActivityEntity(
    /** [com.example.data.repository.RecentItemType].name -- API, NOTE, TOOL, CHAT, FLOW. */
    val itemType: String,
    /** The source item's own id (saved request id, note id, tool id, conversation id, flow id). */
    val itemId: String,
    /** Uppercase-able subtype label for the "{Type} • {SUBTYPE}" line, e.g. "MARKDOWN", "GET". */
    val subtype: String,
    val title: String,
    /** Short single-line metadata/preview shown under the title. */
    val preview: String,
    /** Real epoch-millis of the most recent open -- what the relative-timestamp pill formats. */
    val openedAt: Long,
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
