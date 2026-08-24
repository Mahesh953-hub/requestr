package com.example.interception.quic

import java.io.ByteArrayOutputStream
import java.util.TreeMap

/**
 * Gap-tolerant, retransmission-deduping byte-offset reassembler for a single QUIC stream.
 *
 * QUIC STREAM frames (RFC 9000 §19.8) carry an explicit byte [offset][feed] and a FIN bit, and may
 * arrive out of order, duplicated (retransmissions), or overlapping data already received. This
 * reassembler models one logical stream: it accepts such segments via [feed], deduplicates
 * retransmissions, tracks the FIN/final-size, and lets the caller [drain] the contiguous byte prefix
 * reassembled so far in offset order.
 *
 * **Overlap policy (RFC 9000 §2.2).** A sender may retransmit stream data with *different* framing
 * than the original — "the data sent in STREAM frames … MUST be the same", but the frame boundaries
 * need not be — so a legal retransmission can partially overlap what was already received (e.g.
 * `[0,5)` arrives, then `[3,9)` re-sends the tail and extends it). Such a segment is therefore
 * **accepted for its new bytes only**: the already-covered portion is dropped and only the uncovered
 * remainder is buffered. Rejecting it outright (the previous behaviour) would discard legitimate data
 * and stall the stream forever behind a gap the peer already filled.
 *
 * A partial overlap is only a violation when the overlapping bytes actually *contradict* bytes still
 * buffered — that is a detectable protocol error (or an injection attempt) and yields
 * [FeedResult.REJECTED_OVERLAP]. Overlap against bytes already drained cannot be re-checked (they are
 * no longer held), so those bytes are simply treated as covered.
 *
 * **Bounded tracking.** Received coverage is kept as a *merged, sorted* interval list plus the
 * implicit `[0, drainedUpTo)` prefix, so a normal contiguous stream collapses to zero tracked
 * intervals instead of growing one entry per frame. A peer that sends many small non-contiguous
 * segments (offsets 0, 2, 4, …) still cannot grow this state without limit: once
 * [maxTrackedIntervals] intervals or [maxPendingSegments] buffered segments are in flight, further
 * out-of-order segments are refused with [FeedResult.REJECTED_LIMIT] rather than silently dropping
 * coverage tracking (which would corrupt reassembly) or buffering without bound.
 *
 * Scope: this is the per-stream reassembly primitive backing HTTP/3 STREAM demultiplexing (RFC 9114).
 * It is deliberately single-threaded and self-contained — no Android, Room, or sibling `quic`-package
 * dependencies — so it can be unit-tested in isolation.
 */
class QuicStreamReassembler(
    val streamId: Long,
    private val maxTrackedIntervals: Int = MAX_TRACKED_INTERVALS,
    private val maxPendingSegments: Int = MAX_PENDING_SEGMENTS,
) {

    /** Half-open byte range `[start, end)`. Invariant: `start >= 0` and `end >= start`. */
    private data class Range(val start: Long, val end: Long) {
        init {
            require(start >= 0L && end >= start) { "invalid range [$start, $end)" }
        }

        val length: Long get() = end - start
    }

    /** Outcome of offering a segment via [feed]. */
    enum class FeedResult {
        /**
         * The segment carried at least one byte not already received; those bytes are now buffered for
         * draining. A legal partial retransmission lands here too — only its new bytes are kept.
         */
        ACCEPTED,
        /** Segment's range was already fully received — a retransmission, deduplicated (no state change). */
        DUPLICATE,
        /**
         * Segment's overlapping bytes contradict bytes still buffered for those offsets — a real
         * protocol violation (RFC 9000 §2.2 requires retransmitted data to be identical), rejected.
         */
        REJECTED_OVERLAP,
        /** Segment violates the locked final size (conflicting FIN or data beyond final size), rejected. */
        REJECTED_FINAL_SIZE,
        /**
         * Accepting the segment would exceed this stream's bounded reassembly state
         * ([maxTrackedIntervals] / [maxPendingSegments]), so it is refused rather than buffered. The
         * peer is free to retransmit it once the gap ahead of it has been filled and drained.
         */
        REJECTED_LIMIT,
    }

    // Out-of-order segments not yet drainable, keyed by byte offset. TreeMap keeps them ordered so
    // the lowest pending offset (the next contiguous candidate) is O(log n) to reach.
    private val pending: TreeMap<Long, ByteArray> = TreeMap()

    /**
     * Received coverage **above** [drainedUpTo], as a merged, sorted, disjoint interval list. Bytes
     * below [drainedUpTo] are covered implicitly by the prefix, so a contiguous stream keeps this list
     * empty — that is what bounds the memory this tracking costs.
     */
    private val covered: MutableList<Range> = mutableListOf()

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

    /**
     * Number of tracked coverage intervals above the drained prefix. A fully contiguous stream reports
     * `0`, which is the property that keeps this state bounded on a long-lived stream.
     */
    val seenRangeCount: Int get() = covered.size

    /** Bytes currently buffered in out-of-order segments — the per-stream memory this holds. */
    val bufferedByteCount: Int get() = pending.values.sumOf { it.size }

    /**
     * Offer a STREAM segment at byte [offset] carrying [data], with FIN flag [isFin].
     *
     * - A range already fully covered by received bytes is a retransmission → [FeedResult.DUPLICATE].
     * - A range that partially overlaps received bytes is accepted for its **new** bytes
     *   ([FeedResult.ACCEPTED]) unless the overlapping bytes contradict data still buffered, which is
     *   → [FeedResult.REJECTED_OVERLAP].
     * - A range that conflicts with a known final size, or lies beyond it, is → [FeedResult.REJECTED_FINAL_SIZE].
     * - A range that would exceed this stream's bounded state is → [FeedResult.REJECTED_LIMIT].
     * - Otherwise the new bytes are buffered and coverage recorded → [FeedResult.ACCEPTED].
     *
     * @throws IllegalArgumentException if [offset] is negative.
     */
    fun feed(offset: Long, data: ByteArray, isFin: Boolean): FeedResult {
        require(offset >= 0L) { "offset must be non-negative: $offset" }
        val end = offset + data.size

        // Final size is immutable once known (RFC 9000 §19.8), and no data may lie beyond it.
        if (isFin && hasFinalSize && end != lockedFinalSize) return FeedResult.REJECTED_FINAL_SIZE
        if (hasFinalSize && end > lockedFinalSize) return FeedResult.REJECTED_FINAL_SIZE

        // Pure FIN (no bytes): lock/confirm the final size only.
        if (data.isEmpty()) {
            if (isFin) {
                if (finReceived) return FeedResult.DUPLICATE // repeated identical FIN
                lockedFinalSize = end
                finReceived = true
            }
            return FeedResult.ACCEPTED
        }

        val fresh = uncoveredParts(offset, end)
        if (fresh.isEmpty()) return FeedResult.DUPLICATE // wholly already received

        val freshBytes = fresh.sumOf { it.length }
        if (freshBytes < data.size.toLong() && contradictsBufferedBytes(offset, data)) {
            // Overlapping bytes disagree with bytes we still hold for those offsets: RFC 9000 §2.2
            // requires retransmitted stream data to be identical, so this is a real violation.
            return FeedResult.REJECTED_OVERLAP
        }

        // Bounded state: refuse rather than buffer without limit or stop tracking coverage.
        if (pending.size + fresh.size > maxPendingSegments) return FeedResult.REJECTED_LIMIT
        if (covered.size + fresh.size > maxTrackedIntervals && !wouldMergeIntoExisting(fresh)) {
            return FeedResult.REJECTED_LIMIT
        }

        for (part in fresh) {
            val from = (part.start - offset).toInt()
            val to = (part.end - offset).toInt()
            pending[part.start] = data.copyOfRange(from, to)
            addCovered(part)
        }
        if (isFin) {
            lockedFinalSize = end
            finReceived = true
        }
        return FeedResult.ACCEPTED
    }

    /**
     * Pull the contiguous byte prefix that has been reassembled in offset order, starting at
     * [nextExpectedOffset]. Advances [nextExpectedOffset]; a second call with no newly-arrived
     * contiguous data returns an empty array. Out-of-order segments beyond the current gap remain
     * buffered until the gap is filled. Draining also collapses the coverage intervals it absorbs into
     * the implicit prefix, so tracked state shrinks as the stream progresses.
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
        if (out.size() > 0) collapseCoveredBelowPrefix()
        return out.toByteArray()
    }

    /** The sub-ranges of `[start, end)` not yet covered by the drained prefix or [covered]. */
    private fun uncoveredParts(start: Long, end: Long): List<Range> {
        val out = mutableListOf<Range>()
        var cursor = maxOf(start, drainedUpTo)
        if (cursor >= end) return out
        for (c in covered) {
            if (c.end <= cursor) continue
            if (c.start >= end) break
            if (c.start > cursor) out.add(Range(cursor, minOf(c.start, end)))
            cursor = maxOf(cursor, c.end)
            if (cursor >= end) return out
        }
        if (cursor < end) out.add(Range(cursor, end))
        return out
    }

    /**
     * True when [data] disagrees with bytes still buffered at the same offsets. Only buffered segments
     * can be checked — already-drained bytes have been handed to the caller and are gone — so this is a
     * best-effort detector of a genuine contradiction, never a guess about data we no longer hold.
     */
    private fun contradictsBufferedBytes(offset: Long, data: ByteArray): Boolean {
        val end = offset + data.size
        for ((bufferedOffset, buffered) in pending) {
            if (bufferedOffset >= end) break
            val bufferedEnd = bufferedOffset + buffered.size
            if (bufferedEnd <= offset) continue
            val from = maxOf(offset, bufferedOffset)
            val to = minOf(end, bufferedEnd)
            for (position in from until to) {
                if (data[(position - offset).toInt()] != buffered[(position - bufferedOffset).toInt()]) return true
            }
        }
        return false
    }

    /** Inserts [range] into [covered], merging with any interval it touches so the list stays disjoint. */
    private fun addCovered(range: Range) {
        var start = range.start
        var end = range.end
        val iterator = covered.iterator()
        while (iterator.hasNext()) {
            val existing = iterator.next()
            if (existing.end < start || existing.start > end) continue // strictly disjoint, keep
            start = minOf(start, existing.start)
            end = maxOf(end, existing.end)
            iterator.remove()
        }
        val merged = Range(start, end)
        val insertAt = covered.indexOfFirst { it.start > merged.start }
        if (insertAt < 0) covered.add(merged) else covered.add(insertAt, merged)
    }

    /** True when every part of [fresh] would merge into an existing interval, adding no new entry. */
    private fun wouldMergeIntoExisting(fresh: List<Range>): Boolean =
        fresh.all { part -> covered.any { it.end >= part.start && it.start <= part.end } }

    /** Drops (or trims) coverage intervals absorbed by the drained prefix, keeping the list minimal. */
    private fun collapseCoveredBelowPrefix() {
        val iterator = covered.listIterator()
        while (iterator.hasNext()) {
            val range = iterator.next()
            when {
                range.end <= drainedUpTo -> iterator.remove()
                range.start < drainedUpTo -> iterator.set(Range(drainedUpTo, range.end))
                else -> Unit
            }
        }
    }

    companion object {
        /**
         * Maximum coverage intervals tracked above the drained prefix. A contiguous stream needs none;
         * this only bounds a peer that sends many non-contiguous segments.
         */
        const val MAX_TRACKED_INTERVALS = 64

        /** Maximum out-of-order segments buffered before further ones are refused. */
        const val MAX_PENDING_SEGMENTS = 64
    }
}
