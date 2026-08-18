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

    @Query("SELECT * FROM captured_traffic WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun observeBySession(sessionId: Long): Flow<List<CapturedTrafficEntity>>

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
