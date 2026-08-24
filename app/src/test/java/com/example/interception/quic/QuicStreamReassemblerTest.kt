package com.example.interception.quic

import org.junit.Assert.*
import org.junit.Test

/**
 * Focused unit tests for [QuicStreamReassembler] — the per-stream, byte-offset reassembly
 * primitive that backs HTTP/3 STREAM demultiplexing (RFC 9114 over RFC 9000 §19.8).
 *
 * Covers each required behaviour in isolation:
 *  - byte-offset segment insertion via [QuicStreamReassembler.feed]
 *  - contiguous [QuicStreamReassembler.drain] of in-order and out-of-order (gapped) segments
 *  - overlap validation: a legal partial retransmission contributes its new bytes; only bytes that
 *    contradict still-buffered data are rejected
 *  - retransmission dedupe via `seenRanges` (exact retransmissions, already-drained retransmissions,
 *    and adjacent-range retransmissions that span multiple stored ranges)
 *  - FIN / final-size tracking (final size locked, completion, conflicting-FIN and beyond-final-size
 *    rejection, pure-FIN completion)
 *
 * The reassembler is pure JDK (TreeMap + ByteArray), so these run without Robolectric/Android.
 */
class QuicStreamReassemblerTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
    private fun str(b: ByteArray): String = String(b, Charsets.US_ASCII)

    @Test
    fun `in-order segments drain contiguously in offset order`() {
        val r = QuicStreamReassembler(streamId = 42L)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("abc"), isFin = false))
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(3, bytes("def"), isFin = false))

        assertEquals("abcdef", str(r.drain()))
        assertEquals(6L, r.nextExpectedOffset)
        assertEquals(0, r.pendingSegmentCount)
    }

    @Test
    fun `out-of-order segments are held in TreeMap until the gap fills then drain in order`() {
        val r = QuicStreamReassembler(streamId = 1L)

        // Tail half arrives first — a gap at offset 0 blocks the drain.
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(3, bytes("def"), isFin = false))
        assertEquals(0, str(r.drain()).length)              // nothing contiguous yet
        assertEquals(1, r.pendingSegmentCount)              // held in the TreeMap
        assertEquals(0L, r.nextExpectedOffset)

        // Gap filler arrives; now the whole prefix is contiguous.
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("abc"), isFin = false))
        assertEquals("abcdef", str(r.drain()))
        assertEquals(6L, r.nextExpectedOffset)
        assertEquals(0, r.pendingSegmentCount)
    }

    @Test
    fun `multiple drain calls advance across a gap once it is filled`() {
        val r = QuicStreamReassembler(streamId = 7L)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(6, bytes("gh"), isFin = false))
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("ab"), isFin = false))
        assertEquals("ab", str(r.drain()))                  // drains [0,2), stops at the 2..6 gap
        assertEquals(2L, r.nextExpectedOffset)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(2, bytes("cdef"), isFin = false))
        assertEquals("cdefgh", str(r.drain()))              // gap filled → rest drains in order
        assertEquals(8L, r.nextExpectedOffset)
    }

    @Test
    fun `an exact retransmission is deduplicated and never re-buffered`() {
        val r = QuicStreamReassembler(streamId = 2L)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("abc"), isFin = false))
        val seenBefore = r.seenRangeCount
        val pendingBefore = r.pendingSegmentCount

        assertEquals(QuicStreamReassembler.FeedResult.DUPLICATE, r.feed(0, bytes("abc"), isFin = false))
        assertEquals(seenBefore, r.seenRangeCount)          // no new range recorded
        assertEquals(pendingBefore, r.pendingSegmentCount)  // not re-buffered

        assertEquals("abc", str(r.drain()))                 // delivered exactly once
    }

    @Test
    fun `a retransmission of already-drained data is still deduplicated`() {
        val r = QuicStreamReassembler(streamId = 3L)

        r.feed(0, bytes("abc"), isFin = false)
        assertEquals("abc", str(r.drain()))                 // delivered; range stays in seenRanges

        assertEquals(QuicStreamReassembler.FeedResult.DUPLICATE, r.feed(0, bytes("abc"), isFin = false))
        assertEquals(0, str(r.drain()).length)              // nothing new to deliver
    }

    @Test
    fun `a retransmission spanning adjacent stored ranges is deduplicated via the union`() {
        val r = QuicStreamReassembler(streamId = 4L)

        r.feed(0, bytes("ab"), isFin = false)   // [0,2)
        r.feed(2, bytes("cd"), isFin = false)   // [2,4) — adjacent, not overlapping

        // One retransmitted frame covering the whole contiguous union [0,4) must dedupe, even though
        // no single stored range contains it — proves the dedupe measures the union, not one range.
        assertEquals(QuicStreamReassembler.FeedResult.DUPLICATE, r.feed(0, bytes("abcd"), isFin = false))
        assertEquals("abcd", str(r.drain()))
    }

    @Test
    fun `a partial overlap whose bytes contradict buffered data is rejected`() {
        val r = QuicStreamReassembler(streamId = 5L)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("abcde"), isFin = false)) // [0,5)
        val seenBefore = r.seenRangeCount

        // [3,6) overlaps [0,5) on [3,5), where the buffered bytes are "de" but this segment claims
        // "XY". RFC 9000 §2.2 requires retransmitted stream data to be identical, so this is a real
        // protocol violation (or an injection attempt) and is rejected outright.
        assertEquals(QuicStreamReassembler.FeedResult.REJECTED_OVERLAP, r.feed(3, bytes("XYZ"), isFin = false))
        assertEquals(seenBefore, r.seenRangeCount)          // rejected range not recorded

        assertEquals("abcde", str(r.drain()))               // only the accepted segment delivered
        assertEquals(5L, r.nextExpectedOffset)
    }

    @Test
    fun `a legal partial retransmission is accepted for its new bytes only`() {
        // RFC 9000 §2.2: a sender may re-frame stream data on retransmission, so a legal segment can
        // overlap what was already received and extend beyond it. Rejecting it (the previous behaviour)
        // discarded real data and could stall the stream behind a gap the peer had already filled.
        val r = QuicStreamReassembler(streamId = 50L)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("abcde"), isFin = false)) // [0,5)
        // [3,9) repeats "de" identically and adds "fghi".
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(3, bytes("defghi"), isFin = false))

        assertEquals("abcdefghi", str(r.drain()))
        assertEquals(9L, r.nextExpectedOffset)
        assertEquals("coverage collapses into the drained prefix", 0, r.seenRangeCount)
    }

    @Test
    fun `a partial retransmission over already-drained bytes still contributes its new bytes`() {
        // Once bytes are drained they are no longer held, so an overlap against them cannot be
        // re-verified; it is treated as covered and only the genuinely new remainder is buffered.
        val r = QuicStreamReassembler(streamId = 51L)

        r.feed(0, bytes("abcd"), isFin = false)
        assertEquals("abcd", str(r.drain()))

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(2, bytes("cdef"), isFin = false))
        assertEquals("ef", str(r.drain()))
        assertEquals(6L, r.nextExpectedOffset)
    }

    @Test
    fun `an overlapping retransmission that fills a gap unblocks the stream`() {
        val r = QuicStreamReassembler(streamId = 52L)

        r.feed(4, bytes("efgh"), isFin = false)             // [4,8) — out of order, gap at [0,4)
        assertEquals("nothing drainable while the gap is open", 0, str(r.drain()).length)

        // The peer re-frames and re-sends from 0, overlapping the buffered tail identically.
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("abcdefgh"), isFin = true))

        assertEquals("abcdefgh", str(r.drain()))
        assertTrue(r.isComplete)
    }

    @Test
    fun `coverage tracking stays bounded and refuses further out-of-order segments at the limit`() {
        // A peer sending many non-contiguous segments must not grow coverage/buffer state without
        // limit. Past the bound the reassembler refuses rather than dropping coverage tracking (which
        // would corrupt reassembly) or buffering unboundedly.
        val r = QuicStreamReassembler(streamId = 53L, maxTrackedIntervals = 4, maxPendingSegments = 8)

        // Offsets 2,4,6,8 with a permanent gap at 0 -> four disjoint intervals.
        for (i in 1..4) {
            assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(i * 2L, bytes("x"), isFin = false))
        }
        assertEquals(4, r.seenRangeCount)

        assertEquals(
            "a fifth disjoint interval exceeds the bound",
            QuicStreamReassembler.FeedResult.REJECTED_LIMIT,
            r.feed(20, bytes("x"), isFin = false),
        )
        assertEquals("the refused segment is not buffered", 4, r.pendingSegmentCount)

        // A segment that merges into existing coverage adds no interval, so it is still accepted.
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(3, bytes("y"), isFin = false))
        assertTrue("buffered bytes stay small", r.bufferedByteCount <= 8)
    }

    @Test
    fun `a contiguous stream tracks no coverage intervals at all`() {
        val r = QuicStreamReassembler(streamId = 54L)

        for (i in 0 until 200) {
            r.feed(i.toLong(), bytes("z"), isFin = false)
            r.drain()
        }

        assertEquals("a contiguous stream needs no interval tracking", 0, r.seenRangeCount)
        assertEquals(0, r.pendingSegmentCount)
        assertEquals(200L, r.nextExpectedOffset)
    }

    @Test
    fun `FIN locks the final size and marks the stream complete once drained`() {
        val r = QuicStreamReassembler(streamId = 6L)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(0, bytes("abc"), isFin = true))
        assertTrue(r.isFinReceived)
        assertTrue(r.hasFinalSize)
        assertEquals(3L, r.finalSize)
        assertFalse(r.isComplete)                           // not yet drained

        assertEquals("abc", str(r.drain()))
        assertTrue(r.isComplete)
    }

    @Test
    fun `an out-of-order FIN still records the final size before the gap is filled`() {
        val r = QuicStreamReassembler(streamId = 8L)

        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(3, bytes("def"), isFin = true))
        assertTrue(r.isFinReceived)
        assertEquals(6L, r.finalSize)                       // final size known from the FIN offset
        assertFalse(r.isComplete)                           // gap at 0 still open

        r.feed(0, bytes("abc"), isFin = false)
        assertEquals("abcdef", str(r.drain()))
        assertTrue(r.isComplete)
    }

    @Test
    fun `a FIN reporting a different final size is rejected`() {
        val r = QuicStreamReassembler(streamId = 9L)

        r.feed(0, bytes("abc"), isFin = true)               // final size locked at 3
        assertEquals(3L, r.finalSize)

        // A later FIN implying final size 4 (offset 0, length 4) conflicts with the locked 3.
        assertEquals(QuicStreamReassembler.FeedResult.REJECTED_FINAL_SIZE, r.feed(0, bytes("abcX"), isFin = true))
        assertEquals(3L, r.finalSize)                       // unchanged
    }

    @Test
    fun `data beyond the locked final size is rejected`() {
        val r = QuicStreamReassembler(streamId = 10L)

        r.feed(0, bytes("abc"), isFin = true)               // final size = 3

        // [2,5) ends at 5, beyond final size 3 — final-size violation.
        assertEquals(QuicStreamReassembler.FeedResult.REJECTED_FINAL_SIZE, r.feed(2, bytes("XYZ"), isFin = false))
        assertEquals(3L, r.finalSize)
        assertEquals("abc", str(r.drain()))
    }

    @Test
    fun `a pure FIN with no data completes an already-drained stream`() {
        val r = QuicStreamReassembler(streamId = 11L)

        r.feed(0, bytes("abc"), isFin = false)
        assertEquals("abc", str(r.drain()))
        assertFalse(r.isFinReceived)
        assertFalse(r.isComplete)

        // Empty payload + FIN at offset 3 locks final size 3 and completes.
        assertEquals(QuicStreamReassembler.FeedResult.ACCEPTED, r.feed(3, ByteArray(0), isFin = true))
        assertTrue(r.isFinReceived)
        assertEquals(3L, r.finalSize)
        assertTrue(r.isComplete)
    }

    @Test
    fun `rejecting a negative offset throws rather than corrupting state`() {
        val r = QuicStreamReassembler(streamId = 12L)
        assertThrows(IllegalArgumentException::class.java) {
            r.feed(-1L, bytes("x"), isFin = false)
        }
        assertEquals(0L, r.nextExpectedOffset)
        assertEquals(0, str(r.drain()).length)
    }
}
