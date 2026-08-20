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
    indices = [Index("sessionId"), Index("host"), Index("timestamp")],
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
)

/** Capture-tier actually used for a session. Never inferred by the UI — recorded by the engine. */
enum class CaptureTier { NONE, VPN, SHIZUKU, ROOT }

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

enum class RuleAction { BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY }

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
