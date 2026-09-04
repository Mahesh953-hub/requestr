package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.search.FtsSchema

/**
 * S3 task 22 -- v21 -> v22, search (plan §4, v22 row).
 *
 * 1. `search_presets` (saved searches; DDL mirrors Room's export for [SearchPresetEntity]).
 * 2. Best-effort `traffic_fts` FTS5 index + sync triggers + eager population via [FtsSchema.ensure].
 *    Room runs migrations on the framework SQLite driver, which may not ship FTS5; in that case the
 *    index is simply created on the next open through the bundled driver
 *    ([FtsSchema.roomCallback]) and search uses `LIKE` until then. The migration itself never
 *    fails because of FTS, so no install is ever stuck on v21 for lack of a search index.
 */
val MIGRATION_21_22: Migration = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS search_presets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, query TEXT NOT NULL, conditions TEXT NOT NULL, " +
                "combineWithOr INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_search_presets_name ON search_presets (name)")
        FtsSchema.ensure(db)
    }
}
