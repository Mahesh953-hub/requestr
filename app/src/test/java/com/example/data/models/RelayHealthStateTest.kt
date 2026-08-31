package com.example.data.models

import com.example.interception.tun.IcmpRelay
import com.example.interception.tun.TunRelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14 / Lane D: unit tests for the relay-health state derivation that drives
 * [com.example.ui.components.RelayHealthCard] / [RelayHealthDetailSheet], plus the honest
 * per-reason drop partition and flow-table-pressure helpers. No Android/UI dependencies —
 * pure functions over the real [TunRelay.RelayStats] counters.
 */
class RelayHealthStateTest {

    private fun stats(
        relayedFlows: Int = 0,
        relayErrors: Int = 0,
        unrelayableCount: Int = 0,
        evictionCount: Int = 0,
        dropProtectFail: Int = 0,
        dropUpstreamOpen: Int = 0,
        dropUpstreamWrite: Int = 0,
        dropUnrelayableProto: Int = 0,
        dropTcpUpstreamWriteFail: Int = 0,
        activeTcpV4: Int = 0,
        activeTcpV6: Int = 0,
        activeUdpV4: Int = 0,
        activeUdpV6: Int = 0,
    ) = TunRelay.RelayStats(
        relayedFlows = relayedFlows,
        relayErrors = relayErrors,
        unrelayableCount = unrelayableCount,
        evictionCount = evictionCount,
        dropProtectFail = dropProtectFail,
        dropUpstreamOpen = dropUpstreamOpen,
        dropUpstreamWrite = dropUpstreamWrite,
        dropUnrelayableProto = dropUnrelayableProto,
        dropTcpUpstreamWriteFail = dropTcpUpstreamWriteFail,
        activeTcpV4 = activeTcpV4,
        activeTcpV6 = activeTcpV6,
        activeUdpV4 = activeUdpV4,
        activeUdpV6 = activeUdpV6,
    )

    @Test
    fun `no pump is Down regardless of counters`() {
        val state = toRelayHealthState(pumpRunning = false, stats = stats(relayedFlows = 10), icmpUnrelayableCount = 0, icmpLastPathUsed = IcmpRelay.LastPathUsed.NONE_YET)
        assertTrue(state is RelayHealthState.Down)
    }

    @Test
    fun `pump running with no errors is Healthy`() {
        val state = toRelayHealthState(true, stats(relayedFlows = 52, activeTcpV4 = 30), 0, IcmpRelay.LastPathUsed.PING_SOCKET)
        assertTrue(state is RelayHealthState.Healthy)
    }

    @Test
    fun `pump running with relay errors is Degraded`() {
        val state = toRelayHealthState(true, stats(relayErrors = 5), 0, IcmpRelay.LastPathUsed.NONE_YET)
        assertTrue(state is RelayHealthState.Degraded)
    }

    @Test
    fun `pump running with unrelayable packets is Degraded`() {
        val state = toRelayHealthState(true, stats(unrelayableCount = 3), 0, IcmpRelay.LastPathUsed.NONE_YET)
        assertTrue(state is RelayHealthState.Degraded)
    }

    @Test
    fun `pump running with icmp unrelayable count is Degraded`() {
        val state = toRelayHealthState(true, stats(), icmpUnrelayableCount = 2, IcmpRelay.LastPathUsed.NONE_YET)
        assertTrue(state is RelayHealthState.Degraded)
    }

    @Test
    fun `totalDropped sums relay errors and unrelayable`() {
        assertEquals(7, stats(relayErrors = 4, unrelayableCount = 3).totalDropped())
        assertEquals(0, stats().totalDropped())
    }

    @Test
    fun `dropReasons omits zero counters and preserves display order`() {
        val reasons = stats(
            evictionCount = 2412,
            dropUpstreamWrite = 1684,
            dropUpstreamOpen = 512,
            dropProtectFail = 0,
            dropUnrelayableProto = 92,
        ).dropReasons()
        assertEquals(4, reasons.size)
        assertEquals("Flow table at cap — entry evicted", reasons[0].label)
        assertEquals(2412, reasons[0].count)
        assertEquals("Upstream socket not writable", reasons[1].label)
        assertEquals("No route to upstream host", reasons[2].label)
        assertEquals("Unrelayable protocol / ICMP", reasons[3].label)
    }

    @Test
    fun `dropReasons is empty when every counter is zero`() {
        assertTrue(stats().dropReasons().isEmpty())
    }

    @Test
    fun `dropTcpUpstreamWriteFail renders as its own distinct reason between dropUpstreamWrite and dropUpstreamOpen`() {
        // Regression test: writeUpstream's/flushPendingUpstream's TCP write-failure catch blocks
        // used to increment nothing, unlike the equivalent UDP write-failure path
        // (dropUpstreamWrite) -- silent packet loss invisible in the drop-reason panel. The two
        // counters must stay distinct (a TCP write failure tears down the whole flow; a UDP one
        // drops a single datagram and the flow lives on), so neither should ever be folded into
        // the other.
        val reasons = stats(
            evictionCount = 10,
            dropUpstreamWrite = 20,
            dropTcpUpstreamWriteFail = 30,
            dropUpstreamOpen = 40,
        ).dropReasons()
        assertEquals(4, reasons.size)
        assertEquals("Upstream socket not writable", reasons[1].label)
        assertEquals(20, reasons[1].count)
        assertEquals("TCP upstream write failed — connection reset", reasons[2].label)
        assertEquals(30, reasons[2].count)
        assertEquals("No route to upstream host", reasons[3].label)
        assertEquals(40, reasons[3].count)
    }

    @Test
    fun `dropTcpUpstreamWriteFail is omitted when zero even if the UDP counterpart is non-zero`() {
        val reasons = stats(dropUpstreamWrite = 5, dropTcpUpstreamWriteFail = 0).dropReasons()
        assertEquals(1, reasons.size)
        assertEquals("Upstream socket not writable", reasons[0].label)
    }

    // ── RelayHealthState.dropReasons(): the combined TunRelay + IcmpRelay breakdown ───────

    @Test
    fun `RelayHealthState dropReasons appends the two honest IcmpRelay partitions after TunRelay's own reasons`() {
        val state = toRelayHealthState(
            pumpRunning = true,
            stats = stats(evictionCount = 2412, dropUpstreamWrite = 1684),
            icmpUnrelayableCount = 107,
            icmpLastPathUsed = IcmpRelay.LastPathUsed.PING_SOCKET,
            icmpChecksumFailureCount = 15,
            icmpUnrelayableIcmpv6Count = 92,
        )
        val reasons = state.dropReasons()
        assertEquals(4, reasons.size)
        assertEquals("Flow table at cap — entry evicted", reasons[0].label)
        assertEquals("Upstream socket not writable", reasons[1].label)
        assertEquals("ICMP echo checksum invalid", reasons[2].label)
        assertEquals(15, reasons[2].count)
        assertEquals("ICMPv6 not relayable", reasons[3].label)
        assertEquals(92, reasons[3].count)
    }

    @Test
    fun `RelayHealthState dropReasons omits the IcmpRelay partitions when both are zero`() {
        val state = toRelayHealthState(
            pumpRunning = true,
            stats = stats(evictionCount = 5),
            icmpUnrelayableCount = 0,
            icmpLastPathUsed = IcmpRelay.LastPathUsed.NONE_YET,
        )
        val reasons = state.dropReasons()
        assertEquals(1, reasons.size)
        assertEquals("Flow table at cap — entry evicted", reasons[0].label)
    }

    @Test
    fun `toRelayHealthState threads the icmp checksum and icmpv6 counters through unchanged`() {
        val state = toRelayHealthState(
            pumpRunning = true,
            stats = stats(),
            icmpUnrelayableCount = 3,
            icmpLastPathUsed = IcmpRelay.LastPathUsed.TCP_FALLBACK,
            icmpChecksumFailureCount = 2,
            icmpUnrelayableIcmpv6Count = 1,
        )
        assertEquals(2, state.icmpChecksumFailureCount)
        assertEquals(1, state.icmpUnrelayableIcmpv6Count)
    }

    @Test
    fun `activeFlowCount sums every transport and family`() {
        assertEquals(52, stats(activeTcpV4 = 30, activeTcpV6 = 8, activeUdpV4 = 10, activeUdpV6 = 4).activeFlowCount())
    }

    @Test
    fun `flowTablePressure equals activeFlowCount`() {
        val s = stats(activeTcpV4 = 480, activeTcpV6 = 6, activeUdpV4 = 22, activeUdpV6 = 4)
        assertEquals(s.activeFlowCount(), s.flowTablePressure())
    }
}
