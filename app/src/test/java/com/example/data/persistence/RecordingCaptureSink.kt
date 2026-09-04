package com.example.data.persistence

import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.GrpcCallEntity
import com.example.data.local.GrpcMessageEntity
import com.example.data.local.Http2StreamEventEntity
import com.example.data.local.SseEventEntity
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.TransactionTimingEntity
import com.example.data.local.WebSocketMessageEntity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * S3 task 20 test double for [CaptureSink]: records every write in memory and hands out
 * already-resolved [RowHandle]s (ids 1, 2, 3, ...) so engine code can be driven synchronously
 * without Room. Children are stamped with their parent's id exactly the way
 * [PersistenceQueue] does, so assertions can join on `trafficId`.
 *
 * [deadParent] mimics a parent whose insert was dropped: [PersistenceQueue] refuses children of a
 * dead handle, so this double does the same and counts them in [droppedChildren].
 */
class RecordingCaptureSink : CaptureSink {
    private val nextId = AtomicLong(1)

    class Row(val id: Long, val entity: CapturedTrafficEntity, val headers: List<TransactionHeaderEntity>, val timings: TransactionTimingEntity?)
    class Patch(val targetId: Long?, val patch: CapturedTrafficEntity, val headers: List<TransactionHeaderEntity>?)

    val rows = CopyOnWriteArrayList<Row>()
    val patches = CopyOnWriteArrayList<Patch>()
    val webSocketMessages = CopyOnWriteArrayList<WebSocketMessageEntity>()
    val sseEvents = CopyOnWriteArrayList<SseEventEntity>()
    val grpcCalls = CopyOnWriteArrayList<GrpcCallEntity>()
    val grpcMessages = CopyOnWriteArrayList<GrpcMessageEntity>()
    val http2StreamEvents = CopyOnWriteArrayList<Http2StreamEventEntity>()
    val droppedChildren = AtomicLong(0)

    override fun transaction(entity: CapturedTrafficEntity, headers: List<TransactionHeaderEntity>, timings: TransactionTimingEntity?): RowHandle {
        val id = nextId.getAndIncrement()
        rows += Row(id, entity, headers.map { it.copy(trafficId = id) }, timings?.copy(trafficId = id))
        return RowHandle.resolved(id, "test-row")
    }

    override fun patchExchange(target: RowHandle, patch: CapturedTrafficEntity, headers: List<TransactionHeaderEntity>?) {
        if (target.isDead) { droppedChildren.incrementAndGet(); return }
        patches += Patch(target.idOrNull, patch, headers)
    }

    override fun webSocketMessage(parent: RowHandle, message: WebSocketMessageEntity) {
        val id = alive(parent) ?: return
        webSocketMessages += message.copy(trafficId = id)
    }

    override fun sseEvents(parent: RowHandle, events: List<SseEventEntity>) {
        if (events.isEmpty()) return
        val id = alive(parent) ?: return
        sseEvents += events.map { it.copy(trafficId = id) }
    }

    override fun grpcCall(parent: RowHandle, call: GrpcCallEntity) {
        val id = alive(parent) ?: return
        // Upsert semantics, like GrpcCallDao.upsert (REPLACE on the trafficId primary key).
        grpcCalls.removeAll { it.trafficId == id }
        grpcCalls += call.copy(trafficId = id)
    }

    override fun grpcMessage(parent: RowHandle, message: GrpcMessageEntity) {
        val id = alive(parent) ?: return
        grpcMessages += message.copy(trafficId = id)
    }

    override fun http2StreamEvents(parent: RowHandle, events: List<Http2StreamEventEntity>) {
        if (events.isEmpty()) return
        val id = alive(parent) ?: return
        http2StreamEvents += events.map { it.copy(trafficId = id) }
    }

    /** The `captured_traffic` row an already-resolved handle points at. */
    fun row(handle: RowHandle): Row = rows.first { it.id == handle.idOrNull }

    private fun alive(parent: RowHandle): Long? {
        if (parent.isDead) { droppedChildren.incrementAndGet(); return null }
        return parent.idOrNull ?: error("RecordingCaptureSink only hands out resolved handles: $parent")
    }

    companion object {
        /** A handle whose insert never produced a row -- children of it must be refused. */
        fun deadParent(): RowHandle = RowHandle.pending("dead").also { it.deferred.completeExceptionally(DroppedWriteException("dead")) }
    }
}
