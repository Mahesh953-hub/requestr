package com.example.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.interception.rules.LegacyRuleConverter
import com.example.interception.rules.RuleConfigJson

/**
 * S4 task 24 -- v22 -> v23, the typed rule library (interceptor plan §4, v23 row).
 *
 * 1. Creates `interceptor_rules`, `rule_folders`, `breakpoint_paused_flows`, `replay_jobs` and
 *    `replay_results` with SQL that mirrors the entities in RuleEntities.kt exactly (columns,
 *    NOT NULL-ness, primary keys, indices, foreign keys) -- the MIGRATION_17_18 discipline.
 * 2. **Data-carrying backfill:** reads every `intercept_rules` row inside the migration transaction,
 *    converts it through [LegacyRuleConverter] (the one converter shared with import and the local
 *    API) and inserts the typed row. Evaluation order is preserved: legacy `getEnabledRules()` had
 *    no ORDER BY (rowid scan = `createdAt ASC, id ASC`), which becomes ascending `sortOrder`.
 *    `legacyRuleId` links each typed row to its source. Follows MIGRATION_16_17's data-carrying
 *    pattern (chat backfill).
 * 3. `intercept_rules` is **not** dropped or altered. It stays read-only until v24 (task 34) once
 *    the parity gate G16 is green -- see the plan's v24 row.
 *
 * The backfill is idempotent by construction: a typed row whose `legacyRuleId` already exists is
 * skipped, so re-running [backfill] (or a v23 install that somehow re-enters the migration) never
 * duplicates rules. Rows the converter cannot convert are logged and left in `intercept_rules`;
 * nothing is invented, nothing is deleted.
 */
val MIGRATION_22_23: Migration = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Migration22To23.createTables(db)
        Migration22To23.backfill(db)
    }
}

object Migration22To23 {

    private const val TAG = "Migration22To23"

    fun createTables(db: SupportSQLiteDatabase) {
        // -- interceptor_rules (InterceptorRuleEntity) --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS interceptor_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "stage TEXT NOT NULL, " +
                "enabled INTEGER NOT NULL, " +
                "sortOrder INTEGER NOT NULL, " +
                "folderId INTEGER, " +
                "matcherJson TEXT NOT NULL, " +
                "configJson TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "legacyRuleId INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_interceptor_rules_stage ON interceptor_rules(stage)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_interceptor_rules_enabled ON interceptor_rules(enabled)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_interceptor_rules_sortOrder ON interceptor_rules(sortOrder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_interceptor_rules_folderId ON interceptor_rules(folderId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_interceptor_rules_legacyRuleId ON interceptor_rules(legacyRuleId)")

        // -- rule_folders (RuleFolderEntity) --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS rule_folders (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "sortOrder INTEGER NOT NULL, " +
                "enabled INTEGER NOT NULL, " +
                "collapsed INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rule_folders_sortOrder ON rule_folders(sortOrder)")

        // -- breakpoint_paused_flows (PausedFlowEntity) --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS breakpoint_paused_flows (" +
                "flowId TEXT PRIMARY KEY NOT NULL, " +
                "ruleId INTEGER NOT NULL, " +
                "ruleName TEXT NOT NULL, " +
                "phase TEXT NOT NULL, " +
                "host TEXT NOT NULL, " +
                "url TEXT NOT NULL, " +
                "method TEXT, " +
                "requestSnapshot TEXT, " +
                "responseSnapshot TEXT, " +
                "pausedAt INTEGER NOT NULL, " +
                "timeoutAt INTEGER NOT NULL, " +
                "state TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_breakpoint_paused_flows_pausedAt ON breakpoint_paused_flows(pausedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_breakpoint_paused_flows_ruleId ON breakpoint_paused_flows(ruleId)")

        // -- replay_jobs (ReplayJobEntity) --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS replay_jobs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "sourceFlowIds TEXT NOT NULL, " +
                "iterations INTEGER NOT NULL, " +
                "intervalMs INTEGER NOT NULL, " +
                "jitterMs INTEGER NOT NULL, " +
                "concurrency INTEGER NOT NULL, " +
                "proxyTarget TEXT, " +
                "status TEXT NOT NULL, " +
                "queuedCount INTEGER NOT NULL, " +
                "completedCount INTEGER NOT NULL, " +
                "failedCount INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "startedAt INTEGER, " +
                "completedAt INTEGER, " +
                "error TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_replay_jobs_status ON replay_jobs(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_replay_jobs_createdAt ON replay_jobs(createdAt)")

        // -- replay_results (ReplayResultEntity) --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS replay_results (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "jobId INTEGER NOT NULL, " +
                "sourceFlowId INTEGER NOT NULL, " +
                "attemptNumber INTEGER NOT NULL, " +
                "capturedTrafficId INTEGER, " +
                "statusCode INTEGER, " +
                "latencyMs INTEGER, " +
                "error TEXT, " +
                "startedAt INTEGER NOT NULL, " +
                "completedAt INTEGER, " +
                "FOREIGN KEY(jobId) REFERENCES replay_jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_replay_results_jobId ON replay_results(jobId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_replay_results_sourceFlowId ON replay_results(sourceFlowId)")
    }

    /**
     * Converts every `intercept_rules` row not yet represented in `interceptor_rules` (by
     * `legacyRuleId`). Returns the number of typed rows inserted. Safe to call repeatedly.
     */
    fun backfill(db: SupportSQLiteDatabase): Int {
        if (!tableExists(db, "intercept_rules")) return 0

        val already = HashSet<Long>()
        db.query("SELECT legacyRuleId FROM interceptor_rules WHERE legacyRuleId IS NOT NULL").use { c ->
            while (c.moveToNext()) already += c.getLong(0)
        }

        val converters = RequestrTypeConverters()
        val legacy = ArrayList<InterceptRuleEntity>()
        db.query(
            "SELECT id, name, pattern, domain, action, enabled, replacementValue, createdAt, " +
                "cryptoKeyAlias, cryptoAlgorithm, cryptoDirection, cryptoScope " +
                "FROM intercept_rules ORDER BY createdAt ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in already) continue
                legacy += InterceptRuleEntity(
                    id = id,
                    name = c.getString(1) ?: "",
                    pattern = c.getString(2) ?: "",
                    domain = c.getString(3) ?: "",
                    action = converters.nameToRuleAction(c.getString(4)),
                    enabled = c.getInt(5) != 0,
                    replacementValue = if (c.isNull(6)) null else c.getString(6),
                    createdAt = c.getLong(7),
                    cryptoKeyAlias = c.getString(8) ?: "",
                    cryptoAlgorithm = c.getString(9) ?: "AES/GCM/NoPadding",
                    cryptoDirection = c.getString(10) ?: "ENCRYPT",
                    cryptoScope = c.getString(11) ?: "BOTH",
                )
            }
        }
        if (legacy.isEmpty()) return 0

        // New rows sort after whatever typed rules already exist (none on a fresh v23 upgrade).
        var nextSort = db.query("SELECT COALESCE(MAX(sortOrder), -1) FROM interceptor_rules").use { c ->
            if (c.moveToFirst()) c.getInt(0) + 1 else 0
        }

        var inserted = 0
        for (result in LegacyRuleConverter.convertAll(legacy, firstSortOrder = nextSort)) {
            when (result) {
                is LegacyRuleConverter.ConversionResult.Converted -> {
                    val rule = result.rule
                    val cv = ContentValues().apply {
                        put("name", rule.name)
                        put("stage", rule.stage.name)
                        put("enabled", if (rule.enabled) 1 else 0)
                        put("sortOrder", rule.sortOrder)
                        putNull("folderId")
                        put("matcherJson", RuleConfigJson.encodeMatcher(rule.matcher))
                        put("configJson", RuleConfigJson.encodeConfig(rule.config))
                        put("createdAt", rule.createdAt)
                        put("updatedAt", rule.updatedAt)
                        put("legacyRuleId", rule.legacyRuleId)
                    }
                    db.insert("interceptor_rules", SQLiteDatabase.CONFLICT_ABORT, cv)
                    inserted++
                    nextSort = rule.sortOrder + 1
                    for (w in result.warnings) Log.w(TAG, "v23 backfill: $w")
                }
                is LegacyRuleConverter.ConversionResult.Unconvertible ->
                    Log.e(TAG, "v23 backfill: intercept_rules #${result.legacyId} left unconverted: ${result.reason}")
            }
        }
        Log.i(TAG, "v23 backfill: converted $inserted of ${legacy.size} legacy intercept_rules rows")
        return inserted
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { c ->
            c.moveToFirst() && c.getLong(0) > 0
        }
}
