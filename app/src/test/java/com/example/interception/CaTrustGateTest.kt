package com.example.interception

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [CaTrustGate] (cache TTL / invalidate, no real I/O -- probe and clock are
 * both injected) and the pure [shouldDegradeForUntrustedCa] decision function used by
 * [MitmProxyEngine]'s `FORCE_MITM` branch (C12).
 */
class CaTrustGateTest {

    @Test
    fun `caches probe result within ttl`() {
        var probeCalls = 0
        var now = 0L
        val gate = CaTrustGate(probe = { probeCalls++; true }, cacheTtlMs = 1_000L, clock = { now })

        runBlocking {
            assertTrue(gate.isTrusted())
            now = 500L
            assertTrue(gate.isTrusted())
        }
        assertEquals(1, probeCalls)
    }

    @Test
    fun `re-probes after ttl expires`() {
        var probeCalls = 0
        var now = 0L
        val gate = CaTrustGate(probe = { probeCalls++; true }, cacheTtlMs = 1_000L, clock = { now })

        runBlocking {
            assertTrue(gate.isTrusted())
            now = 1_500L
            assertTrue(gate.isTrusted())
        }
        assertEquals(2, probeCalls)
    }

    @Test
    fun `invalidate forces a fresh probe on next call`() {
        var probeCalls = 0
        val gate = CaTrustGate(probe = { probeCalls++; true }, cacheTtlMs = 60_000L, clock = { 0L })

        runBlocking {
            assertTrue(gate.isTrusted())
            gate.invalidate()
            assertTrue(gate.isTrusted())
        }
        assertEquals(2, probeCalls)
    }

    @Test
    fun `reflects an untrusted probe result`() {
        val gate = CaTrustGate(probe = { false })
        runBlocking { assertFalse(gate.isTrusted()) }
    }

    @Test
    fun `degrades only ForceMitm when ca untrusted`() {
        assertTrue(shouldDegradeForUntrustedCa(ConnectControl.Disposition.ForceMitm, caTrusted = false))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.ForceMitm, caTrusted = true))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.AllowOpaque, caTrusted = false))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.Block(), caTrusted = false))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.Redirect("x:1"), caTrusted = false))
    }
}
