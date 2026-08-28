package com.example.interception

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppDatabaseBrowserTest {

    @Test
    fun `valid package names pass`() {
        assertTrue(isValidPackageName("com.example.app"))
        assertTrue(isValidPackageName("com.example.app123"))
        assertTrue(isValidPackageName("org.mozilla.firefox"))
    }

    @Test
    fun `invalid package names are rejected`() {
        assertFalse(isValidPackageName(""))
        assertFalse(isValidPackageName("com"))
        assertFalse(isValidPackageName("com.example; rm -rf"))
        assertFalse(isValidPackageName("../etc/passwd"))
        assertFalse(isValidPackageName("com..example"))
        assertFalse(isValidPackageName("com.example.app/"))
    }

    @Test
    fun `valid db file names pass`() {
        assertTrue(isValidDbFileName("app.db"))
        assertTrue(isValidDbFileName("cache-db_v2.sqlite"))
    }

    @Test
    fun `db file names with traversal or path separators are rejected`() {
        assertFalse(isValidDbFileName("../app.db"))
        assertFalse(isValidDbFileName("/etc/passwd"))
        assertFalse(isValidDbFileName("dir/app.db"))
        assertFalse(isValidDbFileName("."))
        assertFalse(isValidDbFileName(".."))
        assertFalse(isValidDbFileName(""))
    }

    @Test
    fun `sidecar files are detected`() {
        assertTrue(isSideCarDbFile("app.db-journal"))
        assertTrue(isSideCarDbFile("app.db-wal"))
        assertTrue(isSideCarDbFile("app.db-shm"))
        assertFalse(isSideCarDbFile("app.db"))
    }

    @Test
    fun `safe sql identifiers pass`() {
        assertTrue(isSafeSqlIdentifier("users"))
        assertTrue(isSafeSqlIdentifier("_internal_table"))
        assertTrue(isSafeSqlIdentifier("Table123"))
    }

    @Test
    fun `unsafe sql identifiers are rejected`() {
        assertFalse(isSafeSqlIdentifier(""))
        assertFalse(isSafeSqlIdentifier("users; DROP TABLE users"))
        assertFalse(isSafeSqlIdentifier("users\""))
        assertFalse(isSafeSqlIdentifier("1users"))
        assertFalse(isSafeSqlIdentifier("users table"))
    }

    @Test
    fun `plain select statements are allowed`() {
        assertTrue(isSafeReadOnlyQuery("SELECT * FROM users"))
        assertTrue(isSafeReadOnlyQuery("select id, name from users where id = ?"))
        assertTrue(isSafeReadOnlyQuery("  SELECT count(*) FROM sqlite_master;  "))
    }

    @Test
    fun `whitelisted introspection pragmas are allowed`() {
        assertTrue(isSafeReadOnlyQuery("PRAGMA table_info(users)"))
        assertTrue(isSafeReadOnlyQuery("pragma database_list"))
        assertTrue(isSafeReadOnlyQuery("PRAGMA integrity_check"))
    }

    @Test
    fun `mutating statements are rejected`() {
        assertFalse(isSafeReadOnlyQuery("DROP TABLE users"))
        assertFalse(isSafeReadOnlyQuery("DELETE FROM users"))
        assertFalse(isSafeReadOnlyQuery("INSERT INTO users VALUES (1)"))
        assertFalse(isSafeReadOnlyQuery("UPDATE users SET name = 'x'"))
        assertFalse(isSafeReadOnlyQuery("ATTACH DATABASE 'x' AS y"))
        assertFalse(isSafeReadOnlyQuery("PRAGMA journal_mode=WAL"))
        assertFalse(isSafeReadOnlyQuery(""))
        assertFalse(isSafeReadOnlyQuery("   "))
    }

    @Test
    fun `statement chaining is rejected`() {
        assertFalse(isSafeReadOnlyQuery("SELECT * FROM users; DROP TABLE users"))
        assertFalse(isSafeReadOnlyQuery("SELECT 1; SELECT 2"))
    }
}
