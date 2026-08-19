package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Real on-device persistence for interception.
 *
 * Ships with NO seed data by design: a fresh install has an empty traffic log, so the UI
 * renders a genuine empty state instead of the fabricated sample rows that shipped in v2.1.
 */
@Database(
    entities = [
        CapturedTrafficEntity::class,
        CaptureSessionEntity::class,
        InterceptRuleEntity::class,
        SavedApiRequestEntity::class,
    ],
    // v3.2 Part C — C2: bumped 2 -> 3 for SavedApiRequestEntity.chainExtractions.
    // v3.4 Part C — C3: bumped 3 -> 4 for SavedApiRequestEntity.assertions. Schema is young;
    // destructive migration (below) is acceptable pre-1.0 and avoids shipping a broken upgrade
    // path for a single additive column.
    version = 4,
    exportSchema = false,
)
@TypeConverters(RequestrTypeConverters::class)
abstract class RequestrDatabase : RoomDatabase() {

    abstract fun capturedTrafficDao(): CapturedTrafficDao
    abstract fun captureSessionDao(): CaptureSessionDao
    abstract fun interceptRuleDao(): InterceptRuleDao
    abstract fun savedApiRequestDao(): SavedApiRequestDao

    companion object {
        @Volatile
        private var INSTANCE: RequestrDatabase? = null

        fun get(context: Context): RequestrDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RequestrDatabase::class.java,
                    "requestr.db",
                )
                    // Schema is young; destructive migration is acceptable pre-1.0 and avoids
                    // shipping a broken upgrade path. Replace with real migrations post-release.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
