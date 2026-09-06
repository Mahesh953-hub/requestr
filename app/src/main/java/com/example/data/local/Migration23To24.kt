package com.example.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * S4 task 34 -- v23 -> v24, legacy retirement (interceptor plan §4 v24 row).
 *
 * Drops `intercept_rules`. Every row it held was already converted into a typed
 * `interceptor_rules` row by [MIGRATION_22_23]'s data-carrying backfill (`legacyRuleId` links
 * them), and the parity gate G16 (`LegacyParityTest`) is green, so the legacy table is dead
 * weight: nothing reads it since S4 task 26 moved the engines onto [com.example.interception.rules.RuleSnapshotStore].
 *
 * Belt and braces: before dropping, the migration re-runs the v23 backfill once more. That is a
 * no-op on a normal v23 database (every legacy id is already linked), but it means a row that
 * somehow escaped the first pass -- e.g. a v23 install that ran the original migration while
 * the converter still rejected a shape it accepts today -- is carried across rather than lost.
 * `Migration22To23.backfill` is idempotent by `legacyRuleId`, so this never duplicates a rule.
 *
 * The `InterceptRuleEntity`, `RuleAction`, `InterceptRuleDao` and the two `ruleAction` type
 * converters leave the Room schema with this version; `schemas/24.json` has no `intercept_rules`.
 */
val MIGRATION_23_24: Migration = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Migration23To24.migrate(db)
    }
}

object Migration23To24 {
    private const val TAG = "Migration23To24"

    fun migrate(db: SupportSQLiteDatabase) {
        val carried = Migration22To23.backfill(db)
        if (carried > 0) Log.i(TAG, "v24: carried $carried straggler intercept_rules rows into interceptor_rules before dropping")
        db.execSQL("DROP TABLE IF EXISTS intercept_rules")
    }
}
