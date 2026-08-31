package com.example.interception

import com.example.data.interception.CaState

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
 * `DetailRow("Failure reason", ...)`), never silent.
 *
 * [CapabilityProbe.probeCaInstalled] enumerates real trust stores (KeyStore + `/system` dir) on
 * every call, so it's real I/O -- not expensive, but not free either, and this runs once per
 * CONNECT. [cachedResult] amortizes that over [cacheTtlMs] (default 30s) so a capture session
 * with many FORCE_MITM'd hosts in quick succession doesn't re-probe per host. [invalidate] lets a
 * caller (e.g. right after the user installs the CA from Settings) force a fresh probe on the
 * very next check rather than waiting out the TTL.
 */
class CaTrustGate(
    private val probe: suspend () -> Boolean = DEFAULT_PROBE,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var cachedResult: Boolean? = null
    @Volatile private var cachedAt: Long = 0L

    /** True if requestr's CA is currently trusted (user or system store), per [probe]/cache. */
    suspend fun isTrusted(): Boolean {
        val now = clock()
        val cached = cachedResult
        if (cached != null && now - cachedAt < cacheTtlMs) return cached
        val fresh = probe()
        cachedResult = fresh
        cachedAt = now
        return fresh
    }

    /** Forces the next [isTrusted] call to re-probe rather than serve a cached value. */
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
    }
}

/**
 * Pure decision function (unit-testable without any I/O): a `FORCE_MITM` rule should be degraded
 * to a raw/opaque relay only when it actually matched (any other disposition already bypasses
 * MITM for its own reasons) *and* the CA isn't trusted client-side.
 */
internal fun shouldDegradeForUntrustedCa(disposition: ConnectControl.Disposition, caTrusted: Boolean): Boolean =
    disposition is ConnectControl.Disposition.ForceMitm && !caTrusted
