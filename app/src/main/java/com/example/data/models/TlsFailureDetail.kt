package com.example.data.models

import com.example.data.local.TlsSessionEntity

/**
 * Task 14 / Lane D: UI-facing TLS handshake-failure detail, derived from the real
 * [TlsSessionEntity] a failed handshake persists. Drives
 * [com.example.ui.components.TlsFailureDetailSheet].
 *
 * [TlsSessionEntity] stores a single human-readable [TlsSessionEntity.failureReason] (written by
 * `classifyTlsOutcome` from the parsed alert or an observed RST/close) and the offered/negotiated
 * handshake metadata, but not per-stage timestamps or which side sent the alert as separate
 * columns. So, exactly as the implementation plan specifies, the handshake-stage rail and the
 * "alert sent by" attribution are *inferred from the alert type* (RFC 8446 semantics: unknown_ca is
 * a client-side trust rejection; handshake_failure is a server-side negotiation rejection) rather
 * than read from stored timestamps. Every other field is read straight off the entity.
 */
data class TlsFailureDetail(
    val sniHost: String?,
    val connectionId: String,
    /** Plain-language headline, e.g. "TLS handshake failed". */
    val headline: String,
    /** One-line plain-language explanation of *why* it failed. */
    val explanation: String,
    /** Coarse category label, e.g. "Trust rejection", "Negotiation failure". */
    val category: String,
    /** Who sent the terminating signal, e.g. "Client (the app)", "Server (upstream)". */
    val alertSentBy: String,
    /** The raw failure record (the entity's failureReason), shown in a code block. */
    val failureReason: String,
    /** Inferred handshake-stage rail, ordered ClientHello → ServerHello → terminating event. */
    val stages: List<TlsHandshakeStage>,
    val offeredVersion: String?,
    val cipherSuite: String?,
    val alpn: String?,
    val ja3Hash: String?,
    val handshakeDurationMs: Long,
    val failedAt: Long?,
    val remedy: TlsRemedyType,
)

/** One step of the inferred handshake-stage rail. */
data class TlsHandshakeStage(
    val label: String,
    val detail: String,
    val state: TlsStageState,
)

enum class TlsStageState { DONE, FAILED, NOT_REACHED }

/**
 * The remedy block shown at the bottom of the detail sheet: numbered guidance plus a primary
 * (filled) and optional secondary (ghost) action button. The labels and steps are taken verbatim
 * from the approved `tls-failed-detail-*` mockups.
 */
enum class TlsRemedyType(
    val primaryLabel: String,
    val secondaryLabel: String?,
    val steps: List<String>,
) {
    INSTALL_CA(
        primaryLabel = "Install requestr CA",
        secondaryLabel = "Bypass this host",
        steps = listOf(
            "Install the requestr CA — root is available, so promote it to the system store for apps targeting API 24+.",
            "If the app pins its certificate, no CA install helps — bypass the host below.",
        ),
    ),
    EXTEND_CIPHERS(
        primaryLabel = "Extend cipher set",
        secondaryLabel = "Bypass this host",
        steps = listOf(
            "Extend the cipher set — requestr's replayed ClientHello is narrower than the app's.",
            "Or the host wants a client certificate requestr cannot present; bypass it below.",
        ),
    ),
    BYPASS_HOST(
        primaryLabel = "Bypass this host",
        secondaryLabel = "Retry capture",
        steps = listOf(
            "The host reset or closed before the handshake completed — bypass it, or keep capturing so the next attempt is recorded.",
        ),
    ),
}

/**
 * Maps a FAILED [TlsSessionEntity] into [TlsFailureDetail]. Returns null when the entity is not in
 * the FAILED state (the detail sheet is only opened for failed rows, but this keeps the mapping
 * honest and total).
 *
 * The alert kind is parsed from [TlsSessionEntity.failureReason], which `classifyTlsOutcome` writes
 * as `"alert: <descriptionName>"` for a fatal alert, `"connection reset during handshake"` for an
 * observed RST, or `"closed before handshake completed"` for a premature close.
 */
fun toTlsFailureDetail(entity: TlsSessionEntity): TlsFailureDetail? {
    if (entity.handshakeState != "FAILED") return null
    val reason = entity.failureReason ?: "failed"

    val kind = when {
        reason.contains("unknown_ca", ignoreCase = true) -> TlsFailureKind.UNKNOWN_CA
        reason.contains("handshake_failure", ignoreCase = true) -> TlsFailureKind.HANDSHAKE_FAILURE
        reason.contains("reset", ignoreCase = true) -> TlsFailureKind.RESET
        reason.contains("closed", ignoreCase = true) -> TlsFailureKind.CLOSED
        else -> TlsFailureKind.OTHER
    }

    return TlsFailureDetail(
        sniHost = entity.sniHost,
        connectionId = entity.connectionId,
        headline = "TLS handshake failed",
        explanation = kind.explanation,
        category = kind.category,
        alertSentBy = kind.sentBy,
        failureReason = reason,
        stages = kind.stages(entity),
        offeredVersion = entity.version,
        cipherSuite = entity.cipherSuite,
        alpn = entity.negotiatedProtocol,
        ja3Hash = entity.ja3Hash,
        handshakeDurationMs = entity.handshakeDurationMs,
        failedAt = entity.failedAt,
        remedy = kind.remedy,
    )
}

private enum class TlsFailureKind(
    val category: String,
    val sentBy: String,
    val explanation: String,
    val remedy: TlsRemedyType,
) {
    UNKNOWN_CA(
        category = "Trust rejection",
        sentBy = "Client (the app)",
        explanation = "The app closed the handshake with an unknown_ca alert: it does not trust the CA that signed the certificate requestr presented.",
        remedy = TlsRemedyType.INSTALL_CA,
    ),
    HANDSHAKE_FAILURE(
        category = "Negotiation failure",
        sentBy = "Server (upstream)",
        explanation = "The server closed the handshake with a handshake_failure alert: nothing in the ClientHello requestr replayed was acceptable to it.",
        remedy = TlsRemedyType.EXTEND_CIPHERS,
    ),
    RESET(
        category = "Connection reset",
        sentBy = "Peer",
        explanation = "The connection was reset before the handshake completed — no alert record was observed.",
        remedy = TlsRemedyType.BYPASS_HOST,
    ),
    CLOSED(
        category = "Premature close",
        sentBy = "Peer",
        explanation = "The connection closed before the handshake completed.",
        remedy = TlsRemedyType.BYPASS_HOST,
    ),
    OTHER(
        category = "Handshake failure",
        sentBy = "Peer",
        explanation = "The TLS handshake did not complete.",
        remedy = TlsRemedyType.BYPASS_HOST,
    );

    fun stages(entity: TlsSessionEntity): List<TlsHandshakeStage> = when (this) {
        UNKNOWN_CA -> listOf(
            TlsHandshakeStage("ClientHello sent", sniLine(entity), TlsStageState.DONE),
            TlsHandshakeStage("ServerHello + Certificate", "requestr CA leaf presented", TlsStageState.DONE),
            TlsHandshakeStage("Client alert received", "fatal unknown_ca", TlsStageState.FAILED),
        )
        HANDSHAKE_FAILURE -> listOf(
            TlsHandshakeStage("ClientHello sent", sniLine(entity), TlsStageState.DONE),
            TlsHandshakeStage("ServerHello + Certificate", "no ServerHello observed", TlsStageState.NOT_REACHED),
            TlsHandshakeStage("Server alert received", "fatal handshake_failure", TlsStageState.FAILED),
        )
        RESET -> listOf(
            TlsHandshakeStage("ClientHello sent", sniLine(entity), TlsStageState.DONE),
            TlsHandshakeStage("ServerHello + Certificate", "no ServerHello observed", TlsStageState.NOT_REACHED),
            TlsHandshakeStage("Connection reset", "TCP RST mid-handshake", TlsStageState.FAILED),
        )
        CLOSED -> listOf(
            TlsHandshakeStage("ClientHello sent", sniLine(entity), TlsStageState.DONE),
            TlsHandshakeStage("ServerHello + Certificate", "no ServerHello observed", TlsStageState.NOT_REACHED),
            TlsHandshakeStage("Connection closed", "closed before completion", TlsStageState.FAILED),
        )
        OTHER -> listOf(
            TlsHandshakeStage("ClientHello sent", sniLine(entity), TlsStageState.DONE),
            TlsHandshakeStage("ServerHello + Certificate", "no ServerHello observed", TlsStageState.NOT_REACHED),
            TlsHandshakeStage("Handshake failed", entity.failureReason ?: "failed", TlsStageState.FAILED),
        )
    }

    private fun sniLine(entity: TlsSessionEntity): String =
        "SNI ${entity.sniHost ?: entity.connectionId}${entity.version?.let { " · $it offered" } ?: ""}"
}
