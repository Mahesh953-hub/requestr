package com.example.interception

import java.net.InetAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1 task 11: the Wi-Fi/LTE handoff coordinator and the resolver plan, driven with virtual time
 * (fake `Network` tokens stand in for `android.net.Network`, which cannot be constructed here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnNetworkHandoffTest {

    private val wifi = "wifi"
    private val lte = "lte"
    private val wifiLink = VpnNetworkHandoff.LinkFingerprint(listOf("192.168.1.1"), hasIpv4 = true, hasIpv6 = false)
    private val lteLink = VpnNetworkHandoff.LinkFingerprint(listOf("10.0.0.53"), hasIpv4 = true, hasIpv6 = true)

    private class Driver(scope: kotlinx.coroutines.CoroutineScope, debounceMs: Long = 750) {
        val rebinds = mutableListOf<Any>()
        val reestablishes = mutableListOf<String>()
        val degraded = mutableListOf<Boolean>()
        val handoff = VpnNetworkHandoff(
            scope = scope,
            debounceMs = debounceMs,
            onUnderlyingNetwork = { rebinds += it },
            onReestablish = { reestablishes += it },
            onDegraded = { degraded += it },
        )
    }

    @Test
    fun `handover with the same dependencies only re-binds the underlying network`() = runTest {
        val d = Driver(backgroundScope)
        d.handoff.onEstablished(wifiLink, wifi)
        d.handoff.onNetworkChanged(lte, wifiLink.copy())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf<Any>(lte), d.rebinds)
        assertTrue("same resolvers and families must not tear the tun down", d.reestablishes.isEmpty())
        assertEquals(1L, d.handoff.rebindCount)
        assertEquals(0L, d.handoff.reestablishCount)
    }

    @Test
    fun `a burst of handover callbacks triggers exactly one debounced re-establish`() = runTest {
        val d = Driver(backgroundScope)
        d.handoff.onEstablished(wifiLink, wifi)
        // onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged all fire within ~100 ms.
        d.handoff.onNetworkChanged(lte, lteLink)
        advanceTimeBy(100)
        d.handoff.onNetworkChanged(lte, lteLink)
        advanceTimeBy(100)
        d.handoff.onNetworkChanged(lte, lteLink)
        advanceTimeBy(700); runCurrent()
        assertTrue("still inside the debounce window", d.reestablishes.isEmpty())
        advanceTimeBy(100); runCurrent()
        assertEquals(1, d.reestablishes.size)
        assertTrue(d.reestablishes.single(), d.reestablishes.single().contains("resolvers"))
        assertTrue(d.reestablishes.single().contains("ipv6"))
        // Only one rebind too -- the same network token repeated is not a new network.
        assertEquals(listOf<Any>(lte), d.rebinds)
        advanceTimeBy(5_000); runCurrent()
        assertEquals("no second re-establish after the window", 1, d.reestablishes.size)
    }

    @Test
    fun `a transient fingerprint that settles back cancels the pending re-establish`() = runTest {
        val d = Driver(backgroundScope)
        d.handoff.onEstablished(wifiLink, wifi)
        d.handoff.onNetworkChanged(wifi, lteLink) // e.g. a momentary resolver blip
        advanceTimeBy(300)
        d.handoff.onNetworkChanged(wifi, wifiLink) // back to the baseline before the window ends
        advanceTimeBy(5_000); runCurrent()
        assertTrue(d.reestablishes.isEmpty())
    }

    @Test
    fun `onLost without a replacement degrades but never stops or re-establishes`() = runTest {
        val d = Driver(backgroundScope)
        d.handoff.onEstablished(wifiLink, wifi)
        d.handoff.onNetworkLost(wifi)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(true), d.degraded)
        assertTrue(d.handoff.isDegraded)
        assertTrue(d.reestablishes.isEmpty())
        // The replacement arrives: degraded clears, rebind happens, and only a changed
        // fingerprint re-establishes (once).
        d.handoff.onNetworkChanged(lte, lteLink)
        assertEquals(listOf(true, false), d.degraded)
        assertFalse(d.handoff.isDegraded)
        assertEquals(listOf<Any>(lte), d.rebinds)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, d.reestablishes.size)
    }

    @Test
    fun `losing a network we were not bound to is ignored`() = runTest {
        val d = Driver(backgroundScope)
        d.handoff.onEstablished(wifiLink, wifi)
        d.handoff.onNetworkLost(lte)
        assertTrue(d.degraded.isEmpty())
        assertFalse(d.handoff.isDegraded)
    }

    @Test
    fun `an unknown fingerprint re-binds without re-establishing`() = runTest {
        val d = Driver(backgroundScope)
        d.handoff.onEstablished(wifiLink, wifi)
        d.handoff.onNetworkChanged(lte, null)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf<Any>(lte), d.rebinds)
        assertTrue(d.reestablishes.isEmpty())
    }

    @Test
    fun `close drops a pending re-establish`() = runTest {
        val d = Driver(backgroundScope)
        d.handoff.onEstablished(wifiLink, wifi)
        d.handoff.onNetworkChanged(lte, lteLink)
        d.handoff.close()
        advanceTimeBy(5_000); runCurrent()
        assertTrue(d.reestablishes.isEmpty())
    }

    @Test
    fun `fingerprint ignores link-local v6 and sorts resolvers`() {
        val fp = VpnNetworkHandoff.LinkFingerprint.of(
            dnsServers = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")),
            addresses = listOf(InetAddress.getByName("10.0.0.2"), InetAddress.getByName("fe80::1")),
        )
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), fp.dnsServers)
        assertTrue(fp.hasIpv4)
        assertFalse("fe80::/10 is not global connectivity", fp.hasIpv6)
    }

    @Test
    fun `virtual DNS advertises one resolver inside the bridge pool`() {
        assertEquals(listOf("198.18.0.1"), VpnDnsPlan.resolvers(virtualDns = true, systemResolvers = listOf("192.168.1.1")))
        assertEquals("198.18.0.0/15", com.example.interception.tun.Tun2ProxyConfig.VIRTUAL_DNS_POOL)
        val pool = InetAddress.getByName("198.18.0.0").address
        val resolver = InetAddress.getByName(VpnDnsPlan.VIRTUAL_DNS_RESOLVER).address
        // /15 => first 15 bits equal.
        assertEquals(pool[0], resolver[0])
        assertEquals(pool[1].toInt() and 0xFE, resolver[1].toInt() and 0xFE)
    }

    @Test
    fun `legacy path prefers the system resolvers and falls back to public ones`() {
        assertEquals(
            listOf("192.168.1.1", "fd00::53"),
            VpnDnsPlan.resolvers(virtualDns = false, systemResolvers = listOf("/192.168.1.1", "fd00::53", "192.168.1.1")),
        )
        assertEquals(
            listOf("192.168.1.1"),
            VpnDnsPlan.resolvers(virtualDns = false, systemResolvers = listOf("192.168.1.1", "fd00::53"), ipv6Enabled = false),
        )
        assertEquals(VpnDnsPlan.PUBLIC_FALLBACK_RESOLVERS, VpnDnsPlan.resolvers(virtualDns = false, systemResolvers = emptyList()))
        assertEquals(VpnDnsPlan.PUBLIC_FALLBACK_RESOLVERS, VpnDnsPlan.resolvers(virtualDns = false, systemResolvers = listOf(" ")))
    }
}
