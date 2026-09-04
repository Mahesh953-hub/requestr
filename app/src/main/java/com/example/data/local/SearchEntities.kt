package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * S3 task 22 (v22): a saved traffic search. [query] is a string in the
 * [com.example.data.search.TrafficSearchCompiler] grammar (or, for the three-condition model,
 * [conditions] holds the individual conditions as a JSON string list and [combineWithOr] their
 * combinator). Lives in Room rather than the frozen `UnifiedDataStore` (plan §5).
 */
@Entity(tableName = "search_presets", indices = [Index(value = ["name"], unique = true)])
data class SearchPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val query: String,
    /** JSON string list of the three-condition model's conditions; empty when [query] is used as-is. */
    val conditions: List<String> = emptyList(),
    val combineWithOr: Boolean = false,
    val createdAt: Long,
)
