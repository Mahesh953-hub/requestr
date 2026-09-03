package com.example.interception

import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * §3: bounded, expiring LRU cache for per-host MITM leaf certificates.
 *
 * [MitmProxyEngine]'s `certCache` used to be an unbounded `ConcurrentHashMap` -- fine for a
 * short capture session, but a genuine memory leak (a [KeyPair] plus an [X509Certificate]
 * retained forever, per distinct host ever visited) over a long-running capture that touches
 * many hosts. Two of the reference repos this plan is based on converge on the same fix shape:
 * NetBare's `SSLEngineFactory` cache and RoRoIntercept's `LinkedHashMap`-based LRU capped at
 * 200 entries -- this mirrors that 200-entry cap and adds a time-based expiry on top so a host
 * that goes stale is regenerated with a fresh cert rather than living forever just because it's
 * still within the 200 most-recently-used hosts.
 *
 * S1 task 8: the instance-wide `@Synchronized` around the whole get-or-compute section is gone.
 * Concurrent callers for the *same* key still generate exactly once (they serialize on a per-key
 * lock), but callers for *distinct* keys -- which under the new
 * [com.example.interception.tls.CertificateEngine] may each be dialing a different upstream to
 * sniff its leaf -- no longer wait on each other. The map itself is only ever touched under a
 * short internal lock; the (potentially slow, potentially blocking-I/O) [getOrCompute] generator
 * runs under the per-key lock only.
 *
 * Keys are opaque strings: the engine composes `(identity, policy mode, profile tag)` so a
 * policy change or a different profile source invalidates correctly instead of reusing a leaf
 * minted under the old policy.
 */
class ExpiringLruCertCache(
    private val maxSize: Int = 200,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val cert: X509Certificate, val keyPair: KeyPair, val insertedAt: Long)

    // accessOrder=true turns this into an LRU: every get() moves the entry to the end, and
    // removeEldestEntry evicts the true least-recently-used entry once size exceeds maxSize.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxSize
    }
    private val mapLock = Any()
    private val keyLocks = ConcurrentHashMap<String, Any>()

    fun getOrCompute(
        host: String,
        generate: (String) -> Pair<X509Certificate, KeyPair>,
    ): Pair<X509Certificate, KeyPair> {
        peek(host)?.let { return it }
        val lock = keyLocks.computeIfAbsent(host) { Any() }
        try {
            synchronized(lock) {
                peek(host)?.let { return it }
                val (cert, keyPair) = generate(host)
                synchronized(mapLock) { entries[host] = Entry(cert, keyPair, clock()) }
                return cert to keyPair
            }
        } finally {
            keyLocks.remove(host, lock)
        }
    }

    /** Cached, unexpired entry for [host] (refreshing its LRU recency), or null. */
    fun peek(host: String): Pair<X509Certificate, KeyPair>? = synchronized(mapLock) {
        val existing = entries[host] ?: return null
        if (clock() - existing.insertedAt < ttlMillis) existing.cert to existing.keyPair else null
    }

    /** Drops [host]'s entry, if any. */
    fun invalidate(host: String) {
        synchronized(mapLock) { entries.remove(host) }
    }

    val size: Int
        get() = synchronized(mapLock) { entries.size }

    fun containsKey(host: String): Boolean = synchronized(mapLock) { entries.containsKey(host) }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30 * 60 * 1000L
    }
}
