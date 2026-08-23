package com.example.interception.quic

import java.io.ByteArrayOutputStream
import java.util.TreeMap

/**
 * Gap-tolerant, retransmission-deduping byte-offset reassembler for a single QUIC stream.
 *
 * QUIC STREAM frames (RFC 9000 §19.8) carry an explicit byte [offset][feed] and a FIN bit, and
 * may arrive out of order, duplicated (retransmissions), or overlapping data already received.
 * This reassembler models one logical stream: it accepts such segments via [feed], deduplicates
 * pure retransmissions, rejects overlapping offsets, tracks the FIN/final-size, and lets the
 * caller [drain] the contiguous byte prefix that has been reassembled in offset order.
 *
 * Out-of-order (not-yet-drainable) segments are held in a [TreeMap] keyed by offset, so the
 * lowest pending offset — the next contiguous candidate — is always reachable in O(log n) and
 * segments need not arrive in order. Every byte range ever accepted (including bytes already
 * drained and delivered) is recorded in `seenRanges`, which is what makes a later retransmission
 * of already-delivered data deduplicate instead of re-buffering.
 *
 * Scope: this is the per-stream reassembly primitive backing HTTP/3 STREAM demultiplexing
 * (RFC 9114). It is deliberately single-threaded and self-contained — no Android, Room, or
 * sibling `quic`-package dependencies — so it can be unit-tested in isolation.
 *
 * Overlap policy (stricter than RFC 9000's "retransmitted bytes must be identical"): a segment
 * whose range is already fully covered by received bytes is a [FeedResult.DUPLICATE] retransmission
 * and is dropped; a segment that *partially* overlaps received bytes (bringing new bytes at
 * positions that collide with old ones) is a [FeedResult.REJECTED_OVERLAP] protocol violation and
 * is dropped; only a segment disjoint from all received bytes is [FeedResult.ACCEPTED].
 */
class QuicStreamReassembler(val streamId: Long) {

    /** Half-open byte range `[start, end)`. Invariant: `start >= 0` and `end >= start`. */
    private data class Range(val start: Long, val end: Long) {
        init {
            require(start >= 0L && end >= start) { "invalid range [$start, $end)" }
        }
    }

    /** Outcome of offering a segment via [feed]. */
    enum class FeedResult {
        /** Segment was disjoint from all received bytes and is now buffered for draining. */
        ACCEPTED,
        /** Segment's range was already fully received — a retransmission, deduplicated (no state change). */
        DUPLICATE,
        /** Segment partially overlaps already-received bytes — overlapping-offset violation, rejected. */
        REJECTED_OVERLAP,
        /** Segment violates the locked final size (conflicting FIN or data beyond final size), rejected. */
        REJECTED_FINAL_SIZE,
    }

    // Out-of-order segments not yet drainable, keyed by byte offset. TreeMap keeps them ordered so
    // the lowest pending offset (the next contiguous candidate) is O(log n) to reach.
    private val pending: TreeMap<Long, ByteArray> = TreeMap()

    // Every byte range ever accepted (including already-drained ones) — the retransmission-dedupe set.
    private val seenRanges: MutableSet<Range> = mutableSetOf()

    // Number of contiguous bytes already drained (the next expected offset).
    private var drainedUpTo: Long = 0L

    // FIN/final-size tracking. lockedFinalSize < 0 means "unknown".
    private var finReceived: Boolean = false
    private var lockedFinalSize: Long = -1L

    /** The next byte offset we expect to receive/drain (== number of bytes already drained). */
    val nextExpectedOffset: Long get() = drainedUpTo

    /** True once a FIN-bearing segment has been accepted. */
    val isFinReceived: Boolean get() = finReceived

    /** True once the stream's final size is known (a FIN has been seen). */
    val hasFinalSize: Boolean get() = lockedFinalSize >= 0L

    /**
     * The locked final size of the stream. Returns `-1` when [hasFinalSize] is false; otherwise the
     * immutable end offset established by the FIN-bearing segment (`offset + length`).
     */
    val finalSize: Long get() = lockedFinalSize

    /** True once every byte up to [finalSize] has been drained and the FIN has been seen. */
    val isComplete: Boolean get() = hasFinalSize && finReceived && drainedUpTo >= lockedFinalSize

    /** Number of out-of-order segments currently buffered (not yet drainable). */
    val pendingSegmentCount: Int get() = pending.size

    /** Number of distinct byte ranges recorded for retransmission dedupe. */
    val seenRangeCount: Int get() = seenRanges.size

    /**
     * Offer a STREAM segment at byte [offset] carrying [data], with FIN flag [isFin].
     *
     * - A range already fully covered by received bytes is a retransmission → [FeedResult.DUPLICATE]
     *   (no state change; this is what dedupes retransmissions of already-delivered data too).
     * - A range that partially overlaps received bytes is an overlapping-offset violation →
     *   [FeedResult.REJECTED_OVERLAP] (no state change).
     * - A range that conflicts with a known final size, or lies at/beyond it, is a final-size
     *   violation → [FeedResult.REJECTED_FINAL_SIZE] (no state change).
     * - Otherwise the range is recorded in `seenRanges` and buffered in `pending`; if [isFin], the
     *   final size is locked to `offset + data.size` → [FeedResult.ACCEPTED].
     *
     * @throws IllegalArgumentException if [offset] is negative.
     */
    fun feed(offset: Long, data: ByteArray, isFin: Boolean): FeedResult {
        require(offset >= 0L) { "offset must be non-negative: $offset" }
        val end = offset + data.size
        val candidateFinal = end // final size implied by a FIN on this segment

        // Final size is immutable once known (RFC 9000 §19.8), and no data may lie at/beyond it.
        if (isFin && hasFinalSize && candidateFinal != lockedFinalSize) {
            return FeedResult.REJECTED_FINAL_SIZE
        }
        if (hasFinalSize && end > lockedFinalSize) {
            return FeedResult.REJECTED_FINAL_SIZE
        }

        // Pure FIN (no bytes): lock/confirm the final size only.
        if (data.isEmpty()) {
            if (isFin) {
                if (finReceived) return FeedResult.DUPLICATE // repeated identical FIN
                lockedFinalSize = candidateFinal
                finReceived = true
            }
            return FeedResult.ACCEPTED
        }

        // Retransmission dedupe vs. overlap validation, measured against the union of all seen
        // ranges (summing per-range overlap handles adjacent ranges correctly without merging).
        val r = Range(offset, end)
        val covered = seenRanges.sumOf { overlap(r, it) }
        if (covered == end - offset) return FeedResult.DUPLICATE       // fully already received
        if (covered > 0L) return FeedResult.REJECTED_OVERLAP           // partial overlap = violation

        // Disjoint: accept. Record the range for future dedupe and buffer the segment for draining.
        seenRanges.add(r)
        pending[offset] = data
        if (isFin) {
            // candidateFinal == lockedFinalSize already validated above (or this FIN sets it first).
            lockedFinalSize = candidateFinal
            finReceived = true
        }
        return FeedResult.ACCEPTED
    }

    /**
     * Pull the contiguous byte prefix that has been reassembled in offset order, starting at
     * [nextExpectedOffset]. Advances [nextExpectedOffset]; a second call with no newly-arrived
     * contiguous data returns an empty array. Out-of-order segments beyond the current gap remain
     * buffered until the gap is filled.
     */
    fun drain(): ByteArray {
        val out = ByteArrayOutputStream()
        var entry = pending.firstEntry()
        while (entry != null && entry.key == drainedUpTo) {
            out.write(entry.value)
            drainedUpTo += entry.value.size
            pending.remove(entry.key)
            entry = pending.firstEntry()
        }
        return out.toByteArray()
    }

    /** Overlap length of half-open ranges [a] and [b]; `0` when they are disjoint or only touch. */
    private fun overlap(a: Range, b: Range): Long =
        maxOf(0L, minOf(a.end, b.end) - maxOf(a.start, b.start))
}
