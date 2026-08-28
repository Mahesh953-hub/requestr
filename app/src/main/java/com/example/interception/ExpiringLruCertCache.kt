package com.example.interception

import java.security.KeyPair
import java.security.cert.X509Certificate

/**
 * §3: bounded, expiring LRU cache for per-host MITM leaf certificates.
 *
 * [MitmProxyEngine]'s `certCache` used to be an unbounded `ConcurrentHashMap` -- fine for a
 * short capture session, but a genuine memory leak (an RSA-2048 [KeyPair] plus an
 * [X509Certificate] retained forever, per distinct host ever visited) over a long-running
 * capture that touches many hosts. Two of the reference repos this plan is based on converge
 * on the same fix shape: NetBare's `SSLEngineFactory` cache and RoRoIntercept's
 * `LinkedHashMap`-based LRU capped at 200 entries -- this mirrors that 200-entry cap and adds a
 * time-based expiry on top so a host that goes stale is regenerated with a fresh cert rather
 * than living forever just because it's still within the 200 most-recently-used hosts.
 *
 * Per-host cert generation is expensive (2048-bit RSA keygen), so [getOrCompute] deliberately
 * synchronizes its whole get-or-compute critical section: concurrent callers racing for the
 * *same* host (and even callers for different hosts, since this is a coarse per-instance lock)
 * must never each generate and discard their own certificate. This runs on the CONNECT-accept
 * path (once per TCP connection), not a per-request hot path, so a coarse lock is an acceptable
 * trade for correctness/simplicity over a per-key locking scheme.
 *
 * Deliberately has no Android framework dependency so it is directly JVM-unit-testable without
 * Robolectric.
 */
internal class ExpiringLruCertCache(
    private val maxSize: Int = 200,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val cert: X509Certificate, val keyPair: KeyPair, val insertedAt: Long)

    // accessOrder=true turns this into an LRU: every get() (via containsKey/get inside
    // getOrCompute) moves the entry to the end, and removeEldestEntry evicts the true
    // least-recently-used entry once size exceeds maxSize.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxSize
    }

    /**
     * Returns the cached (cert, keyPair) pair for [host] if present and not expired; otherwise
     * invokes [generate] exactly once, stores the result, and returns it. Safe under concurrent
     * calls for the same or different hosts -- generation never races.
     */
    @Synchronized
    fun getOrCompute(
        host: String,
        generate: (String) -> Pair<X509Certificate, KeyPair>,
    ): Pair<X509Certificate, KeyPair> {
        val existing = entries[host]
        if (existing != null && clock() - existing.insertedAt < ttlMillis) {
            return existing.cert to existing.keyPair
        }
        val (cert, keyPair) = generate(host)
        entries[host] = Entry(cert, keyPair, clock())
        return cert to keyPair
    }

    /** Test/introspection seam: current entry count (post-eviction). */
    val size: Int
        @Synchronized get() = entries.size

    /** Test/introspection seam: whether [host] is currently cached (does not affect LRU order). */
    @Synchronized
    fun containsKey(host: String): Boolean = entries.containsKey(host)

    companion object {
        /** Entries older than this are regenerated on next access even if still within the LRU cap. */
        const val DEFAULT_TTL_MILLIS: Long = 30 * 60 * 1000L
    }
}
