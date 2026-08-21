package com.example.correlation.graph

import android.content.Context
import com.example.data.local.RequestrDatabase

/**
 * D1: causal chain visualization across multiple connections in a capture session.
 *
 * A [ProvenanceGraph] groups every TCP/QUIC connection observed in a session into [HostGroup]s
 * by the host each connection actually dialed (resolved via [ConnectionLineage.resolveHostFor] --
 * DNS answer if one was captured, else TLS SNI, else the raw destination IP as a last resort so
 * a connection is never silently dropped from the graph). Within a host group, connections are
 * ordered chronologically and each carries its own [LineageChain] from [ConnectionLineage.build],
 * so the caller gets both the "which connections talked to which host" grouping (the graph) and
 * the full DNS->TCP->TLS->HTTP causal detail for each one (the existing D2 chain) in one call.
 *
 * This is a real aggregation over Room-persisted rows -- not a static/demo graph. An app that
 * opened zero connections in a session legitimately produces an empty graph; that is correct
 * behaviour, not a bug.
 *
 * Rendering: [HostGroup]/[LineageChain] is UI-framework agnostic on purpose so it can back either
 * a nested LazyColumn (grouped list, e.g. an extra tab in `ConnectionsBottomSheet`) or a
 * Canvas-based node/edge drawing later, without recomputation.
 */
object ProvenanceGraph {

    suspend fun build(context: Context, sessionId: Long): ProvenanceGraphResult {
        val db = RequestrDatabase.get(context)
        val tcpConnections = db.tcpConnectionDao().getBySession(sessionId)
        val quicConnections = db.quicConnectionDao().getBySession(sessionId)

        val nodes = mutableListOf<ConnectionNode>()
        for (tcp in tcpConnections) {
            val host = ConnectionLineage.resolveHostFor(context, sessionId, tcp.connectionId) ?: tcp.destIp
            nodes += ConnectionNode(
                connectionId = tcp.connectionId,
                host = host,
                transport = "TCP",
                establishedAt = tcp.establishedAt,
                closedAt = tcp.closedAt,
            )
        }
        for (quic in quicConnections) {
            val host = ConnectionLineage.resolveHostFor(context, sessionId, quic.connectionId) ?: quic.destIp
            nodes += ConnectionNode(
                connectionId = quic.connectionId,
                host = host,
                transport = "QUIC",
                establishedAt = quic.establishedAt,
                closedAt = null,
            )
        }

        val groups = nodes
            .groupBy { it.host }
            .map { (host, group) ->
                HostGroup(
                    host = host,
                    connections = group.sortedBy { it.establishedAt },
                )
            }
            .sortedByDescending { group -> group.connections.maxOf { it.establishedAt } }

        return ProvenanceGraphResult(sessionId = sessionId, hostGroups = groups)
    }

    /** Builds the full [LineageChain] for one node -- called lazily (e.g. on tap/expand), not eagerly for every node in [build]. */
    suspend fun lineageFor(context: Context, sessionId: Long, connectionId: String): LineageChain =
        ConnectionLineage.build(context, sessionId, connectionId)
}

/** One TCP or QUIC connection, already resolved to the host it actually dialed. */
data class ConnectionNode(
    val connectionId: String,
    val host: String,
    val transport: String, // "TCP" or "QUIC"
    val establishedAt: Long,
    val closedAt: Long?,
)

/** All connections in a session that dialed the same host, most-recently-active group first. */
data class HostGroup(
    val host: String,
    val connections: List<ConnectionNode>,
)

/** Full multi-connection provenance graph for one capture session. */
data class ProvenanceGraphResult(
    val sessionId: Long,
    val hostGroups: List<HostGroup>,
)
