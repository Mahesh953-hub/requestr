package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * S2 task 14 -- v18 -> v19, the persistence spine (plan §4, v19 row).
 *
 * Purely additive: three new child tables of `captured_traffic` (ordered duplicate-preserving
 * headers, nullable measured timings, rule provenance), ten body-reference columns plus two
 * API-Testr protocol columns on `captured_traffic`, and `saved_api_requests.protocolPreference`.
 * No row is rewritten and no value is backfilled: timings for pre-v19 rows simply do not exist,
 * which is the truth -- inventing them is what task 18 removes elsewhere.
 *
 * DDL mirrors exactly what Room generates for the entities in [PersistenceEntities.kt] so
 * `MigrationTestHelper.runMigrationsAndValidate` accepts the migrated schema (Room compares
 * column types/nullability/PK and the foreign keys/indices, not the `IF NOT EXISTS` spelling).
 * Indices are separate `CREATE INDEX` statements -- SQLite rejects inline `INDEX(...)` in
 * `CREATE TABLE`.
 *
 * Same `NOT NULL DEFAULT` discipline as `MIGRATION_15_16`/`MIGRATION_11_12`: a new NOT NULL
 * column always carries a DEFAULT so existing rows stay valid; the entities declare no
 * `@ColumnInfo(defaultValue)`, so Room's validator ignores the DB-side default (it only compares
 * defaults the entity declares).
 */
val MIGRATION_18_19: Migration = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // -- A. captured_traffic: body references (task 16) + API-Testr protocol columns (task 58) --
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN requestBodyFilePath TEXT")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN responseBodyFilePath TEXT")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN requestBodySha256 TEXT")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN responseBodySha256 TEXT")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN requestBodyPreview TEXT")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN responseBodyPreview TEXT")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN requestBodyStoredBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN responseBodyStoredBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN requestBodyTotalBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN responseBodyTotalBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN negotiatedProtocol TEXT")
        db.execSQL("ALTER TABLE captured_traffic ADD COLUMN protocolDowngradeReason TEXT")

        // -- B. saved_api_requests.protocolPreference (task 58 reads it; default AUTO) --
        db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN protocolPreference TEXT NOT NULL DEFAULT 'AUTO'")

        // -- C. transaction_headers --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS transaction_headers (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "trafficId INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, " +
                "ordinal INTEGER NOT NULL, " +
                "name TEXT NOT NULL, " +
                "nameLower TEXT NOT NULL, " +
                "value TEXT NOT NULL, " +
                "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_transaction_headers_trafficId_kind_ordinal " +
                "ON transaction_headers(trafficId, kind, ordinal)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_headers_nameLower ON transaction_headers(nameLower)")

        // -- D. transaction_timings: every measurement nullable, no sentinels --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS transaction_timings (" +
                "trafficId INTEGER PRIMARY KEY NOT NULL, " +
                "dnsMs INTEGER, connectMs INTEGER, tlsMs INTEGER, sendMs INTEGER, " +
                "waitMs INTEGER, receiveMs INTEGER, totalMs INTEGER, " +
                "queuedAt INTEGER, firstByteAt INTEGER, lastByteAt INTEGER, " +
                "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )

        // -- E. applied_rules (rule provenance, written by the S4 pipeline) --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS applied_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "trafficId INTEGER NOT NULL, " +
                "ruleId TEXT NOT NULL, " +
                "ruleName TEXT NOT NULL, " +
                "stage TEXT NOT NULL, " +
                "phase TEXT NOT NULL, " +
                "outcome TEXT NOT NULL, " +
                "detail TEXT, " +
                "appliedAt INTEGER NOT NULL, " +
                "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_applied_rules_trafficId ON applied_rules(trafficId)")
    }
}
