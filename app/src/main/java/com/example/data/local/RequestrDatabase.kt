package com.example.data.local

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.persistence.DbBackup
import com.example.data.persistence.PersistenceHealth
import com.example.data.persistence.DbDriverFlag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

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
        FlowNodeEntity::class,
        FlowConnectionEntity::class,
        FlowPipelineEntity::class,
        TcpConnectionEntity::class,
        UdpFlowEntity::class,
        QuicConnectionEntity::class,
        TlsSessionEntity::class,
        DnsQueryEntity::class,
        NoteEntity::class,
        NoteFolderEntity::class,
        CustomToolEntity::class,
        ChatMessageEntity::class,
        ChatProviderEntity::class,
        ChatConversationEntity::class,
        RecentActivityEntity::class,
        ConnectAttemptEntity::class,
        // S2 task 14 (v19): persistence spine children of captured_traffic.
        TransactionHeaderEntity::class,
        TransactionTimingEntity::class,
        AppliedRuleEntity::class,
        // S3 task 20 (v20): first-class protocol events, children of captured_traffic.
        WebSocketMessageEntity::class,
        SseEventEntity::class,
        GrpcCallEntity::class,
        GrpcMessageEntity::class,
        Http2StreamEventEntity::class,
    ],
    // v3.5: bumped 4 -> 5 for FlowNodeEntity + FlowConnectionEntity (Flow persistence).
    // v5.6 (M1): bumped 5 -> 6 to register TcpConnectionEntity/UdpFlowEntity/QuicConnectionEntity,
    // which previously existed as dead code with no table backing them.
    // v5.6 (M2): bumped 6 -> 7 to register TlsSessionEntity (TLS handshake/cipher/cert model).
    // v5.6 (T2): bumped 7 -> 8 to add CapturedTrafficEntity.sourceIp + CaptureTier.GATEWAY,
    // used by GatewayEngine to tag/group traffic proxied on behalf of remote LAN devices.
    // v5.7 (D2): also part of the 7 -> 8 bump (merged in the same release): registers
    // DnsQueryEntity -- PacketPump.dnsCallback previously parsed real DNS queries/responses via
    // DnsParser and discarded them (log-only); this table is what makes DNS a genuine first hop
    // in ConnectionLineage instead of a documented gap.
    // SDK/CONNECT scope: ConnectAttemptEntity is registered above for the CONNECT control
    // subsystem (see ConnectControl.kt) -- every CONNECT tunnel establishment is now a
    // persisted, inspectable record instead of a blind byte relay. Its connect_attempts
    // CREATE TABLE + indices live in MIGRATION_8_9 Section C below, since the transport
    // scope owns this database's version number and migrations.
    // v5.8 (task 10): bumped 8 -> 9. Adds CapturedTrafficEntity.connectionId (task 12's exact-key
    // lineage join, replacing the getByHostInWindow 30s heuristic) and TlsSessionEntity.failureReason
    // / failedAt (task 11's real FAILED handshake state). Also registers five app-scope tables
    // (notes, note_folders, custom_tools, chat_messages, chat_providers -- see plan-app.md H1).
    // This is the first real Migration this database has ever shipped: previously every version
    // bump relied on fallbackToDestructiveMigration(dropAllTables = true), silently wiping a
    // user's captured traffic, saved requests, and flow graphs on every upgrade. MIGRATION_8_9
    // below is a genuine ALTER/CREATE migration, and exportSchema is now true so future
    // migrations have a real prior-version JSON snapshot to validate against instead of trusting
    // hand-written SQL alone.
    // v6.0 transplant: bumped 10 -> 11 to add FlowPipelineEntity (multi-flow persistence).
    // Master plan Phase 1-3: bumped 11 -> 12 to add SavedApiRequestEntity's API-key placement
    // column (§2.13) and per-request mock-mode columns (§2.8). RuleAction.MOCK_RESPONSE (§1.5)
    // needs no schema change -- RuleAction is persisted by name.
    // Master plan §2.x LOW-ADD: bumped 12 -> 13 to add SavedApiRequestEntity's async 202-polling
    // columns (pollingEnabled/pollingIntervalMs/pollingMaxAttempts/pollingLocationHeader).
    // RuleAction.SCRIPT_REWRITE (§1.11) needs no schema change either, for the same
    // persisted-by-name reason as MOCK_RESPONSE above.
    // P2 review fix (async-polling credential leak): bumped 13 -> 14 to add
    // SavedApiRequestEntity.pollingAllowCrossOriginRedirect, the explicit opt-in gate for
    // following a poll Location that resolves to a different origin (see
    // ApiTestrRepository.executeRequestWithScripts). RuleAction.CHAOS needs no schema change,
    // for the same persisted-by-name reason as every other RuleAction value.
    // §2.14(b): bumped 14 -> 15 to add InterceptRuleEntity.cryptoKeyAlias/cryptoAlgorithm/
    // cryptoDirection/cryptoScope for the new BODY_ENCRYPT_AES/BODY_DECRYPT_AES RuleActions
    // (which, like every other RuleAction, need no schema change themselves -- persisted by
    // name via RequestrTypeConverters).
    // Item 1 (dossier Q2 / audit §7.5): bumped 15 -> 16 to add
    // CapturedTrafficEntity.captureMethod (see CaptureRowMethod's doc) so the traffic row can
    // record which capture tier actually produced it, independent of whatever the app's current
    // global capture setting is.
    // Reqy Assistant completeness pass, item 1: bumped 16 -> 17 to add ChatConversationEntity
    // (real multi-conversation chat history, see its doc) and ChatMessageEntity.conversationId.
    // MIGRATION_16_17 preserves every existing chat_messages row -- it does NOT drop them --
    // by folding them into one derived-title "default-migrated" conversation.
    // Home rebrand (home_rebranding.md §6): bumped 17 -> 18 to add RecentActivityEntity, the real
    // "recently opened" usage history. Home previously fabricated this section from the first two
    // saved requests and first two notes with literal "Recent"/"Saved" timestamp strings; the new
    // table records genuine open events (see RecentActivityRepository). Pure CREATE TABLE -- no
    // existing table is touched, and there is deliberately no backfill: nothing has been "opened"
    // until the user opens it, so an upgraded install starts with an honest empty section.
    // S2 task 14 (interceptor plan §4 v19): bumped 18 -> 19 for the persistence spine --
    // transaction_headers / transaction_timings / applied_rules child tables, body-reference
    // columns for the spill store (task 16), and the API-Testr protocol columns reserved for
    // tasks 58-60. Additive only; see Migration18To19.kt.
    // S3 task 20 (interceptor plan §4 v20): bumped 19 -> 20 for the protocol event tables --
    // websocket_messages / sse_events / grpc_calls / grpc_messages / http2_stream_events, all
    // CASCADE children of captured_traffic. Additive only; see Migration19To20.kt. The legacy
    // flattened WS→/WS← and x-grpc-* rows are folded into them by v21 (task 21).
    // S3 task 21 (interceptor plan §4 v21): bumped 20 -> 21 for the one-time legacy backfill --
    // captured_traffic.legacyBackfill stamp column, then the flattened WS→/WS← rows become
    // websocket_messages children of one synthesized 101 parent per connection and the
    // x-grpc-* rows become grpc_messages + grpc_calls under one synthesized parent per call.
    // See Migration20To21.kt.
    version = 21,
    exportSchema = true,
)
@TypeConverters(RequestrTypeConverters::class)
abstract class RequestrDatabase : RoomDatabase() {

    abstract fun capturedTrafficDao(): CapturedTrafficDao
    abstract fun captureSessionDao(): CaptureSessionDao
    abstract fun interceptRuleDao(): InterceptRuleDao
    abstract fun savedApiRequestDao(): SavedApiRequestDao
    abstract fun flowNodeDao(): FlowNodeDao
    abstract fun flowConnectionDao(): FlowConnectionDao
    abstract fun flowPipelineDao(): FlowPipelineDao
    abstract fun tcpConnectionDao(): TcpConnectionDao
    abstract fun udpFlowDao(): UdpFlowDao
    abstract fun quicConnectionDao(): QuicConnectionDao
    abstract fun tlsSessionDao(): TlsSessionDao
    abstract fun dnsQueryDao(): DnsQueryDao
    abstract fun noteDao(): NoteDao
    abstract fun noteFolderDao(): NoteFolderDao
    abstract fun customToolDao(): CustomToolDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatProviderDao(): ChatProviderDao
    abstract fun chatConversationDao(): ChatConversationDao
    abstract fun recentActivityDao(): RecentActivityDao
    abstract fun connectAttemptDao(): ConnectAttemptDao
    abstract fun transactionHeaderDao(): TransactionHeaderDao
    abstract fun transactionTimingDao(): TransactionTimingDao
    abstract fun appliedRuleDao(): AppliedRuleDao
    abstract fun webSocketMessageDao(): WebSocketMessageDao
    abstract fun sseEventDao(): SseEventDao
    abstract fun grpcCallDao(): GrpcCallDao
    abstract fun grpcMessageDao(): GrpcMessageDao
    abstract fun http2StreamEventDao(): Http2StreamEventDao

    companion object {
        private const val TAG = "RequestrDatabase"

        /**
         * The current schema version declared in the [@Database] annotation above.
         * Kept in sync manually; a CI schema-drift check validates the exported JSON.
         */
        const val SCHEMA_VERSION = 21

        @Volatile
        private var INSTANCE: RequestrDatabase? = null

        /**
         * Deferred that completes when the first [initAsync] finishes building.
         * Replaced by a fresh instance if a test resets the singleton.
         */
        @Volatile
        private var ready = CompletableDeferred<RequestrDatabase>()

        // ── Public entry point ───────────────────────────────────────────────

        /**
         * Eagerly initializes the database on the caller's thread (expected to be
         * `Dispatchers.IO`).  Called from [com.example.RequestrApplication.onCreate].
         *
         * All file I/O (backup/restore, WAL checkpoint, version read) happens here,
         * off the main thread.  When this completes, [get] returns immediately.
         */
        fun initAsync(context: Context) {
            if (INSTANCE != null) return
            synchronized(this) {
                if (INSTANCE != null) return
                val db = buildDatabase(context, DbDriverFlag.get(context).isEnabled)
                warmUp(context, db)
                INSTANCE = db
                ready.complete(db)
            }
        }

        /**
         * Task 17: opens the connection now (off the main thread only) so a failing migration
         * surfaces here, once, as [PersistenceHealth.MigrationEvent.MIGRATION_FAILED] plus a
         * persisted diagnostics row -- instead of as an opaque crash in whichever DAO call
         * happens first. Room runs each migration in a transaction, so on failure the file is
         * still on its previous version; the exception is swallowed here and will re-surface
         * to the first real query, which is the existing behaviour.
         */
        private fun warmUp(context: Context, db: RequestrDatabase) {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null && Looper.myLooper() == mainLooper) return
            try {
                runBlocking { db.useReaderConnection { } }
            } catch (t: Throwable) {
                val detail = (t.message ?: t.javaClass.simpleName).take(200)
                Log.e(TAG, "Database open/migration failed", t)
                PersistenceHealth.recordMigration(PersistenceHealth.MigrationEvent.MIGRATION_FAILED, detail)
                DbBackup(context.applicationContext).recordRecoveryEvent("migration_failed", detail)
            }
        }

        /**
         * Returns the singleton database instance.
         *
         * **Fast path** (post-init): returns the cached [INSTANCE] with zero I/O.
         * Safe to call on any thread including main.
         *
         * **Main-thread cold path**: if [initAsync] was launched (from [RequestrApplication])
         * but has not finished yet, suspends until the in-flight IO init completes.
         * This window is bounded by the IO init duration on cold start and is
         * strictly shorter than the prior architecture where the entire
         * backup/checkpoint/build ran synchronously on main.
         *
         * **Background cold path**: if `initAsync` was never called (unit tests,
         * isolated services), performs the full init synchronously on the current
         * background thread.
         *
         * ### S0 runtime foundation (tasks 3 + 5)
         *
         * - **Bundled SQLite** (gated by [DbDriverFlag], default **on** in S0): uses
         *   [BundledSQLiteDriver] so FTS5/JSON1 are guaranteed on every device.
         * - **Explicit WAL**: `JournalMode.WRITE_AHEAD_LOGGING`.
         * - **Pre-migration backup**: [DbBackup] checkpoints WAL and copies the DB
         *   before any migration, with explicit metadata so data loss is never silent.
         * - **Downgrade safety**: `fallbackToDestructiveMigrationOnDowngrade` is the
         *   non-bricking last resort; [DbBackup] restores on re-upgrade.
         */
        fun get(context: Context): RequestrDatabase {
            // Fast path: already initialized — zero I/O, main-thread safe.
            INSTANCE?.let { return it }

            val mainLooper = Looper.getMainLooper()
            val onMain = mainLooper != null && Looper.myLooper() == mainLooper

            if (onMain && ready.isActive) {
                // Main-thread cold path: initAsync is in-flight, wait for it.
                return runBlocking { ready.await() }
            }

            // Background cold path (or Robolectric where Looper is null/main): init
            // synchronously.  This is the test and early-Service path.
            initAsync(context)
            return INSTANCE!!
        }

        /**
         * Resets the singleton for test isolation.  Production code must never call this.
         */
        @androidx.annotation.VisibleForTesting
        internal fun resetForTest() {
            INSTANCE?.close()
            INSTANCE = null
            ready = CompletableDeferred()
        }

        /**
         * Builds the database.  Extracted so tests can call it with an explicit
         * [useBundledDriver] flag without touching the singleton.
         *
         * @param useBundledDriver `true` → [BundledSQLiteDriver] (on-device only);
         *                         `false` → framework `SupportSQLiteOpenHelper`.
         */
        internal fun buildDatabase(
            context: Context,
            useBundledDriver: Boolean,
        ): RequestrDatabase {
            val appContext = context.applicationContext
            val backup = DbBackup(appContext)
            val dbFile = appContext.getDatabasePath(DbBackup.DB_NAME)

            // ── Phase 1: restore from a previous downgrade-wipe backup ───────
            if (backup.shouldRestoreBeforeOpen(SCHEMA_VERSION)) {
                Log.i(TAG, "Viable backup after previous downgrade wipe — restoring")
                val backupVersion = backup.readMetadata()?.optInt("backupVersion", -1) ?: -1
                if (backup.restoreBackup()) {
                    // Task 17: the wipe is undone -- say so, and keep a diagnostics row.
                    val detail = "v$backupVersion copy restored after a downgrade wipe"
                    PersistenceHealth.recordMigration(PersistenceHealth.MigrationEvent.RESTORED_FROM_BACKUP, detail)
                    backup.recordRecoveryEvent("restored", detail)
                } else {
                    val detail = "backup v$backupVersion could not be restored; starting empty"
                    PersistenceHealth.recordMigration(PersistenceHealth.MigrationEvent.DESTRUCTIVE_WIPE, detail)
                    backup.recordRecoveryEvent("restore_failed", detail)
                }
            }

            // ── Phase 2: backup before any migration ─────────────────────────
            val preOpenVersion: Int = if (dbFile.exists()) {
                backup.readDatabaseVersion(dbFile)
            } else {
                0
            }
            if (preOpenVersion >= DbBackup.MINIMUM_RESTORABLE_VERSION &&
                preOpenVersion != SCHEMA_VERSION
            ) {
                if (backup.backupBeforeMigration(preOpenVersion)) {
                    val detail = "v$preOpenVersion → v$SCHEMA_VERSION"
                    PersistenceHealth.recordMigration(PersistenceHealth.MigrationEvent.BACKED_UP, detail)
                    backup.recordRecoveryEvent("backed_up", detail)
                } else {
                    // Task 17 failure table: the migration still runs (Room migrations are
                    // transactional), but the user is told there is no safety copy.
                    val detail = "v$preOpenVersion → v$SCHEMA_VERSION"
                    PersistenceHealth.recordMigration(PersistenceHealth.MigrationEvent.BACKUP_FAILED, detail)
                    backup.recordRecoveryEvent("backup_failed", detail)
                }
            }

            // ── Phase 2b: bump open generation for stable-launch tracking ────
            backup.bumpOpenGeneration()

            // ── Phase 3: build ───────────────────────────────────────────────
            //
            // Room 2.7.0 pure driver mode requires migrations to override
            // migrate(SQLiteConnection).  The existing v8→v18 migrations only
            // override migrate(SupportSQLiteDatabase), so the bundled driver
            // can only be used when the on-disk version already matches
            // SCHEMA_VERSION (no migration needed) or on a fresh install.
            // If a migration is required, fall back to the support driver.
            val needsMigration = preOpenVersion in 1 until SCHEMA_VERSION
            val effectiveBundled = useBundledDriver && !needsMigration

            if (useBundledDriver && needsMigration) {
                Log.i(
                    TAG,
                    "Bundled driver requested but v$preOpenVersion→v$SCHEMA_VERSION " +
                        "migration needed — falling back to support driver for this open"
                )
            }

            val builder = Room.databaseBuilder(
                appContext,
                RequestrDatabase::class.java,
                DbBackup.DB_NAME,
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigrationFrom(
                    /* dropAllTables = */ true, 1, 2, 3, 4, 5, 6, 7
                )
                .fallbackToDestructiveMigrationOnDowngrade(/* dropAllTables = */ true)
                .addCallback(BackupCallback(backup, preOpenVersion))

            if (effectiveBundled) {
                builder.setDriver(BundledSQLiteDriver())
            }

            return builder.build()
        }

        /**
         * Room callback that fires in **both** support-driver and bundled-driver modes.
         *
         * When Room uses a `SQLiteDriver` (bundled mode), it calls the
         * `SQLiteConnection` overloads. When using the default
         * `SupportSQLiteOpenHelper` path, it calls the `SupportSQLiteDatabase`
         * overloads.  We override both so backup/restore works regardless of mode.
         */
        private class BackupCallback(
            private val backup: DbBackup,
            private val preOpenVersion: Int,
        ) : Callback() {

            // ── Support-driver mode (default / Robolectric) ──────────────────

            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                handleDestructiveMigration()
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                handleOpen()
            }

            // ── Bundled-driver mode (on-device with BundledSQLiteDriver) ─────

            override fun onDestructiveMigration(connection: SQLiteConnection) {
                handleDestructiveMigration()
            }

            override fun onOpen(connection: SQLiteConnection) {
                handleOpen()
            }

            // ── Shared logic ─────────────────────────────────────────────────

            private fun handleDestructiveMigration() {
                backup.recordDestructiveWipe(preOpenVersion, SCHEMA_VERSION)
                // Task 17: rows were lost -- visible in PersistenceHealth (banner) and as a
                // persisted diagnostics row. A restore, if a viable backup exists, happens on
                // the next open (Phase 1), which then reports RESTORED_FROM_BACKUP.
                val detail = "v$preOpenVersion → v$SCHEMA_VERSION downgrade wiped all tables"
                PersistenceHealth.recordMigration(PersistenceHealth.MigrationEvent.DESTRUCTIVE_WIPE, detail)
                backup.recordRecoveryEvent("destructive_wipe", detail)
            }

            private fun handleOpen() {
                backup.markCleanLaunch()
            }
        }

        // ── Migrations ───────────────────────────────────────────────────────
        //
        // All ten migrations v8→v18 are preserved exactly as shipped.

        /**
         * v8 -> v9 (task 10). The first real (non-destructive) migration this database has
         * shipped. Split into two sections by ownership:
         *
         * Section A: transport/packet-core columns (this scope) -- connectionId for the exact-key
         * lineage join (task 12) and TLS failure-state columns (task 11).
         *
         * Section B: app-scope tables (columns owned by docs/plans/plan-app.md H1) -- notes,
         * note_folders, custom_tools, chat_messages, chat_providers. chat_providers deliberately
         * has no API-key column; the key stays in Keystore-backed storage only (H2).
         *
         * Section C: connect_attempts table, owned by the SDK scope (plan-sdk.md).
         * ConnectAttemptEntity itself (and its DAO) live only on the SDK scope's branch --
         * this scope does not register the @Entity/@Dao here, only the backing table/indices,
         * so that when the two branches are merged the entity list (added on the SDK side)
         * and this migration agree on schema instead of Room throwing
         * "Migration didn't properly handle ... connect_attempts" at database-open time.
         */
        /**
         * v11 -> v12 (master plan Phase 1-3). Adds the API-key placement column (§2.13) and the
         * per-saved-request mock-mode columns (§2.8) to `saved_api_requests`. Every column is
         * `NOT NULL DEFAULT`-ed to the value the corresponding Kotlin field already defaults to,
         * so existing rows keep behaving exactly as before (header-placed API keys, mock off).
         *
         * `mockHeaders` stores a JSON array via [RequestrTypeConverters], so its default is `[]`
         * rather than `''` — an empty string would decode to an empty list too, but `[]` keeps
         * the on-disk value consistent with what Room writes for a fresh row.
         */
        /**
         * v12 -> v13. Adds SavedApiRequestEntity's async 202-polling columns (master plan §2.x
         * LOW-ADD). Same discipline as MIGRATION_11_12 immediately below: every `NOT NULL`
         * column gets a `DEFAULT` that textually matches
         * [com.example.data.models.ApiRequestItem]'s corresponding Kotlin default exactly, so an
         * upgraded row and a freshly-created row behave identically. `RuleAction.SCRIPT_REWRITE`
         * needs no schema change -- `RuleAction` is persisted by name (see [RuleAction]'s KDoc).
         */
        /**
         * v13 -> v14. P2 review fix (async-polling credential leak): adds
         * `pollingAllowCrossOriginRedirect`, the explicit opt-in gate for
         * [com.example.data.repository.ApiTestrRepository.executeRequestWithScripts]'s 202-poll
         * loop to follow a `Location` that resolves to a different origin than the original
         * request. Defaults to `0` (false) so every existing saved request keeps today's now-
         * fixed same-origin-only behavior; `RuleAction.CHAOS` needs no schema change, same
         * persisted-by-name reason as every other `RuleAction` value.
         */
        /**
         * Same v14 -> v15 bump as the `intercept_rules` crypto columns above -- folded into one
         * migration rather than bumping to v16, since both landed in the same release. Adds the
         * real-binary-body-persistence columns to `captured_traffic` (see
         * [CapturedTrafficEntity.requestBodyBytes]'s doc for the full three-state contract):
         * `*BodyBytes` are nullable BLOBs (default NULL, i.e. absent, matching every pre-v15 row
         * -- there is no real captured binary data to backfill), `isXBodyBinary`/
         * `XBodyBytesTruncated` are booleans defaulting to `0`/false, matching
         * [CapturedTrafficEntity]'s Kotlin defaults exactly so an upgraded row and a freshly
         * inserted one behave identically (same discipline as every migration above).
         */
        /**
         * v15 -> v16. Item 1 (dossier Q2 / audit §7.5): adds `captured_traffic.captureMethod`,
         * the per-row honest record of which capture tier actually produced the row (see
         * [CaptureRowMethod]'s doc for exactly which engines set which value). Defaults to
         * `'UNKNOWN'`, matching [CapturedTrafficEntity.captureMethod]'s Kotlin default exactly --
         * every pre-v16 row's real capture method is simply not recorded, and the UI must treat
         * that as genuinely absent rather than "definitely not VPN".
         */
        /**
         * v16 -> v17. Reqy Assistant completeness pass, item 1: real multi-conversation chat
         * history. Adds `chat_messages.conversationId` and the new `chat_conversations` table
         * (see [ChatConversationEntity]'s doc). Critically, this does NOT drop any existing
         * `chat_messages` row -- every row that existed at v16 is folded into a single new
         * `'default-migrated'` conversation so nobody's chat history disappears on upgrade:
         *
         * 1. `ALTER TABLE ... ADD COLUMN conversationId TEXT NOT NULL DEFAULT 'default-migrated'`
         *    backfills every pre-existing row's conversationId in one statement (SQLite applies
         *    the DEFAULT to existing rows for an ALTER TABLE ADD COLUMN), so no separate UPDATE
         *    is needed to link old messages to the new conversation row below.
         * 2. The `chat_conversations` row is only inserted `WHERE EXISTS` at least one
         *    pre-existing message -- a fresh v17 install (or a v16 install that never sent a
         *    message) must NOT get a spurious empty conversation row; `ChatRepository.init`
         *    lazily creates the first real conversation instead (see `ensureActiveConversation`).
         * 3. The derived title mirrors [com.example.data.models.deriveChatTitle]'s own
         *    truncate-at-40-chars-plus-ellipsis convention (`CHAR(8230)` = U+2026 `…`) applied to
         *    the earliest surviving user message, falling back to `'Imported chat'` if the old
         *    history has no user message at all (e.g. only ever produced local-mode replies).
         *    Raw SQL can't strip an attachment-marker prefix the way the Kotlin helper does --
         *    an attachment-prefixed FIRST message is a rare, acceptable edge case here.
         */
        /**
         * v17 -> v18 (Home rebrand, home_rebranding.md §6). Adds the `recent_activity` table
         * backing [RecentActivityEntity] / `RecentActivityRepository`. Additive only: no existing
         * table or row is touched. Column set, `NOT NULL`ness, the composite
         * `(itemType, itemId)` primary key and the `openedAt` index mirror the @Entity exactly so
         * Room's schema validation (and `Migration17To18Test`) accept the migrated table as
         * identical to a fresh v18 install. No backfill by design -- the previous "recents" were
         * fabricated from list order, so there is no real open history to carry over, and
         * inventing one would be exactly the dishonesty this table replaces.
         */
        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recent_activity (" +
                        "itemType TEXT NOT NULL, itemId TEXT NOT NULL, " +
                        "subtype TEXT NOT NULL, title TEXT NOT NULL, preview TEXT NOT NULL, " +
                        "openedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(itemType, itemId))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_activity_openedAt ON recent_activity(openedAt)")
            }
        }

        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN conversationId TEXT NOT NULL DEFAULT 'default-migrated'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_conversationId ON chat_messages(conversationId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_conversations (" +
                        "id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                        "titleIsOverride INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_conversations_updatedAt ON chat_conversations(updatedAt)")
                db.execSQL(
                    "INSERT INTO chat_conversations (id, title, titleIsOverride, createdAt, updatedAt) " +
                        "SELECT 'default-migrated', " +
                        "  COALESCE(" +
                        "    (SELECT CASE WHEN LENGTH(content) > 40 THEN SUBSTR(content, 1, 40) || CHAR(8230) ELSE content END " +
                        "     FROM chat_messages WHERE role = 'user' ORDER BY timestamp ASC LIMIT 1)," +
                        "    'Imported chat'" +
                        "  ), " +
                        "  0, " +
                        "  (SELECT MIN(timestamp) FROM chat_messages), " +
                        "  (SELECT MAX(timestamp) FROM chat_messages) " +
                        "WHERE EXISTS (SELECT 1 FROM chat_messages)"
                )
            }
        }

        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN captureMethod TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE intercept_rules ADD COLUMN cryptoKeyAlias TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE intercept_rules ADD COLUMN cryptoAlgorithm TEXT NOT NULL DEFAULT 'AES/GCM/NoPadding'")
                db.execSQL("ALTER TABLE intercept_rules ADD COLUMN cryptoDirection TEXT NOT NULL DEFAULT 'ENCRYPT'")
                db.execSQL("ALTER TABLE intercept_rules ADD COLUMN cryptoScope TEXT NOT NULL DEFAULT 'BOTH'")
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN isRequestBodyBinary INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN isResponseBodyBinary INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN requestBodyBytes BLOB DEFAULT NULL")
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN responseBodyBytes BLOB DEFAULT NULL")
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN requestBodyBytesTruncated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN responseBodyBytesTruncated INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN pollingAllowCrossOriginRedirect INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN pollingEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN pollingIntervalMs INTEGER NOT NULL DEFAULT 2000")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN pollingMaxAttempts INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN pollingLocationHeader TEXT NOT NULL DEFAULT 'Location'")
            }
        }

        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN apiKeyLocation TEXT NOT NULL DEFAULT 'HEADER'")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockStatusCode INTEGER NOT NULL DEFAULT 200")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockStatusMessage TEXT NOT NULL DEFAULT 'OK'")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockHeaders TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockBody TEXT NOT NULL DEFAULT '{\"mocked\": true}'")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockDelayMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS flow_pipelines (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "description TEXT NOT NULL, triggerType TEXT NOT NULL, " +
                        "status TEXT NOT NULL, lastRunText TEXT NOT NULL, " +
                        "runsCount INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quic_connections ADD COLUMN decodeStatus TEXT NOT NULL DEFAULT 'DETECTED_ONLY'")
                db.execSQL("ALTER TABLE quic_connections ADD COLUMN lifecycleState TEXT NOT NULL DEFAULT 'INITIALIZING'")
                db.execSQL("ALTER TABLE quic_connections ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quic_connections ADD COLUMN clientRandomHex TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE quic_connections ADD COLUMN keylogLabels TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── Section A: transport/packet-core columns ──
                db.execSQL("ALTER TABLE captured_traffic ADD COLUMN connectionId TEXT")
                db.execSQL("ALTER TABLE tls_sessions ADD COLUMN failureReason TEXT")
                db.execSQL("ALTER TABLE tls_sessions ADD COLUMN failedAt INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_captured_traffic_connectionId " +
                        "ON captured_traffic(connectionId)"
                )

                // ── Section B: app-scope tables ──
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notes (" +
                        "id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL, " +
                        "format TEXT NOT NULL, folderId TEXT, folderName TEXT NOT NULL, " +
                        "folderColorHex INTEGER NOT NULL, isPinned INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_isPinned ON notes(isPinned)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS note_folders (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "colorHex INTEGER NOT NULL, emoji TEXT NOT NULL)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_tools (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, operation TEXT NOT NULL, " +
                        "pattern TEXT NOT NULL, replacement TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_messages (" +
                        "id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, timestamp INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp " +
                        "ON chat_messages(timestamp)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_providers (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, baseUrl TEXT NOT NULL, " +
                        "model TEXT NOT NULL, isActive INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                )

                // ── Section C: connect_attempts table (SDK scope's ConnectAttemptEntity) ──
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS connect_attempts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId INTEGER NOT NULL, " +
                        "targetHost TEXT NOT NULL, " +
                        "targetPort INTEGER NOT NULL, " +
                        "clientUid INTEGER, " +
                        "sourceIp TEXT, " +
                        "timestamp INTEGER NOT NULL, " +
                        "negotiatedAlpn TEXT, " +
                        "disposition TEXT NOT NULL, " +
                        "redirectTarget TEXT, " +
                        "bytesToClient INTEGER NOT NULL, " +
                        "bytesToUpstream INTEGER NOT NULL, " +
                        "failureReason TEXT, " +
                        "handshakeState TEXT, " +
                        "finishedAt INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_connect_attempts_sessionId " +
                        "ON connect_attempts(sessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_connect_attempts_targetHost " +
                        "ON connect_attempts(targetHost)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_connect_attempts_timestamp " +
                        "ON connect_attempts(timestamp)"
                )
            }
        }

        /** All thirteen registered migrations v8→v21, exposed for tests and the builder. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
            MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
        )
    }
}
