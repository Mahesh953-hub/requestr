package com.example.data.persistence

import android.content.Context
import android.content.SharedPreferences

/**
 * Synchronous, pre-database feature flag for the bundled SQLite driver.
 *
 * This flag gates whether [com.example.data.local.RequestrDatabase] uses
 * [androidx.sqlite.driver.bundled.BundledSQLiteDriver] (bundled mode) or the
 * default `FrameworkSQLiteOpenHelperFactory` (support mode).
 *
 * ## Why SharedPreferences instead of DataStore?
 *
 * The flag must be readable **synchronously** before the Room database is built —
 * i.e. before any coroutine scope or DataStore is available.  SharedPreferences is
 * the only Android persistence mechanism that provides a blocking, zero-dependency
 * read at process start.
 *
 * ## Rollout / rollback
 *
 * - **Default: on** (`true`) from S0 onward.  The bundled driver guarantees FTS5 and
 *   JSON1 on every device, which is the baseline for the v22 FTS5 migration (S3).
 *   However, Room 2.7.0 in pure driver mode requires `migrate(SQLiteConnection)`
 *   overrides; the existing v8→v18 migrations only override
 *   `migrate(SupportSQLiteDatabase)`.  [com.example.data.local.RequestrDatabase]
 *   detects this and transparently falls back to the support driver for that single
 *   open when a migration is required, then uses the bundled driver on subsequent
 *   opens once the schema is current.
 * - **Rollback**: call [setEnabled]`(false)`.  The next database open falls back to
 *   the framework driver.  Room's WAL file is compatible between the two drivers
 *   (both use standard SQLite), so no data is lost.
 * - **Activate from off**: call [setEnabled]`(true)` from a settings screen or
 *   remote-config callback.  Takes effect on the next `RequestrDatabase.get()`.
 *
 * ## Measured per-ABI size impact of `androidx.sqlite:sqlite-bundled:2.5.0`
 *
 * Measured from the published AAR (`sqlite-bundled-release.aar`), `libsqliteJni.so`:
 *
 * | ABI           | `.so` size |
 * |---------------|------------|
 * | arm64-v8a     | 1.9 MB     |
 * | armeabi-v7a   | 1.5 MB     |
 * | x86_64        | 2.0 MB     |
 * | x86           | 2.0 MB     |
 * | **Total (all ABIs)** | **7.2 MB** |
 *
 * With ABI splits or App Bundle delivery, only one `.so` is shipped per device.
 * Without splits, all four are included.
 *
 * ## Fallback: io.requery:sqlite-android
 *
 * If `BundledSQLiteDriver` conflicts with any `SupportSQLiteDatabase` path at
 * runtime (e.g. a third-party library that down-casts the helper), the alternative
 * is `io.requery:sqlite-android` — a drop-in `SupportSQLiteOpenHelper.Factory` that
 * ships its own SQLite binary.  It stays on the support-driver path (no
 * `setDriver()`) and is fully compatible with `MigrationTestHelper` and all existing
 * DAO code.  Switching requires only replacing the dependency and passing
 * `RequerySQLite.openHelperFactory()` to `.openHelperFactory(…)` on the builder.
 */
class DbDriverFlag private constructor(private val prefs: SharedPreferences) {

    /**
     * Returns `true` if the bundled SQLite driver should be used.
     * Safe to call from any thread, including before Room is initialized.
     */
    val isEnabled: Boolean get() = prefs.getBoolean(KEY_BUNDLED_DRIVER, true)

    /**
     * Enables or disables the bundled driver.  Takes effect on the next database
     * open (typically next process start).
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUNDLED_DRIVER, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "requestr_db_driver_bootstrap"
        private const val KEY_BUNDLED_DRIVER = "bundled_sqlite_driver_enabled"

        @Volatile
        private var INSTANCE: DbDriverFlag? = null

        /** Returns (and lazily creates) the singleton flag instance. */
        fun get(context: Context): DbDriverFlag =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DbDriverFlag(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { INSTANCE = it }
            }

        /**
         * Creates an instance from an arbitrary [SharedPreferences] — for tests only.
         * Production code MUST use [get] to ensure the singleton is used.
         */
        internal fun forTest(prefs: SharedPreferences): DbDriverFlag = DbDriverFlag(prefs)
    }
}
