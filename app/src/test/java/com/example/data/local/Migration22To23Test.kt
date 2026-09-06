package com.example.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.interception.rules.BodyCryptoConfig
import com.example.interception.rules.BodySource
import com.example.interception.rules.ChaosConfig
import com.example.interception.rules.CryptoDirection
import com.example.interception.rules.CryptoScope
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.LegacyRuleAction
import com.example.interception.rules.MatchCombine
import com.example.interception.rules.MockConfig
import com.example.interception.rules.Modification
import com.example.interception.rules.ObserveConfig
import com.example.interception.rules.RedirectSpec
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleConfigJson
import com.example.interception.rules.RuleStage
import com.example.interception.rules.ScriptConfig
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
 * S4 task 24, v22 → v23. Seeds a v22 `intercept_rules` table with one row per legacy
 * [LegacyRuleAction] (plus ordering and edge cases), runs [MIGRATION_22_23] with Room schema validation,
 * and asserts the typed rows: stage, decoded config, evaluation order → `sortOrder`, and the
 * `legacyRuleId` back-link. `intercept_rules` must be untouched (v24 drops it, not v23).
 */
@RunWith(RobolectricTestRunner::class)
class Migration22To23Test {

    private val testDbName = "migration-22-23-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { c -> c.moveToFirst() && c.getLong(0) > 0 }

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
        alias: String = "",
        direction: String = "ENCRYPT",
        scope: String = "BOTH",
    ) {
        val cv = ContentValues().apply {
            put("id", id); put("name", name); put("pattern", pattern); put("domain", domain)
            put("action", action.name); put("enabled", if (enabled) 1 else 0)
            if (replacement != null) put("replacementValue", replacement) else putNull("replacementValue")
            put("createdAt", createdAt)
            put("cryptoKeyAlias", alias); put("cryptoAlgorithm", "AES/GCM/NoPadding")
            put("cryptoDirection", direction); put("cryptoScope", scope)
        }
        db.insert("intercept_rules", SQLiteDatabase.CONFLICT_ABORT, cv)
    }

    private data class TypedRow(
        val id: Long, val name: String, val stage: String, val enabled: Boolean, val sortOrder: Int,
        val folderId: Long?, val matcherJson: String, val configJson: String, val createdAt: Long,
        val updatedAt: Long, val legacyRuleId: Long?,
    )

    private fun readTyped(db: SupportSQLiteDatabase): List<TypedRow> =
        db.query("SELECT id, name, stage, enabled, sortOrder, folderId, matcherJson, configJson, createdAt, updatedAt, legacyRuleId FROM interceptor_rules ORDER BY sortOrder ASC, id ASC").use { c ->
            generateSequence { if (c.moveToNext()) c else null }.map {
                TypedRow(
                    it.getLong(0), it.getString(1), it.getString(2), it.getInt(3) != 0, it.getInt(4),
                    if (it.isNull(5)) null else it.getLong(5), it.getString(6), it.getString(7),
                    it.getLong(8), it.getLong(9), if (it.isNull(10)) null else it.getLong(10),
                )
            }.toList()
        }

    @Test
    fun `v23 creates the five tables and leaves intercept_rules in place`() {
        helper.createDatabase(testDbName, 22).close()
        val db = helper.runMigrationsAndValidate(testDbName, 23, true, MIGRATION_22_23)
        for (t in listOf("interceptor_rules", "rule_folders", "breakpoint_paused_flows", "replay_jobs", "replay_results", "intercept_rules")) {
            assertTrue("missing $t", tableExists(db, t))
        }
        db.query("SELECT COUNT(*) FROM interceptor_rules").use { c -> c.moveToFirst(); assertEquals(0L, c.getLong(0)) }
        db.close()
    }

    @Test
    fun `v23 backfills every legacy action into a typed rule in evaluation order`() {
        var db = helper.createDatabase(testDbName, 22)
        // Deliberately out of id order and with a createdAt tie so the ORDER BY contract is exercised.
        insertLegacy(db, 11, "block ads", LegacyRuleAction.BLOCK, createdAt = 5_000, domain = "ads.example")
        insertLegacy(db, 2, "force", LegacyRuleAction.FORCE_MITM, createdAt = 1_000, domain = "pinned.example")
        insertLegacy(db, 3, "log", LegacyRuleAction.LOG_ONLY, createdAt = 1_000, pattern = "^https://log/", enabled = false)
        insertLegacy(db, 4, "redirect", LegacyRuleAction.REDIRECT, createdAt = 2_000, domain = "prod.example", replacement = "https://staging.example/")
        insertLegacy(db, 5, "mod req", LegacyRuleAction.MODIFY_REQUEST, createdAt = 2_500, pattern = "/api/",
            replacement = """{"addHeaders":{"x-debug":"1"},"removeHeaders":["cookie"],"bodyReplace":"file:///sdcard/f.json","variables":{"t":"1"}}""")
        insertLegacy(db, 6, "mod resp", LegacyRuleAction.MODIFY_RESPONSE, createdAt = 3_000, domain = "api.example",
            replacement = """{"statusCode":500,"addHeaders":{"x-r":"y"},"bodyReplace":"oops","removeHeaders":["etag"]}""")
        insertLegacy(db, 7, "mock", LegacyRuleAction.MOCK_RESPONSE, createdAt = 3_500, domain = "mock.example",
            replacement = """{"statusCode":201,"statusMessage":"Created","addHeaders":{"Content-Type":"text/plain"},"bodyReplace":"hi"}""")
        insertLegacy(db, 8, "script", LegacyRuleAction.SCRIPT_REWRITE, createdAt = 4_000, domain = "js.example",
            replacement = """{"script":"function onRequest(r){}"}""")
        insertLegacy(db, 9, "chaos", LegacyRuleAction.CHAOS, createdAt = 4_500, domain = "chaos.example",
            replacement = """{"failRate":0.5,"delayMs":[100,200]}""")
        insertLegacy(db, 10, "enc", LegacyRuleAction.BODY_ENCRYPT_AES, createdAt = 4_750, domain = "enc.example", alias = "k-enc", scope = "REQUEST")
        insertLegacy(db, 1, "dec", LegacyRuleAction.BODY_DECRYPT_AES, createdAt = 6_000, domain = "dec.example", alias = "k-dec", direction = "DECRYPT", scope = "RESPONSE")
        // Malformed JSON: legacy fail-open -> empty config, still converted.
        insertLegacy(db, 12, "broken", LegacyRuleAction.MOCK_RESPONSE, createdAt = 7_000, domain = "b.example", replacement = "{nope")
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 23, true, MIGRATION_22_23)
        val rows = readTyped(db)
        assertEquals(12, rows.size)

        // Evaluation order: createdAt ASC, id ASC -> 2,3 (tie broken by id), 4,5,6,7,8,9,10,11,1,12
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 1L, 12L), rows.map { it.legacyRuleId })
        assertEquals((0 until 12).toList(), rows.map { it.sortOrder })
        rows.forEach { assertNull(it.folderId) }

        val byLegacy = rows.associateBy { it.legacyRuleId!! }
        fun cfg(legacyId: Long) = RuleConfigJson.decodeConfig(byLegacy.getValue(legacyId).configJson)
        fun matcher(legacyId: Long) = RuleConfigJson.decodeMatcher(byLegacy.getValue(legacyId).matcherJson)

        byLegacy.getValue(11).let {
            assertEquals(RuleStage.GATEWAY.name, it.stage)
            assertEquals("block ads", it.name)
            assertEquals(5_000L, it.createdAt); assertEquals(5_000L, it.updatedAt)
            assertEquals(GatewayConfig(GatewayMode.BLOCK, showBlockedInList = false), cfg(11))
            assertEquals(listOf("*ads.example*"), matcher(11).hostGlobs)
            assertEquals(MatchCombine.ANY, matcher(11).combine)
        }
        assertEquals(GatewayConfig(GatewayMode.FORCE_DECRYPT), cfg(2))
        byLegacy.getValue(3).let {
            assertEquals(RuleStage.OBSERVE.name, it.stage)
            assertFalse(it.enabled)
            assertEquals(ObserveConfig, cfg(3))
            assertEquals("^https://log/", matcher(3).urlRegex)
            assertTrue(matcher(3).hostGlobs.isEmpty())
        }
        assertEquals(RewriteConfig(redirection = RedirectSpec("https://staging.example/")), cfg(4))
        assertEquals(
            RewriteConfig(
                requestModifications = listOf(
                    Modification.SetHeader("x-debug", "1"),
                    Modification.RemoveHeader("cookie"),
                    Modification.ReplaceBody(BodySource.File("file:///sdcard/f.json")),
                ),
                variables = mapOf("t" to "1"),
            ),
            cfg(5),
        )
        assertEquals(
            RewriteConfig(responseModifications = listOf(Modification.SetStatus(500), Modification.SetHeader("x-r", "y"), Modification.ReplaceBody(BodySource.Text("oops")))),
            cfg(6),
        )
        assertEquals(MockConfig(201, "Created", mapOf("content-type" to "text/plain"), BodySource.Text("hi")), cfg(7))
        assertEquals(ScriptConfig(source = "function onRequest(r){}"), cfg(8))
        assertEquals(ChaosConfig(failRate = 0.5, delayMinMs = 100, delayMaxMs = 200), cfg(9))
        assertEquals(BodyCryptoConfig(CryptoDirection.ENCRYPT, CryptoScope.REQUEST, "k-enc"), cfg(10))
        assertEquals(BodyCryptoConfig(CryptoDirection.DECRYPT, CryptoScope.RESPONSE, "k-dec"), cfg(1))
        assertEquals(MockConfig(), cfg(12))
        assertEquals(RuleStage.MOCK.name, byLegacy.getValue(12).stage)

        // Source table untouched.
        db.query("SELECT COUNT(*) FROM intercept_rules").use { c -> c.moveToFirst(); assertEquals(12L, c.getLong(0)) }

        // Re-running the backfill is a no-op (idempotent by legacyRuleId).
        assertEquals(0, Migration22To23.backfill(db))
        assertEquals(12, readTyped(db).size)
        db.close()
    }

    @Test
    fun `migrated database opens through Room with the typed DAO reading the same rows`() {
        var db = helper.createDatabase(testDbName, 22)
        insertLegacy(db, 1, "a", LegacyRuleAction.BLOCK, createdAt = 1, domain = "a.example")
        insertLegacy(db, 2, "b", LegacyRuleAction.LOG_ONLY, createdAt = 2, domain = "b.example", enabled = false)
        db.close()
        helper.runMigrationsAndValidate(testDbName, 23, true, MIGRATION_22_23).close()

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
                val enabled = room.interceptorRuleDao().getEnabledOrdered()
                assertEquals(listOf(1L), enabled.map { it.legacyRuleId })
                val domain = all.first().toDomain()
                assertEquals(GatewayConfig(GatewayMode.BLOCK), domain.config)
                assertEquals(RuleStage.GATEWAY, domain.stage)
                assertTrue(room.interceptorRuleDao().hasBlockRuleForDomain("a.example"))
                assertFalse(room.interceptorRuleDao().hasBlockRuleForDomain("b.example"))
                // ALL_MIGRATIONS carried the file on to v24, which dropped `intercept_rules`
                // (MigrationRulesDropLegacyTest covers that step); the typed rows are what remain.
                assertEquals(1, room.interceptorRuleDao().getEnabledOrdered().size)
            }
        } finally {
            room.close()
        }
    }
}
