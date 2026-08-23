package com.example.interception.quic

import org.junit.Assert.*
import org.junit.Test

/**
 * Focused unit tests for [QuicCidTable] — the QUIC connection-ID routing table (RFC 9000 §5.8 /
 * §17.2). Covers the full CID lifecycle the datagram router depends on: add/retire, multiple
 * simultaneous CIDs routing to one connection, the per-connection bound that stops a
 * NEW_CONNECTION_ID flood from growing unboundedly, the zero-length-DCID 5-tuple fallback, and
 * the Retry re-key that re-routes a connection to the Retry packet's SCID (and retires the
 * original DCID so Initial keys can be re-derived from the new one).
 */
class QuicCidTableTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    @Test
    fun `addCid then lookup returns the routing key`() {
        val table = QuicCidTable()
        val cid = hex("8394c8f03e515708")

        assertTrue(table.addCid("conn-1", cid))
        assertEquals("conn-1", table.lookup(cid))
    }

    @Test
    fun `lookup returns null for an unknown cid`() {
        val table = QuicCidTable()
        assertNull(table.lookup(hex("0102030405060708")))
    }

    @Test
    fun `two cids added from different byte arrays with equal content route identically`() {
        // The router extracts a DCID from each incoming packet as a fresh ByteArray, so the table
        // must key by content, not by array identity (a raw ByteArray map would miss this).
        val table = QuicCidTable()
        val firstPacketDcid = hex("8394c8f03e515708")
        val secondPacketDcid = hex("8394c8f03e515708") // same bytes, different instance

        table.addCid("conn-1", firstPacketDcid)
        assertNotSame("sanity: the two arrays are distinct instances", firstPacketDcid, secondPacketDcid)
        assertEquals("conn-1", table.lookup(secondPacketDcid))
    }

    @Test
    fun `retireCid removes the cid and returns its routing key`() {
        val table = QuicCidTable()
        val cid = hex("8394c8f03e515708")
        table.addCid("conn-1", cid)

        assertEquals("conn-1", table.retireCid(cid))
        assertNull("cid no longer routes after retire", table.lookup(cid))
    }

    @Test
    fun `retireCid returns null for an unknown cid`() {
        val table = QuicCidTable()
        assertNull(table.retireCid(hex("0102030405060708")))
    }

    @Test
    fun `two active cids both route to the same connection`() {
        // RFC 9000: a connection may have several active CIDs (the initial DCID plus any issued via
        // NEW_CONNECTION_ID); a packet carrying any of them must reach the same connection.
        val table = QuicCidTable()
        val cidA = hex("8394c8f03e515708")
        val cidB = hex("7ddc89e15045ab12")

        assertTrue(table.addCid("conn-shared", cidA))
        assertTrue(table.addCid("conn-shared", cidB))

        assertEquals("conn-shared", table.lookup(cidA))
        assertEquals("conn-shared", table.lookup(cidB))
    }

    @Test
    fun `retiring one of two cids leaves the other routing`() {
        val table = QuicCidTable()
        val cidA = hex("8394c8f03e515708")
        val cidB = hex("7ddc89e15045ab12")
        table.addCid("conn-shared", cidA)
        table.addCid("conn-shared", cidB)

        table.retireCid(cidA)

        assertNull(table.lookup(cidA))
        assertEquals("conn-shared", table.lookup(cidB))
    }

    @Test
    fun `addCid enforces the per-connection bound`() {
        // A bounded active-CID set so a flood of spoofed NEW_CONNECTION_ID frames can't grow the
        // table unboundedly. With a bound of 2 the third distinct CID is rejected and never routes.
        val table = QuicCidTable(maxCidsPerConnection = 2)
        val cidA = hex("8394c8f03e515708")
        val cidB = hex("7ddc89e15045ab12")
        val cidC = hex("0f1e2d3c4b5a6978")

        assertTrue(table.addCid("conn-bounded", cidA))
        assertTrue(table.addCid("conn-bounded", cidB))
        assertFalse("third cid exceeds the bound and is rejected", table.addCid("conn-bounded", cidC))

        assertNull("rejected cid does not route", table.lookup(cidC))
        assertEquals("conn-bounded", table.lookup(cidA))
        assertEquals("conn-bounded", table.lookup(cidB))
    }

    @Test
    fun `re-adding an already known cid is idempotent and never trips the bound`() {
        val table = QuicCidTable(maxCidsPerConnection = 2)
        val cid = hex("8394c8f03e515708")

        assertTrue(table.addCid("conn-1", cid))
        assertTrue("re-adding the same cid stays within the bound", table.addCid("conn-1", cid))
        assertEquals("conn-1", table.lookup(cid))
    }

    @Test
    fun `addCid rejects an empty zero-length cid`() {
        // A zero-length DCID connection is routed by 5-tuple (lookupByTuple), never by an empty CID
        // entry — so an empty CID is refused here to keep the two paths disjoint.
        val table = QuicCidTable()
        assertFalse(table.addCid("conn-1", ByteArray(0)))
        assertNull(table.lookup(ByteArray(0)))
    }

    @Test
    fun `rekeyAfterRetry re-routes the connection to the retry scid and retires the original dcid`() {
        // RFC 9000 §17.2.8: after a Retry the client switches its DCID to the Retry packet's SCID.
        // The connection that was routed by the original DCID must now route by the Retry SCID, and
        // the caller re-derives Initial keys from that new DCID.
        val table = QuicCidTable()
        val originalDcid = hex("8394c8f03e515708")
        val retryScid = hex("7ddc89e15045ab12")
        table.addCid("conn-retry", originalDcid)

        val rekeyedTo = table.rekeyAfterRetry(originalDcid, retryScid)

        assertEquals("conn-retry", rekeyedTo)
        assertEquals("conn-retry", table.lookup(retryScid))
        assertNull("original dcid is retired after re-key", table.lookup(originalDcid))
    }

    @Test
    fun `rekeyAfterRetry returns null for an unknown original dcid`() {
        // A Retry for a connection we never observed (or a spoofed Retry) has nothing to re-key.
        val table = QuicCidTable()
        val retryScid = hex("7ddc89e15045ab12")

        assertNull(table.rekeyAfterRetry(hex("8394c8f03e515708"), retryScid))
        assertNull("nothing was registered for the unknown connection", table.lookup(retryScid))
    }

    @Test
    fun `zero-length dcid connection routes by tuple fallback`() {
        val table = QuicCidTable()
        val tupleKey = "10.0.0.1:54321-93.184.216.34:443"

        table.registerZeroLengthConnection(tupleKey, "conn-zero")

        assertEquals("conn-zero", table.lookupByTuple(tupleKey))
        assertNull("empty dcid is not routed by cid", table.lookup(ByteArray(0)))
    }

    @Test
    fun `lookupByTuple returns null for an unknown tuple`() {
        val table = QuicCidTable()
        assertNull(table.lookupByTuple("10.0.0.1:54321-93.184.216.34:443"))
    }
}
