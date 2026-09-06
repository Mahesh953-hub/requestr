package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/*
 * S4 task 24 -- DAOs for the v23 typed-rule tables. Every ordered query spells out
 * `ORDER BY sortOrder ASC, id ASC`: the legacy `InterceptRuleDao.getEnabledRules()` had no
 * ORDER BY at all, which is the ambiguity the typed model exists to remove.
 */

@Dao
interface InterceptorRuleDao {

    /** Library order, for the rule-library screen. */
    @Query("SELECT * FROM interceptor_rules ORDER BY sortOrder ASC, id ASC")
    fun observeAllOrdered(): Flow<List<InterceptorRuleEntity>>

    @Query("SELECT * FROM interceptor_rules ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllOrdered(): List<InterceptorRuleEntity>

    @Query("SELECT * FROM interceptor_rules WHERE folderId = :folderId ORDER BY sortOrder ASC, id ASC")
    fun observeByFolder(folderId: Long): Flow<List<InterceptorRuleEntity>>

    @Query("SELECT * FROM interceptor_rules WHERE folderId IS NULL ORDER BY sortOrder ASC, id ASC")
    fun observeUnfiled(): Flow<List<InterceptorRuleEntity>>

    /**
     * The snapshot compiler's input (task 25). Folder enablement is applied by the compiler, not
     * here, so a rule in a disabled folder still appears (and is then dropped) -- the DAO stays a
     * plain projection.
     */
    @Query("SELECT * FROM interceptor_rules WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun getEnabledOrdered(): List<InterceptorRuleEntity>

    @Query("SELECT * FROM interceptor_rules WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    fun observeEnabledOrdered(): Flow<List<InterceptorRuleEntity>>

    @Query("SELECT COUNT(*) FROM interceptor_rules WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Query("SELECT COUNT(*) FROM interceptor_rules")
    suspend fun count(): Int

    @Query("SELECT * FROM interceptor_rules WHERE id = :id")
    suspend fun getById(id: Long): InterceptorRuleEntity?

    @Query("SELECT * FROM interceptor_rules WHERE legacyRuleId = :legacyRuleId")
    suspend fun getByLegacyId(legacyRuleId: Long): InterceptorRuleEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM interceptor_rules")
    suspend fun maxSortOrder(): Int

    /**
     * Typed replacement for the legacy `hasBlockRuleForDomain` existence check that gates the
     * "Block this domain" long-press action. A BLOCK rule converted from `intercept_rules` has the
     * host glob `*<domain>*`, so the check looks for that exact glob in a GATEWAY rule whose config
     * mode is BLOCK. Task 34 repoints the UI callers here.
     */
    @Query(
        "SELECT COUNT(*) > 0 FROM interceptor_rules WHERE enabled = 1 AND stage = 'GATEWAY' " +
            "AND configJson LIKE '%\"mode\":\"BLOCK\"%' AND matcherJson LIKE '%\"*' || :domain || '*\"%'"
    )
    suspend fun hasBlockRuleForDomain(domain: String): Boolean

    @Query(
        "SELECT COUNT(*) > 0 FROM interceptor_rules WHERE enabled = 1 AND stage = 'GATEWAY' " +
            "AND configJson LIKE '%\"mode\":\"BLOCK\"%' AND matcherJson LIKE '%\"*' || :domain || '*\"%'"
    )
    fun observeHasBlockRuleForDomain(domain: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: InterceptorRuleEntity): Long

    /** Import path: inserts in list order, returning the new ids. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rules: List<InterceptorRuleEntity>): List<Long>

    @Update
    suspend fun update(rule: InterceptorRuleEntity)

    @Delete
    suspend fun delete(rule: InterceptorRuleEntity)

    @Query("DELETE FROM interceptor_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM interceptor_rules")
    suspend fun deleteAll()

    @Query("UPDATE interceptor_rules SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun reorder(id: Long, sortOrder: Int, updatedAt: Long)

    /** Drag-reorder: rewrites every rule's sortOrder to its index in [orderedIds] in one transaction. */
    @Transaction
    suspend fun reorderAll(orderedIds: List<Long>, updatedAt: Long = System.currentTimeMillis()) {
        orderedIds.forEachIndexed { index, id -> reorder(id, index, updatedAt) }
    }

    @Query("UPDATE interceptor_rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    /** Kill switch (task 33): disables every rule in the given stages in one statement. */
    @Query("UPDATE interceptor_rules SET enabled = 0, updatedAt = :updatedAt WHERE enabled = 1 AND stage IN (:stages)")
    suspend fun disableStages(stages: List<String>, updatedAt: Long): Int

    @Query("UPDATE interceptor_rules SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?, updatedAt: Long)
}

@Dao
interface RuleFolderDao {

    @Query("SELECT * FROM rule_folders ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<RuleFolderEntity>>

    @Query("SELECT * FROM rule_folders ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<RuleFolderEntity>

    @Query("SELECT * FROM rule_folders WHERE id = :id")
    suspend fun getById(id: Long): RuleFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: RuleFolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(folders: List<RuleFolderEntity>): List<Long>

    @Update
    suspend fun update(folder: RuleFolderEntity)

    @Query("UPDATE rule_folders SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE rule_folders SET collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean)

    /** Deleting a folder un-files its rules rather than deleting them. */
    @Transaction
    suspend fun deleteAndUnfile(id: Long) {
        unfileRules(id)
        deleteById(id)
    }

    @Query("UPDATE interceptor_rules SET folderId = NULL WHERE folderId = :folderId")
    suspend fun unfileRules(folderId: Long)

    @Query("DELETE FROM rule_folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PausedFlowDao {

    @Query("SELECT * FROM breakpoint_paused_flows ORDER BY pausedAt ASC")
    fun observeAll(): Flow<List<PausedFlowEntity>>

    @Query("SELECT * FROM breakpoint_paused_flows ORDER BY pausedAt ASC")
    suspend fun getAll(): List<PausedFlowEntity>

    @Query("SELECT COUNT(*) FROM breakpoint_paused_flows")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM breakpoint_paused_flows WHERE flowId = :flowId")
    suspend fun getByFlowId(flowId: String): PausedFlowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(flow: PausedFlowEntity)

    @Query("UPDATE breakpoint_paused_flows SET state = :state WHERE flowId = :flowId")
    suspend fun setState(flowId: String, state: String)

    @Query("DELETE FROM breakpoint_paused_flows WHERE flowId = :flowId")
    suspend fun deleteByFlowId(flowId: String)

    /** Startup sweep: rows left by a previous process are stale by definition. */
    @Query("DELETE FROM breakpoint_paused_flows")
    suspend fun deleteAll(): Int
}

@Dao
interface ReplayJobDao {

    @Query("SELECT * FROM replay_jobs ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<ReplayJobEntity>>

    @Query("SELECT * FROM replay_jobs WHERE id = :id")
    suspend fun getById(id: Long): ReplayJobEntity?

    @Query("SELECT * FROM replay_jobs WHERE id = :id")
    fun observeById(id: Long): Flow<ReplayJobEntity?>

    @Query("SELECT * FROM replay_jobs WHERE status IN ('PENDING','RUNNING') ORDER BY createdAt ASC, id ASC")
    suspend fun getActive(): List<ReplayJobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: ReplayJobEntity): Long

    @Update
    suspend fun update(job: ReplayJobEntity)

    @Query("UPDATE replay_jobs SET status = :status, completedAt = :completedAt, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, completedAt: Long?, error: String?)

    @Query("UPDATE replay_jobs SET queuedCount = :queued, completedCount = :completed, failedCount = :failed WHERE id = :id")
    suspend fun setCounters(id: Long, queued: Int, completed: Int, failed: Int)

    @Query("DELETE FROM replay_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM replay_jobs")
    suspend fun deleteAll()
}

@Dao
interface ReplayResultDao {

    @Query("SELECT * FROM replay_results WHERE jobId = :jobId ORDER BY startedAt ASC, id ASC")
    fun observeByJob(jobId: Long): Flow<List<ReplayResultEntity>>

    @Query("SELECT * FROM replay_results WHERE jobId = :jobId ORDER BY startedAt ASC, id ASC")
    suspend fun getByJob(jobId: Long): List<ReplayResultEntity>

    @Query("SELECT COUNT(*) FROM replay_results WHERE jobId = :jobId")
    suspend fun countByJob(jobId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(result: ReplayResultEntity): Long

    @Update
    suspend fun update(result: ReplayResultEntity)

    @Query("DELETE FROM replay_results WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}
