package com.example.interception.quic

import com.example.interception.quic.QuicFrameWalker.Frame
import org.junit.Assert.*
import org.junit.Test

/**
 * Focused unit tests for [QuicFrameWalker] — the decrypted-payload frame walker that drives the
 * QUIC connection-ID lifecycle (Gap 5). Covers the four mandated scenarios: a NEW_CONNECTION_ID
 * frame adds a CID to [QuicCidTable]; its Retire Prior To field retires every earlier sequence;
 * a RETIRE_CONNECTION_ID frame removes a CID by sequence number; and the per-connection bound
 * holds across NEW_CONNECTION_ID frames. Plus coverage of the per-type frame delimiting that a
 * naive "type + length + payload" walker would get wrong: PADDING/PING (single bytes), ACK and
 * CRYPTO (self-/length-delimited) and a reserved grease frame are all skipped so a following
 * NEW_CONNECTION_ID is still found; and STREAM + CONNECTION_CLOSE frames are surfaced for the
 * PacketPump 1-RTT path.
 *
 * All wire bytes are hand-encoded per RFC 9000 (no external encoder).
 */
class QuicFrameWalkerTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    /** Builds a byte array from int literals (so 0x80..0xff don't need `.toByte()` per element). */
    private fun b(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    /** QUIC variable-length integer (RFC 9000 §16): 2-bit length class prefix. */
    private fun varint(value: Long): ByteArray = when {
        value <= 0x3F -> byteArrayOf(value.toByte())
        value <= 0x3FFF -> byteArrayOf((0x40 or (value shr 8).toInt()).toByte(), (value and 0xFF).toByte())
        else -> throw IllegalArgumentException("varint too large for test: $value")
    }

    /** A NEW_CONNECTION_ID frame (RFC 9000 §19.15): type + sequence + retire-prior-to + CID-len + CID + 16-byte token. */
    private fun newConnectionIdFrame(
        sequence: Long,
        retirePriorTo: Long,
        cid: ByteArray,
        token: ByteArray = ByteArray(STATELESS_RESET_TOKEN_LEN),
    ): ByteArray = varint(0x18) + varint(sequence) + varint(retirePriorTo) + b(cid.size) + cid + token

    /** A RETIRE_CONNECTION_ID frame (RFC 9000 §19.16): type + sequence number. */
    private fun retireConnectionIdFrame(sequence: Long): ByteArray = varint(0x19) + varint(sequence)

    /** A reserved grease frame (RFC 9000 §19.22: 0x1f*N + 0x1f) with a Length field and payload. */
    private fun greaseFrame(payload: ByteArray): ByteArray = varint(0x1f) + varint(payload.size.toLong()) + payload

    /** A CRYPTO frame (RFC 9000 §19.6): type + offset + length + data. */
    private fun cryptoFrame(offset: Long, data: ByteArray): ByteArray =
        varint(0x06) + varint(offset) + varint(data.size.toLong()) + data

    @Test
    fun `NEW_CONNECTION_ID adds a connection id to the routing table`() {
        val table = QuicCidTable()
        val walker = QuicFrameWalker(table)
        val cid = hex("0102030405060708")

        walker.walk("conn-1", newConnectionIdFrame(1, 0, cid))

        assertEquals("conn-1", table.lookup(cid))
    }

    @Test
    fun `NEW_CONNECTION_ID Retire Prior To retires every earlier sequence number`() {
        val table = QuicCidTable(maxCidsPerConnection = 8)
        val walker = QuicFrameWalker(table)
        val cid1 = hex("0102030405060708")
        val cid2 = hex("1112131415161718")
        val cid3 = hex("2122232425262728")

        walker.walk("conn-1", newConnectionIdFrame(1, 0, cid1))
        walker.walk("conn-1", newConnectionIdFrame(2, 0, cid2))
        // A NEW_CONNECTION_ID with Retire Prior To = 2 retires every active sequence < 2 (seq 1).
        walker.walk("conn-1", newConnectionIdFrame(3, 2, cid3))

        assertNull("seq 1 retired by Retire Prior To=2", table.lookup(cid1))
        assertEquals("conn-1", table.lookup(cid2)) // seq 2 is not < 2 -> still active
        assertEquals("conn-1", table.lookup(cid3)) // seq 3, the newly issued CID
    }

    @Test
    fun `RETIRE_CONNECTION_ID removes the connection id by sequence number`() {
        val table = QuicCidTable()
        val walker = QuicFrameWalker(table)
        val cid1 = hex("0102030405060708")
        val cid2 = hex("1112131415161718")

        walker.walk("conn-1", newConnectionIdFrame(1, 0, cid1) + newConnectionIdFrame(2, 0, cid2))
        assertEquals("conn-1", table.lookup(cid1))
        assertEquals("conn-1", table.lookup(cid2))

        walker.walk("conn-1", retireConnectionIdFrame(1))

        assertNull("seq 1 retired by RETIRE_CONNECTION_ID", table.lookup(cid1))
        assertEquals("conn-1", table.lookup(cid2)) // seq 2 untouched
    }

    @Test
    fun `the per-connection bound holds across NEW_CONNECTION_ID frames`() {
        val table = QuicCidTable(maxCidsPerConnection = 2)
        val walker = QuicFrameWalker(table)
        val cid1 = hex("0102030405060708")
        val cid2 = hex("1112131415161718")
        val cid3 = hex("2122232425262728")

        val result = walker.walk(
            "conn-1",
            newConnectionIdFrame(1, 0, cid1) + newConnectionIdFrame(2, 0, cid2) + newConnectionIdFrame(3, 0, cid3),
        )

        assertEquals("conn-1", table.lookup(cid1))
        assertEquals("conn-1", table.lookup(cid2))
        assertNull("third cid exceeds the bound and is not routed", table.lookup(cid3))
        // The rejected frame is still surfaced for transparency, even though its CID was not accepted.
        assertEquals(3, result.filterIsInstance<Frame.NewConnectionId>().size)
    }

    @Test
    fun `padding ping ack crypto and grease frames are skipped and a following NEW_CONNECTION_ID is found`() {
        val table = QuicCidTable()
        val walker = QuicFrameWalker(table)
        val cid = hex("0102030405060708")
        // PADDING (three 0x00 bytes), PING (0x01), an ACK (LA=5, delay=0, range count=0, first range=0),
        // a CRYPTO (offset=0, length=4, 4 bytes), and a reserved grease frame (0x1f) — all must be
        // delimited and skipped so the trailing NEW_CONNECTION_ID is still applied.
        val padding = b(0x00, 0x00, 0x00)
        val ping = b(0x01)
        val ack = b(0x02, 0x05, 0x00, 0x00, 0x00)
        val crypto = cryptoFrame(0, b(0xDE, 0xAD, 0xBE, 0xEF))
        val grease = greaseFrame(b(0xCA, 0xFE))
        val payload = padding + ping + ack + crypto + grease + newConnectionIdFrame(1, 0, cid)

        walker.walk("conn-1", payload)

        assertEquals("conn-1", table.lookup(cid))
    }

    @Test
    fun `STREAM and CONNECTION_CLOSE frames are surfaced for the caller`() {
        val table = QuicCidTable()
        val walker = QuicFrameWalker(table)
        // STREAM frame type 0x0f (FIN=1, LEN=1, OFF=1): stream id=0, offset=0, length=5, "hello".
        val streamData = "hello".toByteArray()
        val streamFrame = varint(0x0f) + varint(0) + varint(0) + varint(streamData.size.toLong()) + streamData
        // CONNECTION_CLOSE (0x1c): error code=0, frame type=0, reason "bye".
        val reason = "bye".toByteArray()
        val closeFrame = varint(0x1c) + varint(0) + varint(0) + varint(reason.size.toLong()) + reason

        val result = walker.walk("conn-1", streamFrame + closeFrame)

        val stream = result.filterIsInstance<Frame.Stream>().single()
        assertEquals(0L, stream.streamId)
        assertEquals(0L, stream.offset)
        assertTrue(stream.fin)
        assertEquals("hello", String(stream.data))

        val close = result.filterIsInstance<Frame.ConnectionClose>().single()
        assertEquals(0L, close.errorCode)
        assertEquals("bye", close.reasonPhrase)
    }

    private companion object {
        const val STATELESS_RESET_TOKEN_LEN = 16
    }
}
