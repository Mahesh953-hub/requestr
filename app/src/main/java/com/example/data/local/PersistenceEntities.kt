package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * S2 task 14 -- Room v19 "persistence spine" tables (plan §4, v19 row).
 *
 * All three are children of `captured_traffic` with `ON DELETE CASCADE`, so deleting or pruning a
 * transaction row (task 19) removes its headers, timings and rule provenance in the same SQLite
 * statement. They are additive: nothing about the v18 columns changes, and the legacy
 * `requestHeaders`/`responseHeaders` map columns stay in place as a derived, lossy cache for the
 * eight existing readers until they move to the normalized rows.
 */

/** Which header block of the exchange a [TransactionHeaderEntity] row belongs to. */
enum class HeaderKind {
    REQUEST,
    RESPONSE,
    /** HTTP/1.1 chunked trailers or HTTP/2 trailing HEADERS on the request side. */
    REQUEST_TRAILER,
    RESPONSE_TRAILER,
    /** HTTP/2 `:method`/`:path`/`:authority`/`:scheme`/`:status` pseudo-headers. */
    REQUEST_PSEUDO,
    RESPONSE_PSEUDO,
}

/**
 * One header line exactly as it appeared on the wire: original casing, original position,
 * duplicates preserved. This is what makes repeated `Set-Cookie`, duplicate `Via`/`Warning`
 * lines and trailers representable -- the legacy `Map<String, String>` columns fold them.
 */
@Entity(
    tableName = "transaction_headers",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trafficId", "kind", "ordinal"]),
        Index(value = ["nameLower"]),
    ],
)
data class TransactionHeaderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trafficId: Long,
    val kind: HeaderKind,
    /** 0-based wire position within its [kind] block. */
    val ordinal: Int,
    /** Original wire casing. */
    val name: String,
    /** Lower-cased [name] for case-insensitive lookup without `LOWER()` scans. */
    val nameLower: String = name.lowercase(),
    val value: String,
)

/**
 * Measured phase durations for one transaction. Every value is nullable and NULL means "not
 * observed on this capture path" -- never `-1`, never `0` as a stand-in. A measured zero is a
 * real measurement (e.g. TLS on a reused connection). Nothing in this table may be derived by
 * scaling a total; task 18 deletes exactly that kind of fabrication.
 */
@Entity(
    tableName = "transaction_timings",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TransactionTimingEntity(
    @PrimaryKey val trafficId: Long,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    /** Request headers + body on the wire. */
    val sendMs: Long? = null,
    /** Time to first response byte after the request was fully sent. */
    val waitMs: Long? = null,
    /** First response byte to last response byte. */
    val receiveMs: Long? = null,
    /** Wall clock from the first observable instant to the last, when measured end to end. */
    val totalMs: Long? = null,
    /** Epoch millis; anchor instants so consumers can reconstruct absolute phases. */
    val queuedAt: Long? = null,
    val firstByteAt: Long? = null,
    val lastByteAt: Long? = null,
)

/**
 * Rule-provenance row: one stage outcome for one transaction. Written by the S4 pipeline's
 * `FlowAuditTrail`; created here (v19) so the rules lane never owns a migration for it (§4
 * reconciliation note).
 */
@Entity(
    tableName = "applied_rules",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trafficId"])],
)
data class AppliedRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trafficId: Long,
    /** Typed-rule id (S4) or the legacy `intercept_rules.id` as text. */
    val ruleId: String,
    val ruleName: String,
    /** Pipeline slot: GATEWAY, MIRROR, REWRITE, BREAKPOINT, SCRIPT, MOCK, BODY_CRYPTO, SHAPING, OBSERVE. */
    val stage: String,
    /** REQUEST or RESPONSE. */
    val phase: String,
    /** MATCHED, APPLIED, SKIPPED, FAILED, REFUSED ... -- free text owned by the pipeline. */
    val outcome: String,
    val detail: String? = null,
    val appliedAt: Long = 0,
)
