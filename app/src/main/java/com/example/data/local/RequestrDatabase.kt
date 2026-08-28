package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        ConnectAttemptEntity::class,
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
    version = 14,
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
    abstract fun connectAttemptDao(): ConnectAttemptDao

    companion object {
        @Volatile
        private var INSTANCE: RequestrDatabase? = null

        fun get(context: Context): RequestrDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RequestrDatabase::class.java,
                    "requestr.db",
                )
                    // Real migration path as of v9 -- v8 now preserves all user data via
                    // MIGRATION_8_9. But `main` has shipped released builds at versions 1-7
                    // (confirmed via `git log -p` on this file's `version =` field) with
                    // exportSchema = false the whole time, so there is no exported schema JSON
                    // for any of those versions to author a faithful migration against -- only
                    // 8.json and 9.json exist under app/schemas/. Rather than crash every
                    // pre-8 install with Room's "A migration from N to 9 was required but not
                    // found" IllegalStateException on every launch, fall back to destructive
                    // recreation *only* for those specific unsupported legacy versions (1-7).
                    // This wipes captured traffic/saved requests/flow graphs for pre-8 users --
                    // real, disclosed data loss (see AUDIT_REPORT.md/TODO_v5.md) -- but it is
                    // strictly better than a crash loop, and it does not touch the v8->v9 path,
                    // which stays a real, non-destructive migration.
                    .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .fallbackToDestructiveMigrationFrom(/* dropAllTables = */ true, 1, 2, 3, 4, 5, 6, 7)
                    .build()
                    .also { INSTANCE = it }
            }

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
                // The mockBody default is spelled out here to match
                // ApiRequestItem.mockBody/SavedApiRequestEntity.mockBody exactly, so an upgraded
                // request and a freshly-created one behave identically once mock mode is enabled.
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockBody TEXT NOT NULL DEFAULT '{\"mocked\": true}'")
                db.execSQL("ALTER TABLE saved_api_requests ADD COLUMN mockDelayMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v10 -> v11 (transplant slice 11). Adds the `flow_pipelines` table for
         * multi-flow persistence. Existing `flow_nodes`/`flow_connections` rows
         * keep their `flowId` column (already present since v5) — they are
         * associated with the default pipeline seeded on first post-migration
         * launch by [FlowRepository.init].
         */
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
                // Kotlin default values on the @Entity (e.g. `sourceIp: String? = null`,
                // `timestamp: Long = System.currentTimeMillis()`) are NOT database defaults --
                // Room only uses them when the field is omitted from an object at insert time,
                // so no SQL DEFAULT clause is added here for any column.
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
    }
}
