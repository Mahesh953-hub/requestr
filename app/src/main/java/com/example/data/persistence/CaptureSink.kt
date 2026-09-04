package com.example.data.persistence

import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.GrpcCallEntity
import com.example.data.local.GrpcMessageEntity
import com.example.data.local.Http2StreamEventEntity
import com.example.data.local.SseEventEntity
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.TransactionTimingEntity
import com.example.data.local.WebSocketMessageEntity

/**
 * S3 task 20 -- what a capture engine needs from persistence, and nothing more.
 *
 * Every method is fire-and-forget and never blocks the calling relay/pump thread: the production
 * implementation is [PersistenceQueue], which enqueues onto its single-writer actor. Children
 * reference their parent through the [RowHandle] returned by [transaction], so a WebSocket message
 * can be appended before the parent's insert has committed -- the actor resolves the id in
 * submission order. A parent whose insert was dropped or refused turns every later child into a
 * counted no-op instead of an exception.
 *
 * Engines take a `CaptureSink?` rather than the queue class so unit tests can capture the exact
 * rows a code path produced without spinning up Room ([RecordingCaptureSink] in the test tree).
 */
interface CaptureSink {
    /** Phase 1: the parent `captured_traffic` row with its wire-ordered headers and measured timings. */
    fun transaction(
        entity: CapturedTrafficEntity,
        headers: List<TransactionHeaderEntity> = emptyList(),
        timings: TransactionTimingEntity? = null,
    ): RowHandle

    /**
     * Phase 2: a later revision of the exchange (response of a streamed request, gRPC trailers).
     * `headers`, when non-null, replaces the row's headers of every [com.example.data.local.HeaderKind]
     * present in the list.
     */
    fun patchExchange(target: RowHandle, patch: CapturedTrafficEntity, headers: List<TransactionHeaderEntity>? = null)

    fun webSocketMessage(parent: RowHandle, message: WebSocketMessageEntity)

    fun sseEvents(parent: RowHandle, events: List<SseEventEntity>)

    /** Upsert: called once when the call starts and again with the status when the trailers arrive. */
    fun grpcCall(parent: RowHandle, call: GrpcCallEntity)

    fun grpcMessage(parent: RowHandle, message: GrpcMessageEntity)

    fun http2StreamEvents(parent: RowHandle, events: List<Http2StreamEventEntity>)
}
