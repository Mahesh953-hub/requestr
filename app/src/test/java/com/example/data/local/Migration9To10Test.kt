package com.example.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves `MIGRATION_9_10` adds the five new columns to `quic_connections`
 * (`decodeStatus`, `lifecycleState`, `updatedAt`, `clientRandomHex`, `keylogLabels`)
 * without destroying existing rows.  Every `NOT NULL` column has a `DEFAULT` matching
 * the Kotlin entity field, and nullable columns default to `NULL`.
 */
@RunWith(RobolectricTestRunner::class)
class Migration9To10Test {

    private val testDbName = "migration-9-10-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RequestrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 9 to 10 preserves existing quic_connections rows and adds new columns`() {
        var db = helper.createDatabase(testDbName, 9)
        db.execSQL(
            "INSERT INTO quic_connections (connectionId, sessionId, srcIp, destIp, " +
                "srcPort, destPort, sniHost, alpn, quicVersion, dstConnectionId, establishedAt) " +
                "VALUES ('qc-1', 1, '10.0.0.1', '93.184.216.34', 12345, 443, " +
                "'example.com', 'h3', 'Q050', 'abc123', 1700000000000)"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDbName, 10, false, RequestrDatabase.MIGRATION_9_10
        )

        val cursor = db.query(
            "SELECT connectionId, sniHost, decodeStatus, lifecycleState, updatedAt, " +
                "clientRandomHex, keylogLabels FROM quic_connections WHERE connectionId = 'qc-1'"
        )
        assertTrue("Pre-existing row must survive", cursor.moveToFirst())
        assertEquals("qc-1", cursor.getString(0))
        assertEquals("example.com", cursor.getString(1))
        // New NOT NULL columns default correctly
        assertEquals("DETECTED_ONLY", cursor.getString(2))
        assertEquals("INITIALIZING", cursor.getString(3))
        assertEquals(0, cursor.getLong(4))
        // New nullable columns default to NULL
        assertNull(cursor.getString(5))
        assertNull(cursor.getString(6))
        cursor.close()
        db.close()
    }

    @Test
    fun `a freshly inserted v10 row can carry real decode state`() {
        val db = helper.createDatabase(testDbName, 9)
        db.close()
        val migrated = helper.runMigrationsAndValidate(
            testDbName, 10, false, RequestrDatabase.MIGRATION_9_10
        )

        migrated.execSQL(
            "INSERT INTO quic_connections (connectionId, sessionId, srcIp, destIp, " +
                "srcPort, destPort, sniHost, alpn, quicVersion, dstConnectionId, establishedAt, " +
                "decodeStatus, lifecycleState, updatedAt, clientRandomHex, keylogLabels) " +
                "VALUES ('qc-2', 1, '10.0.0.1', '93.184.216.34', 12346, 443, " +
                "'example.com', 'h3', 'Q050', 'def456', 1700000000000, " +
                "'KEYS_AVAILABLE', 'ESTABLISHED', 1700000001000, 'aabbcc', 'CLIENT_RANDOM')"
        )

        val cursor = migrated.query(
            "SELECT decodeStatus, lifecycleState, clientRandomHex, keylogLabels " +
                "FROM quic_connections WHERE connectionId = 'qc-2'"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("KEYS_AVAILABLE", cursor.getString(0))
        assertEquals("ESTABLISHED", cursor.getString(1))
        assertEquals("aabbcc", cursor.getString(2))
        assertEquals("CLIENT_RANDOM", cursor.getString(3))
        cursor.close()
        migrated.close()
    }
}
