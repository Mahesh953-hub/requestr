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

    // Task 12: the only query ConnectionLineage.httpSteps() is allowed to use to attach an HTTP
    // transaction to a connection. connectionId is an exact 5-tuple-derived key (see
    // CapturedTrafficEntity.connectionId doc) shared with TcpConnectionEntity/TlsSessionEntity,
    // so this is a real join, not a heuristic.
    @Query("SELECT * FROM captured_traffic WHERE connectionId = :connectionId ORDER BY timestamp ASC")
    suspend fun getByConnectionId(connectionId: String): List<CapturedTrafficEntity>

    // NOT for lineage attachment -- do not reintroduce this as a fallback inside
    // ConnectionLineage.httpSteps(). A same-host-in-window match can attach a plausible-but-wrong
    // HTTP row to a connection, fabricating a causal chain the data does not support; task 12
    // resolved this in favour of getByConnectionId's exact join instead. Left in place only for
    // other legitimate callers (e.g. CorrelationEngine's existing cross-plane heuristics) that
    // already accept a windowed match for their own purposes.
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

    // ── Keyset ("cursor") pagination (master-plan §3 "keyset-cursor pagination") ──────────
    //
    // Every read above (`observeAll`, `getAll`, `observeBySession`) is an unbounded
    // `SELECT * ... ORDER BY timestamp DESC` with no LIMIT at all — fine for a Flow the UI
    // collects incrementally, but wrong for a one-shot bulk export (LocalApiServer's old
    // `/api/captures` loaded the *entire* table into memory and only capped the response at
    // `.take(50)` afterwards). These queries bound the query itself and page via a
    // (timestamp, id) cursor rather than SQL `OFFSET`, which would otherwise re-walk and
    // discard every already-returned row on each subsequent page — an O(n²) cost across a
    // multi-thousand-row capture log. `beforeTimestamp`/`beforeId` name the cursor of the last
    // row already returned by the previous page (both null for the first page); rows strictly
    // older than that cursor are returned, newest-first, bounded by `limit`. Ties on `timestamp`
    // are broken by `id` so no row is ever skipped or duplicated across a page boundary.

    /** First/next page of the whole log, newest-first, keyset-paginated. */
    @Query(
        "SELECT * FROM captured_traffic " +
            "WHERE (:beforeTimestamp IS NULL OR timestamp < :beforeTimestamp " +
            "OR (timestamp = :beforeTimestamp AND id < :beforeId)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun getPageBeforeCursor(beforeTimestamp: Long?, beforeId: Long?, limit: Int): List<CapturedTrafficEntity>

    /** Same as [getPageBeforeCursor], scoped to one capture session. */
    @Query(
        "SELECT * FROM captured_traffic WHERE sessionId = :sessionId " +
            "AND (:beforeTimestamp IS NULL OR timestamp < :beforeTimestamp " +
            "OR (timestamp = :beforeTimestamp AND id < :beforeId)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun getSessionPageBeforeCursor(
        sessionId: Long,
        beforeTimestamp: Long?,
        beforeId: Long?,
        limit: Int,
    ): List<CapturedTrafficEntity>

    /**
     * Bulk, time-range-filtered export query (master plan §2.4 — "bulk, time-range-filtered
     * network-log export endpoint"). Every row with `timestamp` in `[fromTs, toTs]`, newest
     * first, keyset-paginated exactly like [getPageBeforeCursor] so a caller (e.g.
     * [com.example.api.LocalApiServer]'s `/api/captures/export`) can walk an arbitrarily large
     * range in bounded pages instead of the old unbounded-load-then-`.take(50)` approach.
     */
    @Query(
        "SELECT * FROM captured_traffic WHERE timestamp BETWEEN :fromTs AND :toTs " +
            "AND (:beforeTimestamp IS NULL OR timestamp < :beforeTimestamp " +
            "OR (timestamp = :beforeTimestamp AND id < :beforeId)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun getRangePageBeforeCursor(
        fromTs: Long,
        toTs: Long,
        beforeTimestamp: Long?,
        beforeId: Long?,
        limit: Int,
    ): List<CapturedTrafficEntity>

    /** Total row count in `[fromTs, toTs]`, so an export caller can report how much remains. */
    @Query("SELECT COUNT(*) FROM captured_traffic WHERE timestamp BETWEEN :fromTs AND :toTs")
    suspend fun countInRange(fromTs: Long, toTs: Long): Int

    /**
     * Session-scoped + time-range-filtered export query, keyset-paginated exactly like
     * [getRangePageBeforeCursor]. `LocalApiServer.exportCaptures` used to combine
     * [getSessionPageBeforeCursor] with an in-memory `.filter { it.timestamp in fromTs..toTs }`
     * on the caller side, which is wrong on two counts: (1) `nextCursor` was computed from the
     * *filtered* list's size/last row rather than the raw page's, so a page containing mostly
     * out-of-range rows for that session under-reported how many rows were fetched and could
     * point the next request's cursor at a row that skips back over already-scanned-but-discarded
     * rows on every subsequent page -- if a session has a run of more than `limit` consecutive
     * out-of-range rows, the export never makes forward progress past that run; (2) it can never
     * return more matching rows in a page than the filtered subset of one `limit`-sized raw scan,
     * so a page can come back short even when far more matching rows exist further back. Doing
     * the range filter in SQL alongside the session filter fixes both: every returned row is
     * already in range, the raw `LIMIT` and the returned count agree, and the cursor always names
     * the actual last row scanned.
     */
    @Query(
        "SELECT * FROM captured_traffic WHERE sessionId = :sessionId " +
            "AND timestamp BETWEEN :fromTs AND :toTs " +
            "AND (:beforeTimestamp IS NULL OR timestamp < :beforeTimestamp " +
            "OR (timestamp = :beforeTimestamp AND id < :beforeId)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun getSessionRangePageBeforeCursor(
        sessionId: Long,
        fromTs: Long,
        toTs: Long,
        beforeTimestamp: Long?,
        beforeId: Long?,
        limit: Int,
    ): List<CapturedTrafficEntity>

    /** Total row count in `[fromTs, toTs]` for one session, mirroring [countInRange]. */
    @Query("SELECT COUNT(*) FROM captured_traffic WHERE sessionId = :sessionId AND timestamp BETWEEN :fromTs AND :toTs")
    suspend fun countInSessionRange(sessionId: Long, fromTs: Long, toTs: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: CapturedTrafficEntity): Long

    /**
     * Updates the parts of a live HTTP/3 exchange that keep arriving after its row was first written.
     *
     * A decoded HTTP/3 request is a real transaction the moment its HEADERS field section decodes, but
     * its `:status`, response headers, and DATA-frame bodies arrive later, across further QUIC packets
     * on the same stream. [com.example.interception.PacketPump] therefore inserts the row on first
     * decode and calls this to keep it truthful, rather than either withholding the row until FIN
     * (which never arrives for an aborted stream) or inserting a second, duplicate row per update.
     *
     * **Idempotent and monotonic, so a re-write can never shrink what was recorded.** A body is replaced
     * only when the incoming observed size is at least the stored size, and sizes only ever grow, so the
     * body and the size it belongs to stay a consistent pair. That matters because an exchange can be
     * dropped by the decoder's in-flight bound and then recreated when its stream speaks again: the
     * recreated instance holds fewer buffered body bytes than the row already contains, and without this
     * guard the row's body would be replaced by the shorter one while its size stayed large. Re-applying
     * the same write repeatedly is a no-op.
     *
     * The 6 binary-body columns ([isRequestBodyBinary]/[isResponseBodyBinary]/[requestBodyBytes]/
     * [responseBodyBytes]/[requestBodyBytesTruncated]/[responseBodyBytesTruncated], item 4 / gap B
     * follow-up) are gated by the exact same `:requestSizeBytes >= requestSizeBytes` /
     * `:responseSizeBytes >= responseSizeBytes` monotonic guard as [requestBody]/[responseBody] --
     * they describe the same body those columns describe, so they must advance/hold in lockstep with
     * it, never independently.
     *
     * **Every column here only ever moves from less-known to more-known.** A response can decode before
     * its request HEADERS do (a capture that started mid-stream, or reordering), in which case the row
     * is first written with empty request metadata; this fills `method`/`url`/`host`/`path`/
     * `requestHeaders` once they genuinely decode instead of leaving them permanently blank. Nothing is
     * overwritten with a blank or null: request metadata is only applied when non-empty, header maps
     * only when the exchange really has them, sizes take the maximum, and `statusCode`/bodies use
     * `COALESCE` so a later packet that carries no body cannot erase one already recorded.
     */
    @Query(
        "UPDATE captured_traffic SET " +
            "method = CASE WHEN :method != '' THEN :method ELSE method END, " +
            "url = CASE WHEN :method != '' OR url = '' THEN :url ELSE url END, " +
            "host = CASE WHEN :host != '' THEN :host ELSE host END, " +
            "path = CASE WHEN :path != '' THEN :path ELSE path END, " +
            "requestHeaders = CASE WHEN :hasRequestHeaders THEN :requestHeaders ELSE requestHeaders END, " +
            "statusCode = COALESCE(:statusCode, statusCode), " +
            "responseHeaders = CASE WHEN :hasResponseHeaders THEN :responseHeaders ELSE responseHeaders END, " +
            "requestBody = CASE WHEN :requestBody IS NOT NULL AND :requestSizeBytes >= requestSizeBytes " +
            "  THEN :requestBody ELSE requestBody END, " +
            "responseBody = CASE WHEN :responseBody IS NOT NULL AND :responseSizeBytes >= responseSizeBytes " +
            "  THEN :responseBody ELSE responseBody END, " +
            "isRequestBodyBinary = CASE WHEN :requestSizeBytes >= requestSizeBytes " +
            "  THEN :isRequestBodyBinary ELSE isRequestBodyBinary END, " +
            "isResponseBodyBinary = CASE WHEN :responseSizeBytes >= responseSizeBytes " +
            "  THEN :isResponseBodyBinary ELSE isResponseBodyBinary END, " +
            "requestBodyBytes = CASE WHEN :requestSizeBytes >= requestSizeBytes " +
            "  THEN :requestBodyBytes ELSE requestBodyBytes END, " +
            "responseBodyBytes = CASE WHEN :responseSizeBytes >= responseSizeBytes " +
            "  THEN :responseBodyBytes ELSE responseBodyBytes END, " +
            "requestBodyBytesTruncated = CASE WHEN :requestSizeBytes >= requestSizeBytes " +
            "  THEN :requestBodyBytesTruncated ELSE requestBodyBytesTruncated END, " +
            "responseBodyBytesTruncated = CASE WHEN :responseSizeBytes >= responseSizeBytes " +
            "  THEN :responseBodyBytesTruncated ELSE responseBodyBytesTruncated END, " +
            "requestSizeBytes = MAX(requestSizeBytes, :requestSizeBytes), " +
            "responseSizeBytes = MAX(responseSizeBytes, :responseSizeBytes), " +
            "latencyMs = CASE WHEN :latencyMs > 0 THEN :latencyMs ELSE latencyMs END " +
            "WHERE id = :id"
    )
    suspend fun updateDecodedExchange(
        id: Long,
        method: String,
        url: String,
        host: String,
        path: String,
        hasRequestHeaders: Boolean,
        requestHeaders: Map<String, String>,
        statusCode: Int?,
        hasResponseHeaders: Boolean,
        responseHeaders: Map<String, String>,
        requestBody: String?,
        responseBody: String?,
        requestSizeBytes: Long,
        responseSizeBytes: Long,
        latencyMs: Long,
        isRequestBodyBinary: Boolean = false,
        isResponseBodyBinary: Boolean = false,
        requestBodyBytes: ByteArray? = null,
        responseBodyBytes: ByteArray? = null,
        requestBodyBytesTruncated: Boolean = false,
        responseBodyBytesTruncated: Boolean = false,
    )

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

    /** Single-row lookup backing the LAN dashboard's/local API's rules CRUD (PUT/DELETE by id). */
    @Query("SELECT * FROM intercept_rules WHERE id = :id")
    suspend fun getById(id: Long): InterceptRuleEntity?

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

    /**
     * Every node in every flow, not just one -- used by [com.example.data.export.WorkspaceExporter],
     * which must export a user's whole multi-flow workspace, not only the "default" flow that
     * [getAll]'s default argument scopes to.
     */
    @Query("SELECT * FROM flow_nodes ORDER BY flowId ASC, createdAt ASC")
    suspend fun getAllAcrossFlows(): List<FlowNodeEntity>

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

    /** Every connection in every flow -- same rationale as [FlowNodeDao.getAllAcrossFlows]. */
    @Query("SELECT * FROM flow_connections ORDER BY flowId ASC, createdAt ASC")
    suspend fun getAllAcrossFlows(): List<FlowConnectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(connection: FlowConnectionEntity)

    @Query("DELETE FROM flow_connections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM flow_connections WHERE fromNodeId = :nodeId OR toNodeId = :nodeId")
    suspend fun deleteByNodeId(nodeId: String)

    @Query("DELETE FROM flow_connections WHERE flowId = :flowId")
    suspend fun clearFlow(flowId: String = "default")
}

@Dao
interface FlowPipelineDao {
    @Query("SELECT * FROM flow_pipelines ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<FlowPipelineEntity>>

    @Query("SELECT * FROM flow_pipelines ORDER BY createdAt ASC")
    suspend fun getAll(): List<FlowPipelineEntity>

    @Query("SELECT * FROM flow_pipelines WHERE id = :id")
    suspend fun getById(id: String): FlowPipelineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pipeline: FlowPipelineEntity)

    @Query("UPDATE flow_pipelines SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM flow_pipelines WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM flow_nodes WHERE flowId = :flowId")
    suspend fun nodeCountForFlow(flowId: String): Int
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

    /**
     * Inserts a connection's identity row only if it does not already exist.
     *
     * The REPLACE-based [upsert] must never be used to re-announce a connection: replacing the row
     * discards every column the caller does not currently hold. After the in-memory decode state for an
     * idle connection is released, a later packet on that same connection is "first observation" as far
     * as memory is concerned, while Room still holds the real SNI, ALPN, client random, resolved key
     * labels and decode status. IGNORE-on-conflict keeps that record intact and lets
     * [updateDecodeState] move it forward monotonically instead.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: QuicConnectionEntity): Long

    /**
     * Re-attributes an existing connection row to the **current** capture session, resetting its decode
     * state to what the live decoder currently knows.
     *
     * `connectionId` is a 5-tuple-derived key, and a 5-tuple is genuinely reused by a different QUIC
     * connection in a later capture session. Because [insertIfAbsent] deliberately never replaces a row,
     * such a reuse would otherwise leave the row owned by the finished session -- invisible to the live
     * one, and still advertising the previous connection's SNI, identity and decode status. The
     * `sessionId != :sessionId` guard is what keeps this from firing inside a single session, where the
     * monotonic [updateDecodeState] must remain the only writer.
     *
     * Returns the number of rows re-attributed (0 when the row already belongs to this session).
     */
    @Query(
        "UPDATE quic_connections SET sessionId = :sessionId, srcIp = :srcIp, destIp = :destIp, " +
            "srcPort = :srcPort, destPort = :destPort, sniHost = :sniHost, alpn = :alpn, " +
            "quicVersion = :quicVersion, establishedAt = :establishedAt, dstConnectionId = :connectionId, " +
            "decodeStatus = :decodeStatus, lifecycleState = :lifecycleState, " +
            "clientRandomHex = :clientRandomHex, keylogLabels = :keylogLabels, updatedAt = :updatedAt " +
            "WHERE connectionId = :connectionId AND sessionId != :sessionId"
    )
    suspend fun claimForSession(
        connectionId: String,
        sessionId: Long,
        srcIp: String,
        destIp: String,
        srcPort: Int,
        destPort: Int,
        sniHost: String?,
        alpn: String?,
        quicVersion: Int?,
        establishedAt: Long,
        decodeStatus: QuicDecodeStatus,
        lifecycleState: String,
        clientRandomHex: String?,
        keylogLabels: String?,
        updatedAt: Long,
    ): Int

    /**
     * Drives the authoritative decode state of a QUIC connection row from the live decoder
     * ([com.example.interception.quic.QuicConnectionDecoder]): the [QuicDecodeStatus] high-water mark,
     * the transport lifecycle state, and the decode provenance -- [clientRandomHex] (the TLS key-log
     * identity that made decryption possible at all) and [keylogLabels] (exactly which traffic secrets
     * really resolved, or NULL when none did).
     *
     * **Monotonic and resume-safe in SQL, not just in memory.** `decodeStatus` and `lifecycleState`
     * only move forward: the statement compares the caller's rank against the stored row's rank, so a
     * freshly-created decoder that has so far only seen one undecryptable packet cannot pull a stored
     * `FULLY_DECODED`/`CLOSED` backwards after the in-memory state for an idle connection was released.
     * Provenance and identity are fill-forward only -- `clientRandomHex`/`keylogLabels` keep their
     * stored value when the caller has none, `sniHost`/`alpn` are only filled when still NULL (an
     * Initial recovered them once and cannot recover them again), and `quicVersion` is corrected only
     * when the stored value is absent or `0`, which is what a connection first observed through a
     * short header records before a long header reveals the real version.
     *
     * A targeted UPDATE rather than a whole-row upsert on purpose: the identity columns written when the
     * connection was first observed must not be rewritten -- and cannot be re-derived -- on every
     * status transition.
     *
     * [decodeStatusRank] and [lifecycleRank] are supplied by the caller because SQLite cannot rank a
     * Kotlin enum; they must match the CASE ladders above (see
     * [com.example.interception.quic.QuicConnectionDecoder.decodeStatusRank]).
     */
    @Query(
        "UPDATE quic_connections SET " +
            "decodeStatus = CASE WHEN :decodeStatusRank > (CASE decodeStatus " +
            "    WHEN 'FULLY_DECODED' THEN 3 WHEN 'PARTIALLY_DECODED' THEN 2 " +
            "    WHEN 'OBSERVED_NO_KEYS' THEN 1 ELSE 0 END) " +
            "  THEN :decodeStatus ELSE decodeStatus END, " +
            "lifecycleState = CASE WHEN :lifecycleRank > (CASE lifecycleState " +
            "    WHEN 'CLOSED' THEN 4 WHEN 'DRAINING' THEN 3 WHEN 'HANDSHAKE_COMPLETE' THEN 2 " +
            "    WHEN 'HANDSHAKE_IN_PROGRESS' THEN 1 ELSE 0 END) " +
            "  THEN :lifecycleState ELSE lifecycleState END, " +
            "clientRandomHex = COALESCE(:clientRandomHex, clientRandomHex), " +
            "keylogLabels = COALESCE(:keylogLabels, keylogLabels), " +
            "sniHost = COALESCE(sniHost, :sniHost), " +
            "alpn = COALESCE(alpn, :alpn), " +
            "quicVersion = CASE WHEN COALESCE(quicVersion, 0) = 0 THEN :quicVersion ELSE quicVersion END, " +
            "updatedAt = :updatedAt " +
            "WHERE connectionId = :connectionId"
    )
    suspend fun updateDecodeState(
        connectionId: String,
        decodeStatus: QuicDecodeStatus,
        decodeStatusRank: Int,
        lifecycleState: String,
        lifecycleRank: Int,
        clientRandomHex: String?,
        keylogLabels: String?,
        sniHost: String?,
        alpn: String?,
        quicVersion: Int?,
        updatedAt: Long,
    )

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

// ── v9 app-scope DAOs (task 10, docs/plans/plan-app.md H1) ────────────
// Shaped like SavedApiRequestDao: observeAll()/getAll()/upsert()/deleteById() per table.

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAll(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface NoteFolderDao {

    @Query("SELECT * FROM note_folders")
    fun observeAll(): Flow<List<NoteFolderEntity>>

    @Query("SELECT * FROM note_folders")
    suspend fun getAll(): List<NoteFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: NoteFolderEntity)

    @Query("DELETE FROM note_folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CustomToolDao {

    @Query("SELECT * FROM custom_tools ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CustomToolEntity>>

    @Query("SELECT * FROM custom_tools ORDER BY createdAt DESC")
    suspend fun getAll(): List<CustomToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tool: CustomToolEntity)

    @Query("DELETE FROM custom_tools WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatMessageDao {

    // Kept for backward compatibility (ChatRepositoryRoomTest + pre-multi-conversation callers
    // operate across every conversation at once); conversation-scoped queries below are additive.
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeByConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getByConversation(conversationId: String): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface ChatConversationDao {

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatConversationEntity>>

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ChatConversationEntity>

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun getById(id: String): ChatConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ChatConversationEntity)

    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatProviderDao {

    @Query("SELECT * FROM chat_providers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ChatProviderEntity>>

    @Query("SELECT * FROM chat_providers ORDER BY createdAt DESC")
    suspend fun getAll(): List<ChatProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ChatProviderEntity)

    @Query("DELETE FROM chat_providers WHERE id = :id")
    suspend fun deleteById(id: String)
}

/**
 * Backs [com.example.data.repository.RecentActivityRepository] (v18). Every write is scoped by
 * the composite (itemType, itemId) key, so a repeated open of the same item is a single-row
 * update; [pruneOlderThanNewest] is what keeps the table bounded.
 */
@Dao
interface RecentActivityDao {

    @Query("SELECT * FROM recent_activity ORDER BY openedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentActivityEntity>>

    @Query("SELECT * FROM recent_activity ORDER BY openedAt DESC")
    suspend fun getAll(): List<RecentActivityEntity>

    @Query("SELECT COUNT(*) FROM recent_activity")
    suspend fun count(): Int

    /** REPLACE on the composite key = "re-opening updates the existing row", never a duplicate. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentActivityEntity)

    /**
     * Refreshes the rendered snapshot of an item that is already in the history (e.g. a note was
     * re-saved under a new title) WITHOUT touching [RecentActivityEntity.openedAt] -- editing an
     * item is not the same as opening it. No-op when the item was never opened.
     */
    @Query(
        "UPDATE recent_activity SET subtype = :subtype, title = :title, preview = :preview " +
            "WHERE itemType = :itemType AND itemId = :itemId"
    )
    suspend fun updateSnapshot(itemType: String, itemId: String, subtype: String, title: String, preview: String)

    /**
     * Deletes every row except the [keep] most recently opened. Keyed on SQLite's implicit
     * `rowid` (this is an ordinary rowid table, not WITHOUT ROWID) so the delete is exact even
     * when two rows share the same [RecentActivityEntity.openedAt] millisecond.
     */
    @Query(
        "DELETE FROM recent_activity WHERE rowid NOT IN " +
            "(SELECT rowid FROM recent_activity ORDER BY openedAt DESC LIMIT :keep)"
    )
    suspend fun pruneOlderThanNewest(keep: Int)

    @Query("DELETE FROM recent_activity WHERE itemType = :itemType AND itemId = :itemId")
    suspend fun delete(itemType: String, itemId: String)

    @Query("DELETE FROM recent_activity WHERE itemType = :itemType")
    suspend fun deleteAllOfType(itemType: String)

    @Query("DELETE FROM recent_activity")
    suspend fun clear()
}

// ═══════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════════
// CONNECT Control subsystem
// ═══════════════════════════════════════════════════════════════════

@Dao
interface ConnectAttemptDao {
    @Query("SELECT * FROM connect_attempts WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun observeBySession(sessionId: Long): Flow<List<ConnectAttemptEntity>>

    @Query("SELECT * FROM connect_attempts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ConnectAttemptEntity>>

    @Query("SELECT * FROM connect_attempts WHERE id = :id")
    suspend fun getById(id: Long): ConnectAttemptEntity?

    @Query("SELECT * FROM connect_attempts WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getBySession(sessionId: Long): List<ConnectAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ConnectAttemptEntity): Long

    @Update
    suspend fun update(item: ConnectAttemptEntity)

    @Query("SELECT COUNT(*) FROM connect_attempts WHERE sessionId = :sessionId")
    fun observeCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM connect_attempts WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: Long)
}
