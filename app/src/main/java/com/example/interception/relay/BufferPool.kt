package com.example.interception.relay

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pooled copy buffers plus the per-flow per-direction accumulation cap (master plan §7 task 7,
 * gate G3).
 *
 * [acquire]/[release] hand out fixed-size `ByteArray`s (MTU + headroom, rounded to
 * [DEFAULT_BUFFER_SIZE]) so the relay copy loops stop allocating a fresh 8 KiB array per
 * connection leg; the pool is bounded by [maxPooled] so a burst cannot pin memory forever.
 *
 * [newFlowBuffer] returns a [FlowBuffer]: an append-only accumulator for one direction of one
 * flow (the "bytes parsed so far" buffer of the HTTP/1.x inspecting relay). Its in-memory portion
 * is capped at [flowCapBytes] (clamped to 512 KiB..2 MiB, default 1 MiB); bytes beyond the cap
 * spill to a temp file in [spillDir] instead of growing the heap, so twenty concurrent slow flows
 * cannot exceed `20 x cap` of relay heap. If no spill directory is available the buffer refuses
 * (returns `false` from [FlowBuffer.append]) rather than exceed the cap silently.
 */
class BufferPool(
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val maxPooled: Int = DEFAULT_MAX_POOLED,
    flowCapBytes: Int = DEFAULT_FLOW_CAP_BYTES,
    private val spillDir: File? = null,
) {
    val flowCapBytes: Int = flowCapBytes.coerceIn(MIN_FLOW_CAP_BYTES, MAX_FLOW_CAP_BYTES)

    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private val pooled = AtomicInteger()
    private val allocated = AtomicInteger()
    private val spills = AtomicInteger()

    fun acquire(): ByteArray {
        val b = pool.poll()
        if (b != null) {
            pooled.decrementAndGet()
            return b
        }
        allocated.incrementAndGet()
        return ByteArray(bufferSize)
    }

    fun release(buffer: ByteArray) {
        if (buffer.size != bufferSize) return
        if (pooled.get() >= maxPooled) return
        pooled.incrementAndGet()
        pool.offer(buffer)
    }

    /** Buffers currently parked in the pool. */
    val pooledCount: Int get() = pooled.get()

    /** Total fresh allocations (a rising number under steady load means the pool is too small). */
    val allocatedCount: Int get() = allocated.get()

    /** Number of flow buffers that had to spill to disk so far. */
    val spillCount: Int get() = spills.get()

    fun newFlowBuffer(): FlowBuffer = FlowBuffer(flowCapBytes, spillDir) { spills.incrementAndGet() }

    /**
     * One direction of one flow. Not thread-safe: each relay leg owns exactly one.
     *
     * The in-memory tail always holds the most recent bytes up to the cap; older bytes spill to
     * disk in order, so [toByteArray] returns the full logical content (spilled prefix + memory
     * tail) for callers that need to re-parse, while [size] and [inMemorySize] let the owner see
     * how far over the cap the flow has gone.
     */
    class FlowBuffer internal constructor(
        val capBytes: Int,
        private val spillDir: File?,
        private val onSpill: () -> Unit,
    ) : java.io.Closeable {
        private val memory = ByteArrayOutputStream()
        private var spillFile: File? = null
        private var spillOut: OutputStream? = null
        private var spilledBytes: Long = 0

        val inMemorySize: Int get() = memory.size()
        val size: Long get() = spilledBytes + memory.size()
        val isSpilled: Boolean get() = spillFile != null

        /**
         * Appends [len] bytes. Returns `false` if the cap is exceeded and no spill directory is
         * available -- the caller must then stop accumulating (e.g. switch to blind relay).
         */
        fun append(src: ByteArray, off: Int = 0, len: Int = src.size - off): Boolean {
            if (len <= 0) return true
            if (memory.size() + len <= capBytes) {
                memory.write(src, off, len)
                return true
            }
            val dir = spillDir ?: return false
            if (spillOut == null) {
                if (!dir.isDirectory && !dir.mkdirs()) return false
                val f = File.createTempFile("flow-", ".spill", dir)
                spillFile = f
                spillOut = FileOutputStream(f).buffered()
                onSpill()
            }
            // Spill the current memory contents in order, then keep the new bytes in memory.
            val out = spillOut!!
            memory.writeTo(out)
            spilledBytes += memory.size()
            memory.reset()
            if (len > capBytes) {
                out.write(src, off, len)
                spilledBytes += len
            } else {
                memory.write(src, off, len)
            }
            return true
        }

        /** Discards everything (memory and spill), keeping the buffer usable. */
        fun reset() {
            memory.reset()
            closeSpill()
            spilledBytes = 0
        }

        /** Full logical content (spilled prefix followed by the memory tail). */
        fun toByteArray(): ByteArray {
            val f = spillFile ?: return memory.toByteArray()
            spillOut?.flush()
            val prefix = f.readBytes()
            val tail = memory.toByteArray()
            return prefix + tail
        }

        override fun close() {
            memory.reset()
            closeSpill()
        }

        private fun closeSpill() {
            runCatching { spillOut?.close() }
            spillOut = null
            spillFile?.let { runCatching { it.delete() } }
            spillFile = null
        }
    }

    companion object {
        /** MTU (1500) + headroom, rounded up to a page-friendly 8 KiB. */
        const val DEFAULT_BUFFER_SIZE = 8192
        const val DEFAULT_MAX_POOLED = 256
        const val MIN_FLOW_CAP_BYTES = 512 * 1024
        const val MAX_FLOW_CAP_BYTES = 2 * 1024 * 1024
        const val DEFAULT_FLOW_CAP_BYTES = 1024 * 1024
    }
}
