package com.example.interception

import com.example.data.interception.CaState
import java.util.concurrent.atomic.AtomicLong

/**
 * C12 — per-CONNECT check that requestr's own CA is actually trusted client-side before a
 * `FORCE_MITM` rule is honored.
 *
 * Without this, a `FORCE_MITM` rule matched against a device that never installed (or has since
 * removed) requestr's CA would still attempt TLS termination: the client-facing handshake using
 * our per-host leaf cert would fail (untrusted issuer), breaking the connection outright with no
 * actionable explanation. [MitmProxyEngine] instead calls [isTrusted] right before falling
 * through to cert generation and, if the CA is not trusted, degrades that CONNECT to a plain
 * opaque relay (see [shouldDegradeForUntrustedCa]) -- the connection still works, just undecrypted,
 * and a descriptive `failureReason` is persisted on the `ConnectAttemptEntity` so this is visible
 * in the UI (already rendered generically for any disposition -- see `InterceptScreen`'s
 * `DetailRow(\"Failure reason\", ...)`), never silent.
 *
 * SHOULD-FIX (per-CONNECT re-check honesty): [cachedResult] amortizes [CapabilityProbe.probeCaInstalled]
 * over [cacheTtlMs] (default 30s). The KDoc used to call that probe "not expensive, but not free
 * either" while nothing in production ever called [invalidate] -- i.e. the code advertised a
 * "per-CONNECT re-check" that in practice only ever re-checked once per [cacheTtlMs] window,
 * regardless of what changed in between. Measured/reasoned cost of removing the cache entirely
 * (probing on every CONNECT, option (a) from the review): [CapabilityProbe.probeCaInstalled]
 * does two rounds of real, unbounded-by-anything-we-control I/O -- (1) lists and
 * `File.readText()`s every file under `/system/etc/security/cacerts` (stock Android images ship
 * with 100+ system root CAs, so this is not a handful of files), then, if that misses, (2) loads
 * the `AndroidCAStore` `KeyStore` and, for every alias whose *name* doesn't already match, calls
 * `KeyStore.getCertificate()` (a real ASN.1 X.509 deserialization) just to inspect its subject DN.
 * A capture session under active MITM can see dozens of CONNECTs per page load; paying that cost
 * on every single one would add visible, un-cacheable latency to every HTTPS connection --
 * exactly the kind of tax this whole feature exists to avoid. So: the cache stays (option (b)).
 *
 * What changed: [invalidate] is now actually wired into every CA-trust-store mutation the app
 * itself performs and can therefore observe synchronously -- [CertificateAuthority.regenerate]
 * (a brand new, definitely-not-yet-trusted CA replaces the old one), [CaP12Exchange.importP12]
 * (same: a different CA identity replaces the active one), and [CaWhitelistService.promoteToSystem]
 * (the CA's storage location changes, even though [isTrusted] evaluates both as trusted) -- via
 * [invalidateAll], a process-wide signal so this fires even for gates the caller has no direct
 * reference to (e.g. the one owned by a already-running [MitmProxyEngine]'s active capture
 * session), not just whichever instance happens to be at hand.
 *
 * What did NOT change, stated honestly rather than implied away: a trust-store change made
 * *outside* the app -- the user manually removing requestr's CA via Android's own Settings app,
 * or a MDM/policy push -- is still only detected the next time [isTrusted] happens to probe
 * fresh, i.e. within [cacheTtlMs] of it actually mattering, same as before. That is a real,
 * bounded window, not a broken promise: the KDoc no longer claims a "per-CONNECT re-check" that
 * the code never actually performed.
 */
class CaTrustGate(
    private val probe: suspend () -> Boolean = DEFAULT_PROBE,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var cachedResult: Boolean? = null
    @Volatile private var cachedAt: Long = 0L

    // Compared against the shared, process-wide [Companion.epoch] on every read: a mismatch
    // means some app-initiated CA change happened since this gate last probed, so the cached
    // value -- however fresh by [cacheTtlMs]'s clock -- is treated as already invalidated. This
    // is what lets [invalidateAll] reach gates the caller holds no reference to.
    @Volatile private var lastSeenEpoch: Long = epoch.get()

    /** True if requestr's CA is currently trusted (user or system store), per [probe]/cache. */
    suspend fun isTrusted(): Boolean {
        val now = clock()
        val currentEpoch = epoch.get()
        val cached = cachedResult
        if (cached != null && currentEpoch == lastSeenEpoch && now - cachedAt < cacheTtlMs) {
            return cached
        }
        val fresh = probe()
        cachedResult = fresh
        cachedAt = now
        lastSeenEpoch = currentEpoch
        return fresh
    }

    /** Forces the next [isTrusted] call on *this* instance to re-probe rather than serve a cached value. */
    fun invalidate() {
        cachedResult = null
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 30_000L

        /** Real probe: trusted iff requestr's CA is in the user or system trust store. */
        val DEFAULT_PROBE: suspend () -> Boolean = {
            when (CapabilityProbe.probeCaInstalled()) {
                CaState.USER_STORE, CaState.SYSTEM_STORE -> true
                CaState.UNKNOWN, CaState.NOT_INSTALLED -> false
            }
        }

        // Process-wide, monotonically increasing generation counter. Every [CaTrustGate]
        // instance remembers the epoch value it last probed at; [invalidateAll] bumps this,
        // which invalidates every outstanding instance's cache on its very next [isTrusted]
        // call without needing a registry of live instances.
        private val epoch = AtomicLong(0L)

        /**
         * Call after any app-initiated action that may have changed which CA the device trusts
         * as requestr's -- see the class KDoc for the exact call sites this is wired to
         * ([CertificateAuthority.regenerate], [CaP12Exchange.importP12],
         * [CaWhitelistService.promoteToSystem]). Cheap (an atomic increment); safe to call
         * speculatively/on every attempt, not just confirmed successes.
         */
        fun invalidateAll() {
            epoch.incrementAndGet()
        }
    }
}

/**
 * Pure decision function (unit-testable without any I/O): a `FORCE_MITM` rule should be degraded
 * to a raw/opaque relay only when it actually matched (any other disposition already bypasses
 * MITM for its own reasons) *and* the CA isn't trusted client-side.
 */
internal fun shouldDegradeForUntrustedCa(disposition: ConnectControl.Disposition, caTrusted: Boolean): Boolean =
    disposition is ConnectControl.Disposition.ForceMitm && !caTrusted
