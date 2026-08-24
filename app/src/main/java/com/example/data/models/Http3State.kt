package com.example.data.models

import com.example.data.local.QuicConnectionEntity
import com.example.data.local.QuicDecodeStatus
import com.example.interception.PacketPump

/**
 * Task 14 / Lane D: UI-facing HTTP/3 / QUIC detail state, derived from the authoritative,
 * Room-persisted [QuicConnectionEntity.decodeStatus] plus the strictly-live
 * [PacketPump.QuicLiveSummary] snapshot. Drives
 * [com.example.ui.components.Http3DetailSheet] and the row indicator
 * ([com.example.ui.components.Http3RowIndicator]).
 *
 * Three states match the approved `http3-detail-*` mockups:
 *  - [Decoded] — FULLY_DECODED (and PARTIALLY_DECODED, which still has some decoded content);
 *  - [Undecrypted] — OBSERVED_NO_KEYS (a real H3 connection whose traffic secrets never resolved);
 *  - [QuicOnly] — DETECTED_ONLY (QUIC detected, no ClientHello ALPN recovered).
 *
 * Every field is read off the entity or the live summary; nothing is inferred. The detail sheet
 * cannot show decoded header key/value pairs because the [PacketPump.QuicLiveSummary] exposes only
 * the decoded-exchange *count*, not the exchanges themselves (the decoder is held privately inside
 * PacketPump, which this lane consumes read-only), so the decoded state reports the real count and
 * connection facts rather than fabricating header content.
 */
sealed class Http3DetailState {
    abstract val connectionId: String
    abstract val sniHost: String?
    abstract val quicVersion: Int?
    abstract val alpn: String?
    abstract val fiveTuple: String

    /** Fully (or partially) decoded HTTP/3 — real decoded-exchange count and connection facts. */
    data class Decoded(
        override val connectionId: String,
        override val sniHost: String?,
        override val quicVersion: Int?,
        override val alpn: String?,
        override val fiveTuple: String,
        val cipherSuite: String?,
        val decodedHttp3Exchanges: Int,
        val keyUpdatesPromoted: Int,
        val resolvedKeyLabels: List<String>,
        val partial: Boolean,
    ) : Http3DetailState()

    /** Observed HTTP/3 connection whose traffic secrets never resolved — frames stay opaque. */
    data class Undecrypted(
        override val connectionId: String,
        override val sniHost: String?,
        override val quicVersion: Int?,
        override val alpn: String?,
        override val fiveTuple: String,
        val bytesReceived: Long,
        val missingKeyLabels: List<String>,
        val keyMaterialMissing: Boolean,
        val cipherSuiteUnavailable: Boolean,
    ) : Http3DetailState()

    /** QUIC detected only — no ClientHello ALPN recovered, HTTP/3 not confirmed. */
    data class QuicOnly(
        override val connectionId: String,
        override val sniHost: String?,
        override val quicVersion: Int?,
        override val fiveTuple: String,
        val bytesReceived: Long,
        val retriesAccepted: Int,
        val retriesRejected: Int,
    ) : Http3DetailState() {
        override val alpn: String? = null
    }
}

/**
 * Row-level indicator state for traffic-list rows, derived from [CapturedTrafficItem]'s
 * `protocolHint` + whether a status line was decoded. Kept separate from [Http3DetailState]
 * because the traffic row carries a different (lighter) model than the connections-sheet detail.
 */
enum class Http3RowState { DECODED, OBSERVED, DETECTED }

/**
 * Maps a persisted [QuicConnectionEntity] and its live [PacketPump.QuicLiveSummary] (nullable when
 * the pump is no longer running) into one of the three [Http3DetailState]s. The authoritative
 * branch is [QuicConnectionEntity.decodeStatus]; the live summary only enriches with strictly-live
 * detail (cipher suite, resolved/missing key labels, byte counts, retries).
 */
fun toHttp3DetailState(
    entity: QuicConnectionEntity,
    live: PacketPump.QuicLiveSummary?,
): Http3DetailState {
    val fiveTuple = "${entity.srcIp}:${entity.srcPort} → ${entity.destIp}:${entity.destPort}"
    val version = entity.quicVersion
    return when (entity.decodeStatus) {
        QuicDecodeStatus.FULLY_DECODED,
        QuicDecodeStatus.PARTIALLY_DECODED -> Http3DetailState.Decoded(
            connectionId = entity.connectionId,
            sniHost = entity.sniHost,
            quicVersion = version,
            alpn = live?.alpn,
            fiveTuple = fiveTuple,
            cipherSuite = live?.cipherSuite,
            decodedHttp3Exchanges = live?.decodedHttp3Exchanges ?: 0,
            keyUpdatesPromoted = live?.keyUpdatesPromoted ?: 0,
            resolvedKeyLabels = live?.resolvedKeyLabels.orEmpty(),
            partial = entity.decodeStatus == QuicDecodeStatus.PARTIALLY_DECODED,
        )
        QuicDecodeStatus.OBSERVED_NO_KEYS -> Http3DetailState.Undecrypted(
            connectionId = entity.connectionId,
            sniHost = entity.sniHost,
            quicVersion = version,
            alpn = live?.alpn,
            fiveTuple = fiveTuple,
            bytesReceived = live?.bytesReceived ?: 0,
            missingKeyLabels = live?.missingKeyLabels.orEmpty(),
            keyMaterialMissing = live?.keyMaterialMissing ?: false,
            cipherSuiteUnavailable = live?.cipherSuiteUnavailable ?: false,
        )
        QuicDecodeStatus.DETECTED_ONLY -> Http3DetailState.QuicOnly(
            connectionId = entity.connectionId,
            sniHost = entity.sniHost,
            quicVersion = version,
            fiveTuple = fiveTuple,
            bytesReceived = live?.bytesReceived ?: 0,
            retriesAccepted = live?.retriesAccepted ?: 0,
            retriesRejected = live?.retriesRejected ?: 0,
        )
    }
}
