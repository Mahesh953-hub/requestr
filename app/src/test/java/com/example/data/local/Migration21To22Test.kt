package com.example.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.search.FtsSchema
import com.example.data.search.TrafficSearchCompiler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S3 task 22, v21 → v22 on the framework/support driver. Robolectric's SQLite has no FTS5
 * module, which is exactly the production situation on a device whose Room build runs the
 * migration on the framework driver: the migration must still succeed (only `search_presets` is
 * a Room-managed table), [FtsSchema.available] must stay false, and the `LIKE` fallback must
 * answer the same query the index would have.
 */
@RunWith(RobolectricTestRunner::class)
class Migration21To22Test {

    private val testDbName = "migration-21-22-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun resetFlag() = FtsSchema.available.set(false)

    @After
    fun clearFlag() = FtsSchema.available.set(false)

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','view') AND name = '$name'").use { c -> c.moveToFirst() && c.getLong(0) > 0 }

    private fun insertV21Row(db: SupportSQLiteDatabase, url: String, host: String, body: String?, timestamp: Long) {
        val cv = ContentValues().apply {
            put("sessionId", 1L); put("method", "GET"); put("url", url); put("host", host); put("path", "/")
            put("statusCode", 200); put("protocol", "HTTP/1.1"); put("requestHeaders", "{}"); put("responseHeaders", "{}")
            if (body != null) put("responseBody", body)
            put("requestSizeBytes", 0L); put("responseSizeBytes", 0L); put("latencyMs", 5L); put("timestamp", timestamp)
            put("wasDecrypted", 1); put("isUndecoded", 0); put("isRequestBodyBinary", 0); put("isResponseBodyBinary", 0)
            put("requestBodyStoredBytes", 0L); put("responseBodyStoredBytes", 0L); put("requestBodyTotalBytes", 0L); put("responseBodyTotalBytes", 0L)
            put("requestBodyBytesTruncated", 0); put("responseBodyBytesTruncated", 0)
            put("captureMethod", CaptureRowMethod.MITM_PROXY.name)
        }
        db.insert("captured_traffic", SQLiteDatabase.CONFLICT_ABORT, cv)
    }

    @Test
    fun `v22 adds search_presets with a unique name index and keeps traffic intact without FTS5`() {
        var db = helper.createDatabase(testDbName, 21)
        insertV21Row(db, "https://api.example/users", "api.example", "rate limit exceeded", 1_000)
        insertV21Row(db, "https://cdn.example/app.js", "cdn.example", "function boot() {}", 2_000)
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 22, true, MIGRATION_21_22)
        assertTrue(tableExists(db, "search_presets"))
        assertFalse("framework SQLite has no FTS5 module, so the index must not exist", tableExists(db, FtsSchema.TABLE))
        assertFalse(FtsSchema.available.get())
        db.query("SELECT COUNT(*) FROM captured_traffic").use { c -> c.moveToFirst(); assertEquals(2L, c.getLong(0)) }

        db.execSQL("INSERT INTO search_presets (name, query, conditions, combineWithOr, createdAt) VALUES ('Errors', 'status:5xx', '[]', 0, 1)")
        val dup = runCatching {
            db.execSQL("INSERT INTO search_presets (name, query, conditions, combineWithOr, createdAt) VALUES ('Errors', 'x', '[]', 0, 2)")
        }
        assertTrue("preset names are unique", dup.isFailure)

        // The LIKE fallback that the app selects when FtsSchema.available is false runs on this schema.
        val c = TrafficSearchCompiler(useFts = false).compile("rate NOT cdn")
        db.query(c.sql, c.args.toTypedArray()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("api.example", cursor.getString(cursor.getColumnIndexOrThrow("host")))
            assertEquals(1, cursor.count)
        }
        db.close()
    }

    @Test
    fun `ensure on the framework driver reports failure without throwing and leaves the schema valid`() {
        var db = helper.createDatabase(testDbName, 21)
        db.close()
        db = helper.runMigrationsAndValidate(testDbName, 22, true, MIGRATION_21_22)
        assertFalse(FtsSchema.ensure(db))
        assertFalse(FtsSchema.available.get())
        // No half-created trigger may survive a failed CREATE VIRTUAL TABLE.
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'traffic_fts%'").use { c -> c.moveToFirst(); assertEquals(0L, c.getLong(0)) }
        db.close()
    }
}
