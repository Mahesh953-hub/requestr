package com.example.interception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Exercises [ProcNetUidResolver.resolveFromLines] against fixture text shaped exactly like real
 * `/proc/net/tcp`/`tcp6` content, so the hex-encoding/matching logic is verified deterministically
 * without depending on the test host's actual proc filesystem.
 */
class ProcNetUidResolverTest {

    // 127.0.0.1:8080 (local) <-> 93.184.216.34:443 (remote), owned by uid 10087.
    // 127.0.0.1 -> 0100007F ; port 8080 = 0x1F90 ; 93.184.216.34 -> 22D8B85D ; port 443 = 0x01BB
    private val tcpFixture = listOf(
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        "   0: 0100007F:1F90 22D8B85D:01BB 01 00000000:00000000 00:00000000 00000000 10087        0 55555 1 0000000000000000 20 4 30 10 -1",
        "   1: 0100007F:C350 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12345 1 0000000000000000 100 0 0 10 0",
    )

    @Test
    fun `matches the exact local-remote pair and returns its uid`() {
        val local = InetSocketAddress.createUnresolved("127.0.0.1", 8080).let { InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 8080) }
        val remote = InetSocketAddress(java.net.InetAddress.getByName("93.184.216.34"), 443)
        assertEquals(10087, ProcNetUidResolver.resolveFromLines(tcpFixture, local, remote))
    }

    @Test
    fun `no matching row returns null`() {
        val local = InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 9999)
        val remote = InetSocketAddress(java.net.InetAddress.getByName("1.2.3.4"), 443)
        assertNull(ProcNetUidResolver.resolveFromLines(tcpFixture, local, remote))
    }

    @Test
    fun `swapped local-remote does not accidentally match`() {
        // The fixture's row 0 is (local=127.0.0.1:8080, remote=93.184.216.34:443); asking with
        // the pair swapped must NOT match it -- that's exactly why ConnectControl.resolveClientUid
        // tries both orderings itself rather than this function guessing.
        val local = InetSocketAddress(java.net.InetAddress.getByName("93.184.216.34"), 443)
        val remote = InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 8080)
        assertNull(ProcNetUidResolver.resolveFromLines(tcpFixture, local, remote))
    }

    @Test
    fun `header-only input returns null without throwing`() {
        val local = InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 8080)
        val remote = InetSocketAddress(java.net.InetAddress.getByName("93.184.216.34"), 443)
        assertNull(ProcNetUidResolver.resolveFromLines(listOf(tcpFixture[0]), local, remote))
    }

    @Test
    fun `ipv6 style address encodes and matches per-group byte reversal`() {
        // ::1 (loopback) port 9000, matched against a synthetic tcp6 row for ::1.
        // ::1 -> 16 bytes: 15 zero bytes then 0x01. Kernel stores as 4 groups of 4 bytes,
        // each group byte-reversed: group0=00000000 group1=00000000 group2=00000000 group3=01000000.
        val fixture = listOf(
            "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
            "   0: 00000000000000000000000000000000:2328 00000000000000000000000000000000:01BB 01 00000000:00000000 00:00000000 00000000 555        0 1 1 0 20 4 30 10 -1",
        )
        val local = InetSocketAddress(java.net.InetAddress.getByName("::"), 9000)
        val remote = InetSocketAddress(java.net.InetAddress.getByName("::"), 443)
        assertEquals(555, ProcNetUidResolver.resolveFromLines(fixture, local, remote))
    }
}
