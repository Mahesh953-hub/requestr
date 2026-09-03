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

    // ── invalidateAll (SHOULD-FIX: per-CONNECT re-check honesty) ──────

    @Test
    fun `invalidateAll forces a fresh probe on a gate that never received a direct reference`() {
        // Simulates the real-world gap this fixes: CertificateAuthority.regenerate /
        // CaP12Exchange.importP12 / CaWhitelistService.promoteToSystem have no reference to
        // whichever CaTrustGate a live MitmProxyEngine happens to be using -- invalidateAll must
        // still reach it.
        var probeCalls = 0
        val gate = CaTrustGate(probe = { probeCalls++; true }, cacheTtlMs = 60_000L, clock = { 0L })

        runBlocking {
            assertTrue(gate.isTrusted())
            CaTrustGate.invalidateAll()
            // Still well within the 60s TTL by the gate's own clock -- this only re-probes
            // because of invalidateAll(), not because the cache expired.
            assertTrue(gate.isTrusted())
        }
        assertEquals(2, probeCalls)
    }

    @Test
    fun `invalidateAll invalidates every independently-constructed gate, not just one`() {
        var probeCallsA = 0
        var probeCallsB = 0
        val gateA = CaTrustGate(probe = { probeCallsA++; true }, cacheTtlMs = 60_000L, clock = { 0L })
        val gateB = CaTrustGate(probe = { probeCallsB++; true }, cacheTtlMs = 60_000L, clock = { 0L })

        runBlocking {
            assertTrue(gateA.isTrusted())
            assertTrue(gateB.isTrusted())
            CaTrustGate.invalidateAll()
            assertTrue(gateA.isTrusted())
            assertTrue(gateB.isTrusted())
        }
        assertEquals(2, probeCallsA)
        assertEquals(2, probeCallsB)
    }

    @Test
    fun `a gate constructed after invalidateAll still probes fresh on its very first call`() {
        CaTrustGate.invalidateAll()
        var probeCalls = 0
        val gate = CaTrustGate(probe = { probeCalls++; true }, cacheTtlMs = 60_000L, clock = { 0L })
        runBlocking { assertTrue(gate.isTrusted()) }
        assertEquals(1, probeCalls)
    }

    @Test
    fun `without any invalidateAll call, the ttl cache behaves exactly as before`() {
        // Regression guard: the epoch check must never cause spurious re-probes on its own.
        var probeCalls = 0
        var now = 0L
        val gate = CaTrustGate(probe = { probeCalls++; true }, cacheTtlMs = 1_000L, clock = { now })

        runBlocking {
            assertTrue(gate.isTrusted())
            now = 200L
            assertTrue(gate.isTrusted())
            now = 900L
            assertTrue(gate.isTrusted())
        }
        assertEquals(1, probeCalls)
    }

    @Test
    fun `degrades only ForceMitm when ca untrusted`() {
        assertTrue(shouldDegradeForUntrustedCa(ConnectControl.Disposition.ForceMitm, caTrusted = false))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.ForceMitm, caTrusted = true))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.AllowOpaque, caTrusted = false))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.Block(), caTrusted = false))
        assertFalse(shouldDegradeForUntrustedCa(ConnectControl.Disposition.Redirect("x:1"), caTrusted = false))
    }

    // ---- S1 task 13: the fast gate and the full TrustDiagnostics report never disagree ----

    @Test
    fun `gate boolean equals TrustDiagnostics report caTrusted for every store layout`() {
        val fp = "ab".repeat(32)
        fun entry(store: com.example.interception.tls.TrustDiagnostics.Store, sha: String) =
            com.example.interception.tls.TrustDiagnostics.CaEntry(store, "${store.name.lowercase()}:x.0", "CN=requestr Root CA", sha)
        val layouts = listOf(
            emptyList(),
            listOf(entry(com.example.interception.tls.TrustDiagnostics.Store.USER, "cd".repeat(32))), // stale twin
            listOf(entry(com.example.interception.tls.TrustDiagnostics.Store.USER, fp)),
            listOf(entry(com.example.interception.tls.TrustDiagnostics.Store.SYSTEM, fp)),
        )
        for (layout in layouts) {
            val diag = com.example.interception.tls.TrustDiagnostics(
                ownCaFingerprint = { fp },
                stores = object : com.example.interception.tls.TrustDiagnostics.TrustStoreScanner {
                    override fun entries() = layout
                },
                appInspector = { null },
                pinningHistory = { emptyList() },
                mutualTlsHosts = { emptySet() },
                quicBranches = { emptyMap() },
                capabilities = { com.example.data.interception.CapabilityState() },
                clock = { 0L },
            )
            val gate = CaTrustGate(probe = { diag.report().caTrusted }, cacheTtlMs = 1_000L, clock = { 0L })
            val fromGate = runBlocking { gate.isTrusted() }
            assertEquals("layout=$layout", diag.report().caTrusted, fromGate)
            // Degradation decision derived from the gate matches what the report would drive.
            assertEquals(
                shouldDegradeForUntrustedCa(ConnectControl.Disposition.ForceMitm, fromGate),
                shouldDegradeForUntrustedCa(ConnectControl.Disposition.ForceMitm, diag.report().caTrusted),
            )
        }
    }
}
