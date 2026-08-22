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
    version = 9,
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
                    // Real migration path as of v9 -- no more fallbackToDestructiveMigration.
                    // Upgrading from v8 now preserves all user data; before this change every
                    // version bump wiped every table.
                    .addMigrations(MIGRATION_8_9)
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
         */
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
            }
        }
    }
}
