package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * S3 task 20 -- v19 -> v20, the protocol event tables (plan §4, v20 row).
 *
 * Purely additive: five new CASCADE children of `captured_traffic` (`websocket_messages`,
 * `sse_events`, `grpc_calls`, `grpc_messages`, `http2_stream_events`) and their indices. No
 * existing table or row is touched. The legacy flattened `WS→`/`WS←` and `x-grpc-*` rows are
 * left exactly where they are -- folding them into these tables is v21's job (task 21), which
 * runs as its own migration so a v20 install never sees a half-converted database.
 *
 * DDL mirrors what Room generates for the entities in [ProtocolEntities.kt] (copied from the
 * exported `20.json`) so `MigrationTestHelper.runMigrationsAndValidate` accepts the migrated
 * schema. Same conventions as [MIGRATION_18_19]: `IF NOT EXISTS`, indices as separate statements.
 */
val MIGRATION_19_20: Migration = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // -- websocket_messages --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS websocket_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "trafficId INTEGER NOT NULL, connectionId TEXT NOT NULL, direction TEXT NOT NULL, " +
            "opcode INTEGER NOT NULL, isText INTEGER NOT NULL, payloadText TEXT, payloadBytes BLOB, " +
            "payloadTruncated INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, " +
            "timestampMs INTEGER NOT NULL, frameIndex INTEGER NOT NULL, " +
            "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_websocket_messages_trafficId_frameIndex ON websocket_messages (trafficId, " +
            "frameIndex)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_websocket_messages_timestampMs ON websocket_messages (timestampMs)"
        )

        // -- sse_events --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sse_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "trafficId INTEGER NOT NULL, eventName TEXT, data TEXT NOT NULL, eventId TEXT, " +
            "retryMs INTEGER, timestampMs INTEGER NOT NULL, eventIndex INTEGER NOT NULL, " +
            "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sse_events_trafficId_eventIndex ON sse_events (trafficId, " +
            "eventIndex)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sse_events_timestampMs ON sse_events (timestampMs)"
        )

        // -- grpc_calls --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS grpc_calls (trafficId INTEGER NOT NULL, service TEXT NOT NULL, " +
            "method TEXT NOT NULL, encoding TEXT NOT NULL, messageType TEXT NOT NULL, " +
            "streamId INTEGER NOT NULL, grpcStatus INTEGER, grpcMessage TEXT, PRIMARY KEY(trafficId), " +
            "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )

        // -- grpc_messages --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS grpc_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "trafficId INTEGER NOT NULL, streamId INTEGER NOT NULL, direction TEXT NOT NULL, " +
            "compressed INTEGER NOT NULL, messageIndex INTEGER NOT NULL, payload BLOB, " +
            "payloadTruncated INTEGER NOT NULL, decodedText TEXT, sizeBytes INTEGER NOT NULL, " +
            "timestampMs INTEGER NOT NULL, " +
            "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_grpc_messages_trafficId_messageIndex ON grpc_messages (trafficId, " +
            "messageIndex)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_grpc_messages_streamId ON grpc_messages (streamId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_grpc_messages_timestampMs ON grpc_messages (timestampMs)"
        )

        // -- http2_stream_events --
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS http2_stream_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "trafficId INTEGER NOT NULL, connectionId TEXT NOT NULL, streamId INTEGER NOT NULL, " +
            "direction TEXT NOT NULL, frameType INTEGER NOT NULL, flags INTEGER NOT NULL, " +
            "payloadLength INTEGER NOT NULL, timestampMs INTEGER NOT NULL, note TEXT, " +
            "FOREIGN KEY(trafficId) REFERENCES captured_traffic(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_http2_stream_events_trafficId_id ON http2_stream_events (trafficId, " +
            "id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_http2_stream_events_streamId ON http2_stream_events (streamId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_http2_stream_events_timestampMs ON http2_stream_events (timestampMs)"
        )
    }
}
