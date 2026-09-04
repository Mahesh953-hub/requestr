package com.example.data.search

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.SearchPresetEntity
import kotlinx.coroutines.flow.Flow

/**
 * S3 task 22: executes statements produced by [TrafficSearchCompiler]. `@RawQuery` because the
 * predicate shape is data-dependent and `traffic_fts` is not a Room entity (see [FtsSchema]);
 * arguments are still bound, never inlined. Replaces the `host LIKE '%q%' OR url LIKE '%q%'`
 * query that used to live on `CapturedTrafficDao.search`.
 */
@Dao
interface TrafficFtsDao {
    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<CapturedTrafficEntity>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Long
}

/** Saved searches (task 22 presets). */
@Dao
interface SearchPresetDao {
    @Query("SELECT * FROM search_presets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SearchPresetEntity>>

    @Query("SELECT * FROM search_presets ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<SearchPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: SearchPresetEntity): Long

    @Query("DELETE FROM search_presets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun delete(preset: SearchPresetEntity)
}
