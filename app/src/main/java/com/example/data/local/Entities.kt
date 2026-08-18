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
