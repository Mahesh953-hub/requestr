package com.example.interception.tls

import com.example.data.interception.CapabilityState
import com.example.data.interception.FridaState
import com.example.data.interception.RootState
import com.example.interception.CaTrustGate
import com.example.interception.ConnectControl
import com.example.interception.quic.UdpPolicyRouter
import com.example.interception.tls.TrustDiagnostics.AppTrustInfo
import com.example.interception.tls.TrustDiagnostics.CaEntry
import com.example.interception.tls.TrustDiagnostics.Presence
import com.example.interception.tls.TrustDiagnostics.Store
import com.example.interception.tls.TrustDiagnostics.UserCaPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

/**
 * S1 task 13. Pure-JVM: every input to [TrustDiagnostics] is a fake (stores, package facts,
 * pinning history, mTLS signals, QUIC counters, capabilities, clock).
 */
class TrustDiagnosticsTest {

    private val ourFp = "aa".repeat(32)
    private val otherFp = "bb".repeat(32)
    private val mozillaFp = "cc".repeat(32)

    private fun ours(store: Store, alias: String = if (store == Store.USER) "user:1234abcd.0" else "system:1234abcd.0") =
        CaEntry(store, alias, "CN=requestr Root CA,O=requestr", ourFp)

    private fun stale(store: Store) =
        CaEntry(store, if (store == Store.USER) "user:deadbeef.0" else "system:deadbeef.0", "CN=requestr Root CA,O=requestr", otherFp)

    private val unrelated = CaEntry(Store.SYSTEM, "system:5ad8a5d6.0", "CN=GlobalSign Root CA,O=GlobalSign nv-sa", mozillaFp)

    private class FakeStores(var list: List<CaEntry>) : TrustDiagnostics.TrustStoreScanner {
        var scans = 0
        override fun entries(): List<CaEntry> {
            scans++
            return list
        }
    }

    private var now = 1_000_000L

    private fun diag(
        stores: FakeStores,
        fingerprint: String? = ourFp,
        apps: Map<String, AppTrustInfo> = emptyMap(),
        pinning: () -> List<ConnectControl.PinningFailureRegistry.HostHistory> = { ConnectControl.PinningFailureRegistry.snapshot() },
        mtls: () -> Set<String> = { MutualTlsSignals.hosts() },
        quic: Map<UdpPolicyRouter.Branch, Long> = emptyMap(),
        caps: CapabilityState = CapabilityState(),
        ttl: Long = 30_000L,
    ) = TrustDiagnostics(
        ownCaFingerprint = { fingerprint },
        stores = stores,
        appInspector = { apps[it] },
        pinningHistory = pinning,
        mutualTlsHosts = mtls,
        quicBranches = { quic },
        capabilities = { caps },
        clock = { now },
        cacheTtlMs = ttl,
    )

    @Before
    fun reset() {
        ConnectControl.PinningFailureRegistry.clearAll()
        MutualTlsSignals.clearAll()
    }

    @After
    fun cleanup() = reset()

    // ---- per-store presence -------------------------------------------------------------

    @Test
    fun `CA in the user store only is reported with its alias and the system store absent`() {
        val r = diag(FakeStores(listOf(unrelated, ours(Store.USER)))).report()

        assertEquals(Presence.INSTALLED, r.user.presence)
        assertEquals("user:1234abcd.0", r.user.entry?.alias)
        assertEquals(Presence.ABSENT, r.system.presence)
        assertNull(r.system.entry)
        assertTrue(r.caTrusted)
        assertTrue(r.findings.first().contains("USER trust store (user:1234abcd.0)"))
    }

    @Test
    fun `CA in the system store wins over the user store in the headline`() {
        val r = diag(FakeStores(listOf(ours(Store.SYSTEM), ours(Store.USER)))).report()

        assertEquals(Presence.INSTALLED, r.system.presence)
        assertEquals(Presence.INSTALLED, r.user.presence)
        assertTrue(r.caTrusted)
        assertTrue(r.headline.contains("SYSTEM trust store"))
        assertEquals(true, r.appWillTrustCa)
    }

    @Test
    fun `no CA anywhere is not trusted and tells the user how to install it`() {
        val r = diag(FakeStores(listOf(unrelated))).report()

        assertEquals(Presence.ABSENT, r.system.presence)
        assertEquals(Presence.ABSENT, r.user.presence)
        assertFalse(r.caTrusted)
        assertTrue(r.headline.contains("NOT installed"))
        assertTrue(r.headline.contains("Install a certificate"))
    }

    @Test
    fun `no CA generated yet is a distinct finding and never trusted`() {
        // Even a matching-looking entry cannot be "ours" when we have no fingerprint to compare.
        val r = diag(FakeStores(listOf(ours(Store.USER))), fingerprint = null).report()

        assertNull(r.ownCaFingerprint)
        assertEquals(Presence.STALE, r.user.presence) // alias says requestr, but unverifiable
        assertFalse(r.caTrusted)
        assertTrue(r.headline.contains("has not generated its CA"))
    }

    // ---- alias / fingerprint mismatch ---------------------------------------------------

    @Test
    fun `a requestr-named entry with a different fingerprint is STALE not INSTALLED`() {
        val r = diag(FakeStores(listOf(stale(Store.USER), unrelated))).report()

        assertEquals(Presence.STALE, r.user.presence)
        assertEquals("user:deadbeef.0", r.user.entry?.alias)
        assertEquals(otherFp, r.user.entry?.sha256)
        assertFalse("a stale CA must not count as trusted", r.caTrusted)
        val staleFinding = r.findings.first { it.contains("different requestr CA") }
        assertTrue(staleFinding.contains("user:deadbeef.0"))
        assertTrue(staleFinding.contains(otherFp.take(16)))
        assertTrue(staleFinding.contains(ourFp.take(16)))
        assertTrue(staleFinding.contains("USER store"))
    }

    @Test
    fun `exact fingerprint match beats a stale twin in the same store`() {
        val r = diag(FakeStores(listOf(stale(Store.USER), ours(Store.USER)))).report()

        assertEquals(Presence.INSTALLED, r.user.presence)
        assertEquals(ourFp, r.user.entry?.sha256)
        assertTrue(r.caTrusted)
        // The stale twin is not reported as a separate STALE status once the real one is present.
        assertTrue(r.findings.none { it.contains("different requestr CA") })
    }

    @Test
    fun `fingerprint comparison is case-insensitive`() {
        val upper = CaEntry(Store.USER, "user:1.0", "CN=x", ourFp.uppercase())
        val r = diag(FakeStores(listOf(upper))).report()
        assertEquals(Presence.INSTALLED, r.user.presence)
    }

    // ---- per-app networkSecurityConfig policy -------------------------------------------

    @Test
    fun `app targeting API 24+ without a network security config ignores user CAs`() {
        val app = AppTrustInfo("com.bank", targetSdk = 34, hasNetworkSecurityConfig = false, debuggable = false)
        val r = diag(FakeStores(listOf(ours(Store.USER))), apps = mapOf("com.bank" to app)).report("com.bank")

        assertEquals(UserCaPolicy.SYSTEM_STORE_ONLY, r.userCaPolicy)
        assertTrue("CA is trusted by the OS...", r.caTrusted)
        assertEquals("...but not by this app", false, r.appWillTrustCa)
        val f = r.findings.first { it.startsWith("com.bank") }
        assertTrue(f.contains("ignores user-installed CAs"))
        assertTrue(f.contains("API 34"))
    }

    @Test
    fun `app with a custom network security config is CUSTOM_CONFIG and undecidable`() {
        val app = AppTrustInfo("com.shop", targetSdk = 33, hasNetworkSecurityConfig = true, debuggable = true)
        val r = diag(FakeStores(listOf(ours(Store.USER))), apps = mapOf("com.shop" to app)).report("com.shop")

        assertEquals(UserCaPolicy.CUSTOM_CONFIG, r.userCaPolicy)
        assertNull(r.appWillTrustCa)
        val f = r.findings.first { it.startsWith("com.shop") }
        assertTrue(f.contains("<certificates src=\"user\"/>"))
        assertTrue("debuggable builds get the debug-overrides hint", f.contains("debug-overrides"))
    }

    @Test
    fun `legacy app targeting below API 24 trusts user CAs`() {
        val app = AppTrustInfo("com.old", targetSdk = 23, hasNetworkSecurityConfig = false, debuggable = false)
        val r = diag(FakeStores(listOf(ours(Store.USER))), apps = mapOf("com.old" to app)).report("com.old")

        assertEquals(UserCaPolicy.TRUSTS_USER_CAS, r.userCaPolicy)
        assertEquals(true, r.appWillTrustCa)
        assertTrue(r.findings.any { it.startsWith("com.old") && it.contains("trusts user-installed CAs") })
    }

    @Test
    fun `system store install makes the per-app policy moot`() {
        val app = AppTrustInfo("com.bank", targetSdk = 34, hasNetworkSecurityConfig = false, debuggable = false)
        val r = diag(FakeStores(listOf(ours(Store.SYSTEM))), apps = mapOf("com.bank" to app)).report("com.bank")

        assertEquals(UserCaPolicy.SYSTEM_STORE_ONLY, r.userCaPolicy)
        assertEquals(true, r.appWillTrustCa)
        assertTrue(r.findings.none { it.startsWith("com.bank") })
    }

    @Test
    fun `unknown package yields UNKNOWN policy and no app finding`() {
        val r = diag(FakeStores(listOf(ours(Store.USER)))).report("com.missing")
        assertNull(r.app)
        assertEquals(UserCaPolicy.UNKNOWN, r.userCaPolicy)
        assertNull(r.appWillTrustCa)
    }

    // ---- pinning history ---------------------------------------------------------------

    @Test
    fun `pinning history is aggregated per host with count last reason and bypass`() {
        val reg = ConnectControl.PinningFailureRegistry
        reg.recordFailure("Api.Pinned.Example", at = 10_000L, reason = "alert: bad_certificate")
        reg.recordFailure("api.pinned.example", at = 20_000L, reason = "alert: certificate_unknown")
        reg.recordFailure("api.pinned.example", at = 30_000L, reason = "alert: certificate_unknown")
        reg.recordFailure("once.example", at = 30_000L, reason = "connection reset during handshake")
        now = 40_000L

        val r = diag(FakeStores(listOf(ours(Store.USER)))).report()

        assertEquals(listOf("api.pinned.example", "once.example"), r.hosts.map { it.host })
        val pinned = r.hosts[0]
        assertEquals(3, pinned.failureCount)
        assertEquals(30_000L, pinned.lastFailureAt)
        assertEquals("alert: certificate_unknown", pinned.lastReason)
        assertNotNull("3 failures in the window trips the bypass", pinned.bypassedUntil)
        assertFalse(pinned.mutualTlsRequired)
        val once = r.hosts[1]
        assertEquals(1, once.failureCount)
        assertNull(once.bypassedUntil)

        val f = r.findings.first { it.startsWith("api.pinned.example") }
        assertTrue(f.contains("failed 3 TLS handshake(s)"))
        assertTrue(f.contains("(last: alert: certificate_unknown)"))
        assertTrue(f.contains("auto-bypassed"))
        assertTrue(r.findings.first { it.startsWith("once.example") }.contains("(last: connection reset during handshake)"))
    }

    @Test
    fun `an expired bypass window is not reported as bypassed`() {
        val reg = ConnectControl.PinningFailureRegistry
        repeat(3) { reg.recordFailure("h.example", at = 1_000L, bypassDurationMs = 5_000L) }
        now = 10_000L
        val r = diag(FakeStores(listOf(ours(Store.USER)))).report()
        assertNull(r.hosts.single().bypassedUntil)
        assertNull(r.hosts.single().lastReason)
        assertFalse(r.findings.first { it.startsWith("h.example") }.contains("auto-bypassed"))
    }

    // ---- mutual TLS ----------------------------------------------------------------------

    @Test
    fun `certificate_required from upstream maps to a mutual TLS finding`() {
        val e = SSLHandshakeException("Received fatal alert: certificate_required")
        assertTrue(MutualTlsSignals.isClientCertificateDemand(e))
        assertTrue(MutualTlsSignals.recordIfDemand("MTLS.Example", e))
        assertFalse(MutualTlsSignals.recordIfDemand("plain.example", SSLHandshakeException("Received fatal alert: handshake_failure")))
        assertEquals(setOf("mtls.example"), MutualTlsSignals.hosts())

        val r = diag(FakeStores(listOf(ours(Store.USER)))).report()
        val h = r.hosts.single()
        assertEquals("mtls.example", h.host)
        assertTrue(h.mutualTlsRequired)
        assertEquals(0, h.failureCount)
        val f = r.findings.first { it.startsWith("mtls.example") }
        assertTrue(f.contains("client certificate (mutual TLS)"))
        assertTrue(f.contains("Client certificates"))
    }

    @Test
    fun `mutual TLS demand is detected through the cause chain and merged with pinning history`() {
        val wrapped = RuntimeException("upstream failed", SSLHandshakeException("certificate_required"))
        assertTrue(MutualTlsSignals.isClientCertificateDemand(wrapped))
        MutualTlsSignals.record("both.example")
        ConnectControl.PinningFailureRegistry.recordFailure("both.example", at = now, reason = "alert: bad_certificate")

        val r = diag(FakeStores(listOf(ours(Store.USER)))).report()
        val h = r.hosts.single()
        assertTrue(h.mutualTlsRequired)
        assertEquals(1, h.failureCount)
        assertEquals("alert: bad_certificate", h.lastReason)
    }

    // ---- QUIC / Chromium and runtime signals -------------------------------------------

    @Test
    fun `observed but unblocked QUIC flows produce the Chromium HTTP3 caveat`() {
        val r = diag(
            FakeStores(listOf(ours(Store.USER))),
            quic = mapOf(UdpPolicyRouter.Branch.OBSERVED to 4L, UdpPolicyRouter.Branch.DNS to 100L),
        ).report()
        val f = r.findings.first { it.contains("QUIC") }
        assertTrue(f.contains("4 QUIC flow(s) were observed but not blocked"))
        assertTrue(f.contains("HTTP/3"))
        assertTrue(f.contains("UDP/443"))
    }

    @Test
    fun `blocked QUIC flows are stated as the TCP fallback fact`() {
        val r = diag(FakeStores(listOf(ours(Store.USER))), quic = mapOf(UdpPolicyRouter.Branch.BLOCKED to 7L)).report()
        assertTrue(r.findings.first { it.contains("QUIC") }.contains("7 QUIC flow(s) were blocked"))
    }

    @Test
    fun `no QUIC activity produces no QUIC finding`() {
        val r = diag(FakeStores(listOf(ours(Store.USER))), quic = mapOf(UdpPolicyRouter.Branch.DNS to 5L)).report()
        assertTrue(r.findings.none { it.contains("QUIC") })
    }

    @Test
    fun `frida and root signals are consolidated into the report`() {
        val caps = CapabilityState(root = RootState.GRANTED, frida = FridaState.RUNNING)
        val r = diag(FakeStores(listOf(ours(Store.USER))), caps = caps).report()
        assertSame(caps, r.capabilities)
        assertTrue(r.findings.any { it.startsWith("frida-server is reachable") })
        assertTrue(r.findings.any { it.startsWith("Root is granted") && it.contains("system store") })

        // Already in the system store: the root hint is redundant and omitted.
        val r2 = diag(FakeStores(listOf(ours(Store.SYSTEM))), caps = caps).report()
        assertTrue(r2.findings.none { it.startsWith("Root is granted") })
    }

    // ---- cache behaviour ---------------------------------------------------------------

    @Test
    fun `report is cached for the ttl and rebuilt after it or on invalidate`() {
        val stores = FakeStores(listOf(ours(Store.USER)))
        val d = diag(stores, ttl = 1_000L)

        val first = d.report()
        assertSame(first, d.report())
        assertSame(first, d.cachedReport())
        assertEquals(1, stores.scans)

        now += 999
        assertSame(first, d.report())
        now += 1
        val second = d.report()
        assertNotSame(first, second)
        assertEquals(2, stores.scans)

        d.invalidate()
        assertNull(d.cachedReport())
        d.report()
        assertEquals(3, stores.scans)
    }

    @Test
    fun `report for a different package is not served from the cache`() {
        val stores = FakeStores(listOf(ours(Store.USER)))
        val d = diag(stores, apps = mapOf("a" to AppTrustInfo("a", 34, false, false)))
        d.report("a")
        d.report("a")
        assertEquals(1, stores.scans)
        d.report(null)
        assertEquals(2, stores.scans)
    }

    @Test
    fun `cachedReport never builds`() {
        val stores = FakeStores(listOf(ours(Store.USER)))
        val d = diag(stores)
        assertNull(d.cachedReport())
        assertEquals(0, stores.scans)
    }

    @Test
    fun `a failing store scan degrades to ABSENT instead of throwing`() {
        val boom = object : TrustDiagnostics.TrustStoreScanner {
            override fun entries(): List<CaEntry> = throw IllegalStateException("keystore unavailable")
        }
        val r = TrustDiagnostics(
            ownCaFingerprint = { ourFp }, stores = boom, appInspector = { null },
            pinningHistory = { emptyList() }, mutualTlsHosts = { emptySet() },
            quicBranches = { emptyMap() }, capabilities = { CapabilityState() }, clock = { now },
        ).report()
        assertEquals(Presence.ABSENT, r.system.presence)
        assertEquals(Presence.ABSENT, r.user.presence)
        assertFalse(r.caTrusted)
    }

    // ---- gate / report agreement --------------------------------------------------------

    @Test
    fun `CaTrustGate driven by the report agrees with the report boolean in every store state`() {
        val cases = listOf(
            listOf<CaEntry>() to false,
            listOf(unrelated) to false,
            listOf(stale(Store.USER)) to false,
            listOf(stale(Store.SYSTEM)) to false,
            listOf(ours(Store.USER)) to true,
            listOf(ours(Store.SYSTEM)) to true,
            listOf(ours(Store.SYSTEM), ours(Store.USER)) to true,
            listOf(stale(Store.SYSTEM), ours(Store.USER)) to true,
        )
        for ((entries, expected) in cases) {
            val d = diag(FakeStores(entries))
            val gate = CaTrustGate(probe = { d.report().caTrusted }, cacheTtlMs = 1_000L, clock = { now })
            val trusted = runBlocking { gate.isTrusted() }
            assertEquals("entries=$entries", expected, trusted)
            assertEquals("gate and report must never disagree", d.report().caTrusted, trusted)
        }
    }

    @Test
    fun `policyFor mirrors the Android 7 user-CA rule`() {
        assertEquals(UserCaPolicy.UNKNOWN, TrustDiagnostics.policyFor(null))
        assertEquals(UserCaPolicy.TRUSTS_USER_CAS, TrustDiagnostics.policyFor(AppTrustInfo("a", 23, true, false)))
        assertEquals(UserCaPolicy.CUSTOM_CONFIG, TrustDiagnostics.policyFor(AppTrustInfo("a", 24, true, false)))
        assertEquals(UserCaPolicy.SYSTEM_STORE_ONLY, TrustDiagnostics.policyFor(AppTrustInfo("a", 24, false, false)))
    }

    private fun assertNotSame(a: Any?, b: Any?) = assertTrue("expected different instances", a !== b)
}
