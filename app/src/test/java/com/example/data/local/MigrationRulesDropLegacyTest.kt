package com.example.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.LegacyRuleAction
import com.example.interception.rules.MockConfig
import com.example.interception.rules.RuleConfigJson
import com.example.interception.rules.RuleStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S4 task 34, v23 → v24 (legacy retirement). Three scenarios:
 *
 *  1. **v18 → 23 → 24 in one hop** -- an old install's `intercept_rules` rows are backfilled into
 *     `interceptor_rules` by v23, then v24 drops the legacy table. Every typed row survives with
 *     its stage/config/order intact and Room's schema validation passes against `24.json`.
 *  2. **Straggler carry** -- a row that was added to `intercept_rules` *after* v23 ran (an install
 *     that sat on v23 while an old code path still wrote the legacy table) is converted by v24's
 *     belt-and-braces backfill before the drop, so no data is lost. Rows already carried by v23
 *     (same `legacyRuleId`) are not duplicated.
 *  3. **Room opens the v24 file** -- `ALL_MIGRATIONS` carries a v18 file all the way and the
 *     typed DAO reads the same rows, including the `hasBlockRuleForDomain` semantics the
 *     Intercept screen's "Block this domain" action depends on.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationRulesDropLegacyTest {

    private val testDbName = "migration-rules-drop-legacy-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { c -> c.moveToFirst() && c.getLong(0) > 0 }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getLong(0) }

    /** Inserts a legacy row using the v18-era column set (identical through v23). */
    private fun insertLegacy(
        db: SupportSQLiteDatabase,
        id: Long,
        name: String,
        action: LegacyRuleAction,
        createdAt: Long,
        domain: String = "",
        pattern: String = "",
        enabled: Boolean = true,
        replacement: String? = null,
    ) {
        val cv = ContentValues().apply {
            put("id", id); put("name", name); put("pattern", pattern); put("domain", domain)
            put("action", action.name); put("enabled", if (enabled) 1 else 0)
            if (replacement != null) put("replacementValue", replacement) else putNull("replacementValue")
            put("createdAt", createdAt)
            put("cryptoKeyAlias", ""); put("cryptoAlgorithm", "AES/GCM/NoPadding")
            put("cryptoDirection", "ENCRYPT"); put("cryptoScope", "BOTH")
        }
        db.insert("intercept_rules", SQLiteDatabase.CONFLICT_ABORT, cv)
    }

    private data class TypedRow(val name: String, val stage: String, val enabled: Boolean, val sortOrder: Int, val configJson: String, val matcherJson: String, val legacyRuleId: Long?)

    private fun readTyped(db: SupportSQLiteDatabase): List<TypedRow> =
        db.query("SELECT name, stage, enabled, sortOrder, configJson, matcherJson, legacyRuleId FROM interceptor_rules ORDER BY sortOrder ASC, id ASC").use { c ->
            generateSequence { if (c.moveToNext()) c else null }.map {
                TypedRow(it.getString(0), it.getString(1), it.getInt(2) != 0, it.getInt(3), it.getString(4), it.getString(5), if (it.isNull(6)) null else it.getLong(6))
            }.toList()
        }

    @Test
    fun `v18 to v24 backfills every legacy rule into the typed table then drops intercept_rules with no data loss`() {
        val db = helper.createDatabase(testDbName, 18)
        insertLegacy(db, 10, "block ads", LegacyRuleAction.BLOCK, createdAt = 100, domain = "ads.example")
        insertLegacy(db, 11, "mock users", LegacyRuleAction.MOCK_RESPONSE, createdAt = 200, pattern = ".*/users$", replacement = """{"statusCode":201,"bodyReplace":"[]"}""")
        insertLegacy(db, 12, "watch auth", LegacyRuleAction.LOG_ONLY, createdAt = 300, pattern = ".*auth.*", enabled = false)
        db.close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 24, true, *RequestrDatabase.ALL_MIGRATIONS)
        try {
            assertFalse("v24 must drop intercept_rules", tableExists(migrated, "intercept_rules"))
            assertTrue(tableExists(migrated, "interceptor_rules"))
            assertTrue(tableExists(migrated, "rule_folders"))

            val rows = readTyped(migrated)
            assertEquals(listOf("block ads", "mock users", "watch auth"), rows.map { it.name })
            assertEquals(listOf("GATEWAY", "MOCK", "OBSERVE"), rows.map { it.stage })
            assertEquals(listOf(true, true, false), rows.map { it.enabled })
            assertEquals(listOf(0, 1, 2), rows.map { it.sortOrder })
            assertEquals(listOf(10L, 11L, 12L), rows.map { it.legacyRuleId })
            assertEquals(GatewayConfig(mode = GatewayMode.BLOCK, showBlockedInList = false), RuleConfigJson.decodeConfig(rows[0].configJson))
            val mock = RuleConfigJson.decodeConfig(rows[1].configJson) as MockConfig
            assertEquals(201, mock.statusCode)
            assertEquals(listOf("*ads.example*"), RuleConfigJson.decodeMatcher(rows[0].matcherJson).hostGlobs)
            assertEquals(".*/users$", RuleConfigJson.decodeMatcher(rows[1].matcherJson).urlRegex)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `v24 carries stragglers written to intercept_rules after v23 without duplicating rows v23 already converted`() {
        var db = helper.createDatabase(testDbName, 22)
        insertLegacy(db, 1, "carried by v23", LegacyRuleAction.BLOCK, createdAt = 1, domain = "a.example")
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 23, true, MIGRATION_22_23)
        assertEquals(1, count(db, "interceptor_rules"))
        // An install parked on v23 while an old writer still appended to the legacy table.
        insertLegacy(db, 2, "straggler", LegacyRuleAction.LOG_ONLY, createdAt = 2, pattern = ".*late.*")
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 24, true, MIGRATION_23_24)
        try {
            assertFalse(tableExists(db, "intercept_rules"))
            val rows = readTyped(db)
            assertEquals(listOf("carried by v23", "straggler"), rows.map { it.name })
            assertEquals(listOf(1L, 2L), rows.map { it.legacyRuleId })
            assertEquals(listOf(0, 1), rows.map { it.sortOrder })
            assertEquals("OBSERVE", rows[1].stage)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v24 on a database with nothing left to carry just drops the table`() {
        val db = helper.createDatabase(testDbName, 23)
        db.close()
        val migrated = helper.runMigrationsAndValidate(testDbName, 24, true, MIGRATION_23_24)
        try {
            assertFalse(tableExists(migrated, "intercept_rules"))
            assertEquals(0, count(migrated, "interceptor_rules"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a v18 file opens through Room at v24 and the typed DAO answers the block-rule query`() {
        val db = helper.createDatabase(testDbName, 18)
        insertLegacy(db, 1, "a", LegacyRuleAction.BLOCK, createdAt = 1, domain = "a.example")
        insertLegacy(db, 2, "b", LegacyRuleAction.LOG_ONLY, createdAt = 2, domain = "b.example")
        db.close()

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RequestrDatabase::class.java, testDbName)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(*RequestrDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val all = room.interceptorRuleDao().getAllOrdered()
                assertEquals(listOf("a", "b"), all.map { it.name })
                assertEquals(listOf(RuleStage.GATEWAY, RuleStage.OBSERVE), all.map { it.stage })
                assertTrue(room.interceptorRuleDao().hasBlockRuleForDomain("a.example"))
                assertFalse(room.interceptorRuleDao().hasBlockRuleForDomain("b.example"))
                assertNull(room.interceptorRuleDao().getById(999))
            }
            assertFalse(tableExists(room.openHelper.readableDatabase, "intercept_rules"))
        } finally {
            room.close()
        }
    }
}
