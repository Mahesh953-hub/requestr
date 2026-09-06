package com.example.interception.rules.stages

import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * S4 task 30: a byte-rate token bucket for the relay copy loops.
 *
 * `rateBytesPerSecond` tokens accrue continuously up to [capacityBytes]; [acquire] takes tokens
 * for a chunk and, when the bucket is in debt, blocks (via the injectable [sleep]) for exactly as
 * long as it takes the debt to be repaid at the configured rate. Debt-based accounting (tokens
 * may go negative) keeps the long-run throughput exactly at the rate regardless of chunk size or
 * timer granularity -- a late wake-up is compensated on the next refill instead of being lost.
 *
 * Thread-safe: one bucket may be shared by several writers (e.g. a pipelined response and a
 * blind-relay chunk on the same direction of one connection). The clock is monotonic nanos and
 * injectable so [ThrottleStageTest] drives a virtual timeline for the G7 ±20 % check without
 * really waiting for a 10 MB GPRS download.
 */
class TokenBucket(
    val rateBytesPerSecond: Long,
    capacityBytes: Long = defaultCapacity(rateBytesPerSecond),
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = ::sleepNanos,
) {
    init {
        require(rateBytesPerSecond > 0) { "rateBytesPerSecond must be positive" }
    }

    val capacityBytes: Long = max(1L, capacityBytes)

    private val lock = Any()
    private var tokens: Double = this.capacityBytes.toDouble()
    private var lastRefillNanos: Long = clock()

    /** Bytes currently available without waiting (never negative; for diagnostics/tests). */
    val availableBytes: Long
        get() = synchronized(lock) { refill(); max(0L, tokens.toLong()) }

    /**
     * Takes [bytes] tokens, sleeping as needed. Chunks larger than the capacity are taken in
     * capacity-sized slices so a single huge write is paced rather than admitted in one burst.
     */
    fun acquire(bytes: Int) {
        if (bytes <= 0) return
        var remaining = bytes.toLong()
        while (remaining > 0) {
            val take = min(remaining, capacityBytes)
            val waitNanos = synchronized(lock) { reserve(take) }
            if (waitNanos > 0) sleep(waitNanos)
            remaining -= take
        }
    }

    /** Nanoseconds the caller must wait before the reserved [n] bytes are "paid for". */
    private fun reserve(n: Long): Long {
        refill()
        tokens -= n.toDouble()
        if (tokens >= 0) return 0L
        val debt = -tokens
        return (debt * NANOS_PER_SECOND / rateBytesPerSecond).toLong().coerceAtLeast(1L)
    }

    private fun refill() {
        val now = clock()
        val elapsed = now - lastRefillNanos
        if (elapsed > 0) {
            tokens = min(capacityBytes.toDouble(), tokens + elapsed * rateBytesPerSecond / NANOS_PER_SECOND)
            lastRefillNanos = now
        }
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** ~100 ms worth of bytes, floor 1 KiB, cap 256 KiB -- small enough to feel continuous. */
        fun defaultCapacity(rateBytesPerSecond: Long): Long =
            (rateBytesPerSecond / 10).coerceIn(1024L, 256L * 1024)

        fun sleepNanos(nanos: Long) {
            if (nanos <= 0) return
            Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
        }
    }
}

/**
 * An [OutputStream] that pays for every byte at [bucket] before forwarding it. Large writes are
 * sliced into capacity-sized pieces, each flushed immediately, so the receiver sees a smooth
 * trickle rather than one burst followed by a long stall.
 */
class ThrottledOutputStream(private val delegate: OutputStream, private val bucket: TokenBucket) : OutputStream() {
    override fun write(b: Int) {
        bucket.acquire(1)
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        val slice = bucket.capacityBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        while (remaining > 0) {
            val n = min(remaining, slice)
            bucket.acquire(n)
            delegate.write(b, offset, n)
            if (remaining > n) delegate.flush()
            offset += n
            remaining -= n
        }
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
