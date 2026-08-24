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
    fun `activeFlowCount sums every transport and family`() {
        assertEquals(52, stats(activeTcpV4 = 30, activeTcpV6 = 8, activeUdpV4 = 10, activeUdpV6 = 4).activeFlowCount())
    }

    @Test
    fun `flowTablePressure equals activeFlowCount`() {
        val s = stats(activeTcpV4 = 480, activeTcpV6 = 6, activeUdpV4 = 22, activeUdpV6 = 4)
        assertEquals(s.activeFlowCount(), s.flowTablePressure())
    }
}
