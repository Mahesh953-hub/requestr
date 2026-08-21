package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Live traffic log. Every read is an observable [Flow] so the Intercept screen reflects exactly
 * what the engine has written — replacing the previous `Random`-driven fake stream.
 */
@Dao
interface CapturedTrafficDao {

    @Query("SELECT * FROM captured_traffic ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CapturedTrafficEntity>>

    @Query("SELECT * FROM captured_traffic ORDER BY timestamp DESC")
    suspend fun getAll(): List<CapturedTrafficEntity>

    @Query("SELECT * FROM captured_traffic WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun observeBySession(sessionId: Long): Flow<List<CapturedTrafficEntity>>

    // D3: one-shot read used by VisibilityAnalyzer to compute decrypted/undecoded fractions.
    @Query("SELECT * FROM captured_traffic WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getBySession(sessionId: Long): List<CapturedTrafficEntity>

    // D2: one-shot read used by ConnectionLineage to find the HTTP transaction(s) that belong to
    // a given TCP/TLS connection. CapturedTrafficEntity has no connectionId column (HTTP is
    // parsed out of a TCP stream after the fact, and MITM'd HTTP never sees the raw 5-tuple), so
    // the join has to be by host + a timestamp window instead -- same heuristic CorrelationEngine
    // already uses for cross-plane matching, not a new invented convention.
    @Query(
        "SELECT * FROM captured_traffic WHERE sessionId = :sessionId AND host = :host " +
            "AND timestamp BETWEEN :fromTs AND :toTs ORDER BY timestamp ASC"
    )
    suspend fun getByHostInWindow(sessionId: Long, host: String, fromTs: Long, toTs: Long): List<CapturedTrafficEntity>

    @Query("SELECT * FROM captured_traffic WHERE host LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun search(query: String): Flow<List<CapturedTrafficEntity>>

    @Query("SELECT * FROM captured_traffic WHERE id = :id")
    suspend fun getById(id: Long): CapturedTrafficEntity?

    @Query("SELECT COUNT(*) FROM captured_traffic")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: CapturedTrafficEntity): Long

    @Delete
    suspend fun delete(item: CapturedTrafficEntity)

    @Query("DELETE FROM captured_traffic WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM captured_traffic")
    suspend fun clearAll()
}

@Dao
interface CaptureSessionDao {

    @Query("SELECT * FROM capture_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CaptureSessionEntity>>

    /** Most recent session, used to render real "last session" stats rather than a fake count. */
    @Query("SELECT * FROM capture_sessions ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<CaptureSessionEntity?>

    @Query("SELECT * FROM capture_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSession(): CaptureSessionEntity?

    @Query("SELECT * FROM capture_sessions WHERE id = :id")
    suspend fun getById(id: Long): CaptureSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: CaptureSessionEntity): Long

    @Update
    suspend fun update(session: CaptureSessionEntity)

    @Query("UPDATE capture_sessions SET packetCount = :packets, bytesTransferred = :bytes, transactionCount = :transactions WHERE id = :id")
    suspend fun updateCounters(id: Long, packets: Int, bytes: Long, transactions: Int)

    @Query("UPDATE capture_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun markEnded(id: Long, endedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM capture_sessions")
    suspend fun clearAll()
}

@Dao
interface InterceptRuleDao {

    @Query("SELECT * FROM intercept_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<InterceptRuleEntity>>

    @Query("SELECT * FROM intercept_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<InterceptRuleEntity>

    /**
     * Real existence check backing the "Block this domain" long-press action.
     * The UI must call this to decide whether the action is genuinely disabled
     * (APP_v2.md Bug 3.8) instead of just dimming it visually.
     */
    @Query("SELECT COUNT(*) > 0 FROM intercept_rules WHERE domain = :domain AND action = 'BLOCK' AND enabled = 1")
    suspend fun hasBlockRuleForDomain(domain: String): Boolean

    @Query("SELECT COUNT(*) > 0 FROM intercept_rules WHERE domain = :domain AND action = 'BLOCK' AND enabled = 1")
    fun observeHasBlockRuleForDomain(domain: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: InterceptRuleEntity): Long

    @Update
    suspend fun update(rule: InterceptRuleEntity)

    @Delete
    suspend fun delete(rule: InterceptRuleEntity)

    @Query("DELETE FROM intercept_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/**
 * Real persistence for API Testr's Save/Star actions (v3.1 fix A4). Previously these lived only
 * in an in-memory `MutableStateFlow`, so a saved/favorited request vanished on process death.
 */
@Dao
interface SavedApiRequestDao {

    @Query("SELECT * FROM saved_api_requests ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SavedApiRequestEntity>>

    @Query("SELECT * FROM saved_api_requests ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SavedApiRequestEntity>

    @Query("SELECT * FROM saved_api_requests WHERE id = :id")
    suspend fun getById(id: String): SavedApiRequestEntity?

    @Query("SELECT COUNT(*) > 0 FROM saved_api_requests WHERE id = :id")
    suspend fun exists(id: String): Boolean

    /** Upsert: a save either creates a new row or overwrites the existing one for that id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: SavedApiRequestEntity)

    @Query("UPDATE saved_api_requests SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM saved_api_requests WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM saved_api_requests")
    suspend fun clearAll()
}

// ── Flow DAOs (Fix 8 / AUDIT_REPORT §5.2, GAP_ANALYSIS Tier 3 #15) ─────

@Dao
interface FlowNodeDao {
    @Query("SELECT * FROM flow_nodes WHERE flowId = :flowId ORDER BY createdAt ASC")
    fun observeAll(flowId: String = "default"): Flow<List<FlowNodeEntity>>

    @Query("SELECT * FROM flow_nodes WHERE flowId = :flowId")
    suspend fun getAll(flowId: String = "default"): List<FlowNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: FlowNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(nodes: List<FlowNodeEntity>)

    @Query("UPDATE flow_nodes SET positionX = :x, positionY = :y WHERE id = :id")
    suspend fun updatePosition(id: String, x: Float, y: Float)

    @Query("DELETE FROM flow_nodes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM flow_nodes WHERE flowId = :flowId")
    suspend fun clearFlow(flowId: String = "default")
}

@Dao
interface FlowConnectionDao {
    @Query("SELECT * FROM flow_connections WHERE flowId = :flowId ORDER BY createdAt ASC")
    fun observeAll(flowId: String = "default"): Flow<List<FlowConnectionEntity>>

    @Query("SELECT * FROM flow_connections WHERE flowId = :flowId")
    suspend fun getAll(flowId: String = "default"): List<FlowConnectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(connection: FlowConnectionEntity)

    @Query("DELETE FROM flow_connections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM flow_connections WHERE fromNodeId = :nodeId OR toNodeId = :nodeId")
    suspend fun deleteByNodeId(nodeId: String)

    @Query("DELETE FROM flow_connections WHERE flowId = :flowId")
    suspend fun clearFlow(flowId: String = "default")
}

// ═══════════════════════════════════════════════════════════════════
// v5 Connection model DAOs (M1) — TCP/UDP/QUIC connection tracking
// ═══════════════════════════════════════════════════════════════════

@Dao
interface TcpConnectionDao {
    @Query("SELECT * FROM tcp_connections WHERE sessionId = :sessionId ORDER BY establishedAt DESC")
    fun observeBySession(sessionId: Long): Flow<List<TcpConnectionEntity>>

    @Query("SELECT * FROM tcp_connections ORDER BY establishedAt DESC")
    fun observeAll(): Flow<List<TcpConnectionEntity>>

    // D1: one-shot read used by ProvenanceGraph to build the graph over every connection in a
    // session without holding a Flow subscription open.
    @Query("SELECT * FROM tcp_connections WHERE sessionId = :sessionId ORDER BY establishedAt DESC")
    suspend fun getBySession(sessionId: Long): List<TcpConnectionEntity>

    @Query("SELECT * FROM tcp_connections WHERE connectionId = :connectionId")
    suspend fun getById(connectionId: String): TcpConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TcpConnectionEntity)

    @Query("SELECT COUNT(*) FROM tcp_connections WHERE sessionId = :sessionId")
    fun observeCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM tcp_connections WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)
}

@Dao
interface UdpFlowDao {
    @Query("SELECT * FROM udp_flows WHERE sessionId = :sessionId ORDER BY startedAt DESC")
    fun observeBySession(sessionId: Long): Flow<List<UdpFlowEntity>>

    @Query("SELECT * FROM udp_flows ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<UdpFlowEntity>>

    // D3: one-shot read used by VisibilityAnalyzer.
    @Query("SELECT * FROM udp_flows WHERE sessionId = :sessionId ORDER BY startedAt DESC")
    suspend fun getBySession(sessionId: Long): List<UdpFlowEntity>

    @Query("SELECT * FROM udp_flows WHERE flowId = :flowId")
    suspend fun getById(flowId: String): UdpFlowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: UdpFlowEntity)

    @Query("SELECT COUNT(*) FROM udp_flows WHERE sessionId = :sessionId")
    fun observeCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM udp_flows WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)
}

@Dao
interface QuicConnectionDao {
    @Query("SELECT * FROM quic_connections WHERE sessionId = :sessionId ORDER BY establishedAt DESC")
    fun observeBySession(sessionId: Long): Flow<List<QuicConnectionEntity>>

    @Query("SELECT * FROM quic_connections ORDER BY establishedAt DESC")
    fun observeAll(): Flow<List<QuicConnectionEntity>>

    // D1: one-shot read, same rationale as TcpConnectionDao.getBySession above.
    @Query("SELECT * FROM quic_connections WHERE sessionId = :sessionId ORDER BY establishedAt DESC")
    suspend fun getBySession(sessionId: Long): List<QuicConnectionEntity>

    @Query("SELECT * FROM quic_connections WHERE connectionId = :connectionId")
    suspend fun getById(connectionId: String): QuicConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: QuicConnectionEntity)

    @Query("SELECT COUNT(*) FROM quic_connections WHERE sessionId = :sessionId")
    fun observeCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM quic_connections WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)
}

// ═══════════════════════════════════════════════════════════════════
// v5.6 TLS session DAO (M2) — TLS handshake/cipher/cert metadata
// ═══════════════════════════════════════════════════════════════════

@Dao
interface TlsSessionDao {
    @Query("SELECT * FROM tls_sessions WHERE sessionId = :sessionId ORDER BY establishedAt DESC")
    fun observeBySession(sessionId: Long): Flow<List<TlsSessionEntity>>

    @Query("SELECT * FROM tls_sessions ORDER BY establishedAt DESC")
    fun observeAll(): Flow<List<TlsSessionEntity>>

    @Query("SELECT * FROM tls_sessions WHERE tlsSessionId = :tlsSessionId")
    suspend fun getById(tlsSessionId: String): TlsSessionEntity?

    // D3: one-shot read used by VisibilityAnalyzer to compute decrypted/SNI-only fractions.
    @Query("SELECT * FROM tls_sessions WHERE sessionId = :sessionId ORDER BY establishedAt DESC")
    suspend fun getBySession(sessionId: Long): List<TlsSessionEntity>

    // D2: MitmProxyEngine keys tlsSessionId as "$host:$port" (the CONNECT target), which is a
    // different convention from the undecoded path's tlsSessionId == TcpStream.key. Given only a
    // TCP connection's destIp/destPort we can't always reconstruct the MITM key, so
    // ConnectionLineage also looks TLS sessions up by SNI host within the same capture session.
    @Query("SELECT * FROM tls_sessions WHERE sessionId = :sessionId AND sniHost = :sniHost ORDER BY establishedAt DESC LIMIT 1")
    suspend fun getLatestBySniHost(sessionId: Long, sniHost: String): TlsSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TlsSessionEntity)

    @Query("SELECT COUNT(*) FROM tls_sessions WHERE sessionId = :sessionId")
    fun observeCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM tls_sessions WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)
}

// ═══════════════════════════════════════════════════════════════════
// v5.7 DNS query DAO (D2 prerequisite) — real persistence for DnsQueryEntity
// ═══════════════════════════════════════════════════════════════════

@Dao
interface DnsQueryDao {
    @Query("SELECT * FROM dns_queries WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun observeBySession(sessionId: Long): Flow<List<DnsQueryEntity>>

    @Query("SELECT * FROM dns_queries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DnsQueryEntity>>

    @Query("SELECT * FROM dns_queries WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getBySession(sessionId: Long): List<DnsQueryEntity>

    /** Most recent response (has resolvedIps) for [hostname] at or before [beforeTs] — used by
     * [com.example.correlation.graph.ConnectionLineage] to find the DNS answer that most likely
     * caused a later TCP connection to a resolved IP. */
    @Query(
        "SELECT * FROM dns_queries WHERE hostname = :hostname AND isResponse = 1 " +
            "AND timestamp <= :beforeTs ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getLatestResponseFor(hostname: String, beforeTs: Long): DnsQueryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DnsQueryEntity): Long

    @Query("SELECT COUNT(*) FROM dns_queries WHERE sessionId = :sessionId")
    fun observeCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM dns_queries WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)
}
