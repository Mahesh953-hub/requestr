package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * S3 task 20 -- Room v20 protocol event tables (plan §4, v20 row).
 *
 * Every table here is a child of one `captured_traffic` row with `ON DELETE CASCADE`:
 *
 *  - a WebSocket connection's parent is the `101 Switching Protocols` upgrade exchange;
 *  - an SSE stream's parent is the `text/event-stream` response;
 *  - a gRPC call's parent is one `captured_traffic` row per HTTP/2 stream (`protocol = "gRPC"`),
 *    described by exactly one `grpc_calls` row;
 *  - HTTP/2 frame events hang off whichever transaction their stream produced.
 *
 * Before v20 each WebSocket frame and each gRPC message was flattened into its own
 * `captured_traffic` row (`method = "WS→"/"WS←"`, synthetic `x-grpc-*` headers). v21 (task 21)
 * folds those legacy rows into these tables; the writers in `MitmProxyEngine` / `CaptureEngine`
 * stop producing them as of this version.
 */

/** Who sent a message on a bidirectional stream. */
enum class MessageDirection {
    /** Client -> server (request side). */
    CLIENT,
    /** Server -> client (response side). */
    SERVER,
}

/**
 * One reassembled WebSocket message (RFC 6455 §5.4: fragments already joined). Text payloads are
 * stored as [payloadText] capped at [WebSocketMessageEntity.MAX_TEXT_CHARS] characters; binary
 * payloads byte-exact in [payloadBytes] up to `MAX_STORED_BODY_BYTES`. [payloadTruncated] is the
 * honest marker when either cap applied; [sizeBytes] is always the real wire size.
 */
@Entity(
    tableName = "websocket_messages",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trafficId", "frameIndex"]),
        Index(value = ["timestampMs"]),
    ],
)
data class WebSocketMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trafficId: Long,
    /** `host:port` (or the tun stream key) of the carrying TCP connection. */
    val connectionId: String,
    val direction: MessageDirection,
    /** RFC 6455 opcode of the first frame (1 text, 2 binary, 8 close, 9 ping, 10 pong). */
    val opcode: Int,
    val isText: Boolean,
    val payloadText: String? = null,
    val payloadBytes: ByteArray? = null,
    val payloadTruncated: Boolean = false,
    /** Real payload length on the wire, regardless of what was stored. */
    val sizeBytes: Long,
    val timestampMs: Long,
    /** 0-based position within the connection, both directions interleaved. */
    val frameIndex: Int,
) {
    companion object {
        const val MAX_TEXT_CHARS = 4_096
    }
}

/** One `text/event-stream` event (WHATWG HTML §9.2.6) parsed from an SSE response body. */
@Entity(
    tableName = "sse_events",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trafficId", "eventIndex"]),
        Index(value = ["timestampMs"]),
    ],
)
data class SseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trafficId: Long,
    /** `event:` field; NULL when the stream used the default `message` type. */
    val eventName: String? = null,
    /** Joined `data:` lines. */
    val data: String,
    /** `id:` field. */
    val eventId: String? = null,
    /** `retry:` field in milliseconds. */
    val retryMs: Long? = null,
    val timestampMs: Long,
    /** 0-based position within the stream. */
    val eventIndex: Int,
)

/**
 * One gRPC call = one HTTP/2 stream = one `captured_traffic` parent row. Status arrives in the
 * trailers, so [grpcStatus]/[grpcMessage] are NULL until the stream ends and the writer upserts.
 */
@Entity(
    tableName = "grpc_calls",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GrpcCallEntity(
    @PrimaryKey val trafficId: Long,
    /** Fully-qualified service, e.g. `my.pkg.UserService`. */
    val service: String,
    /** RPC name, e.g. `GetUser`. */
    val method: String,
    /** `proto`, `json`, ... from `content-type: application/grpc+<encoding>`. */
    val encoding: String,
    /** Message type hint when one was advertised; empty when unknown. */
    val messageType: String = "",
    /** HTTP/2 stream id on the carrying connection. */
    val streamId: Int,
    val grpcStatus: Int? = null,
    val grpcMessage: String? = null,
)

/**
 * One length-prefixed gRPC message (gRPC-over-HTTP/2 §"Length-Prefixed-Message"). Byte-exact
 * payload up to `MAX_STORED_BODY_BYTES`; [decodedText] is the protobuf wire-format field tree
 * when the message was not compressed and decoding succeeded.
 */
@Entity(
    tableName = "grpc_messages",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trafficId", "messageIndex"]),
        Index(value = ["streamId"]),
        Index(value = ["timestampMs"]),
    ],
)
data class GrpcMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trafficId: Long,
    val streamId: Int,
    val direction: MessageDirection,
    /** The 1-byte compressed flag of the message header. */
    val compressed: Boolean,
    /** 0-based position within the call, both directions interleaved. */
    val messageIndex: Int,
    val payload: ByteArray? = null,
    val payloadTruncated: Boolean = false,
    val decodedText: String? = null,
    /** Message length from the 4-byte prefix (payload only, excluding the 5-byte header). */
    val sizeBytes: Long,
    val timestampMs: Long,
)

/**
 * One HTTP/2 frame observed on a stream (RFC 9113 §6), in wire order. Connection-level frames
 * (stream 0: SETTINGS, PING, GOAWAY) are not persisted here -- they belong to no transaction.
 */
@Entity(
    tableName = "http2_stream_events",
    foreignKeys = [
        ForeignKey(
            entity = CapturedTrafficEntity::class,
            parentColumns = ["id"],
            childColumns = ["trafficId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trafficId", "id"]),
        Index(value = ["streamId"]),
        Index(value = ["timestampMs"]),
    ],
)
data class Http2StreamEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trafficId: Long,
    val connectionId: String,
    val streamId: Int,
    val direction: MessageDirection,
    /** RFC 9113 frame type byte (0 DATA, 1 HEADERS, 3 RST_STREAM, 8 WINDOW_UPDATE, ...). */
    val frameType: Int,
    /** RFC 9113 flags byte as received. */
    val flags: Int,
    val payloadLength: Int,
    val timestampMs: Long,
    /** Short human note (`END_STREAM`, `RST_STREAM CANCEL`, ...) or NULL. */
    val note: String? = null,
)
