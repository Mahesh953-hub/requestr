package com.example.interception.tls

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TlsKeyLogRegistryTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun randomOf(byte: Int): ByteArray = ByteArray(32) { byte.toByte() }

    @After
    fun tearDown() {
        TlsKeyLogRegistry.clear()
    }

    @Test
    fun `record then lookup round-trips the same secret`() {
        val cr = randomOf(0x11)
        val secret = byteArrayOf(1, 2, 3, 4, 5)
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr, secret)
        val found = TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr)
        assertNotNull(found)
        assertArrayEquals(secret, found)
    }

    @Test
    fun `lookup miss returns null`() {
        val cr = randomOf(0x22)
        assertNull(TlsKeyLogRegistry.lookup(KeyLogLabel.SERVER_TRAFFIC_SECRET_0, cr))
    }

    @Test
    fun `record ignores wrong-length client random`() {
        val bad = ByteArray(10)
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, bad, byteArrayOf(9))
        assertNull(TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, bad))
        assertEquals(0, TlsKeyLogRegistry.entryCount.value)
    }

    @Test
    fun `overwrite on differing secret is last-writer-wins`() {
        val cr = randomOf(0x33)
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr, byteArrayOf(1))
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr, byteArrayOf(2))
        val found = TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr)
        assertArrayEquals(byteArrayOf(2), found)
    }

    @Test
    fun `caller-mutated array after record does not affect stored secret`() {
        val cr = randomOf(0x44)
        val secret = byteArrayOf(7, 7, 7)
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr, secret)
        secret[0] = 99
        val found = TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr)
        assertArrayEquals(byteArrayOf(7, 7, 7), found)
    }

    @Test
    fun `importNssKeyLog accepts well-formed lines and counts them`() {
        val cr1 = "11".repeat(32)
        val cr2 = "22".repeat(32)
        val lines = sequenceOf(
            "CLIENT_TRAFFIC_SECRET_0 $cr1 aabbcc",
            "SERVER_TRAFFIC_SECRET_0 $cr2 ddeeff",
        )
        val accepted = TlsKeyLogRegistry.importNssKeyLog(lines)
        assertEquals(2, accepted)
        assertArrayEquals(hex("aabbcc"), TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, hex(cr1)))
        assertArrayEquals(hex("ddeeff"), TlsKeyLogRegistry.lookup(KeyLogLabel.SERVER_TRAFFIC_SECRET_0, hex(cr2)))
    }

    @Test
    fun `importNssKeyLog is case-insensitive on hex and rejects malformed lines without throwing`() {
        val cr = "AA".repeat(32)
        val lines = sequenceOf(
            "not a valid line",
            "CLIENT_TRAFFIC_SECRET_0 tooshort aabb",
            "CLIENT_TRAFFIC_SECRET_0 $cr AABBCC",
            "",
            "# comment",
        )
        val accepted = TlsKeyLogRegistry.importNssKeyLog(lines)
        assertEquals(1, accepted)
        assertArrayEquals(hex("aabbcc"), TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, hex(cr.lowercase())))
    }

    @Test
    fun `clear empties the registry and entryCount emits 0`() {
        val cr = randomOf(0x55)
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr, byteArrayOf(1))
        assertTrue(TlsKeyLogRegistry.entryCount.value > 0)
        TlsKeyLogRegistry.clear()
        assertEquals(0, TlsKeyLogRegistry.entryCount.value)
        assertNull(TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, cr))
    }

    @Test
    fun `bound enforcement evicts oldest entry`() {
        // MAX_ENTRIES is 512 (private); drive it over the bound and confirm size stays capped
        // and the very first entry (oldest) is gone while a recent one survives.
        fun crFor(i: Int): ByteArray {
            val b = ByteArray(32)
            b[0] = (i shr 8).toByte()
            b[1] = i.toByte()
            return b
        }
        val first = crFor(1)
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, first, byteArrayOf(0))
        for (i in 2..600) {
            TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, crFor(i), byteArrayOf(1))
        }
        assertTrue(TlsKeyLogRegistry.entryCount.value <= 512)
        assertNull(TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, first))
        val last = crFor(600)
        assertNotNull(TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, last))
    }
}
