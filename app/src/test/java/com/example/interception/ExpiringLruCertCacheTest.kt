package com.example.interception

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3 coverage for [ExpiringLruCertCache]: bounded-size LRU eviction, recency ordering, TTL
 * expiry, and single-generation-under-concurrency for the same key. Pure JVM -- no Android
 * framework dependency, so no Robolectric needed (RSA keygen in these tests uses a tiny key
 * size purely to keep the test fast; production code still generates real 2048-bit keys).
 */
class ExpiringLruCertCacheTest {

    /** Cheap stand-in for a real generated (cert, keyPair) -- fast enough to call hundreds of times. */
    private fun cheapCertFor(host: String): Pair<X509Certificate, KeyPair> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(512, SecureRandom()) }.genKeyPair()
        val now = Date()
        val subject = X500Name("CN=$host")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(System.nanoTime()), now, Date(now.time + 1000L), subject, keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return cert to keyPair
    }

    @Test
    fun `getOrCompute generates once and returns the cached pair on subsequent calls`() {
        val cache = ExpiringLruCertCache(maxSize = 200)
        var generations = 0
        val first = cache.getOrCompute("a.example") { generations++; cheapCertFor(it) }
        val second = cache.getOrCompute("a.example") { generations++; cheapCertFor(it) }

        assertEquals(1, generations)
        assertEquals(first.first, second.first)
        assertEquals(first.second.public, second.second.public)
    }

    @Test
    fun `distinct hosts each get their own entry`() {
        val cache = ExpiringLruCertCache(maxSize = 200)
        cache.getOrCompute("a.example") { cheapCertFor(it) }
        cache.getOrCompute("b.example") { cheapCertFor(it) }

        assertEquals(2, cache.size)
        assertTrue(cache.containsKey("a.example"))
        assertTrue(cache.containsKey("b.example"))
    }

    @Test
    fun `cache evicts the least-recently-used entry once past the size cap`() {
        val cache = ExpiringLruCertCache(maxSize = 3)
        cache.getOrCompute("host1") { cheapCertFor(it) }
        cache.getOrCompute("host2") { cheapCertFor(it) }
        cache.getOrCompute("host3") { cheapCertFor(it) }
        // host1 is now the least-recently-used of the three.
        cache.getOrCompute("host4") { cheapCertFor(it) }

        assertEquals(3, cache.size)
        assertFalse("least-recently-used entry must have been evicted", cache.containsKey("host1"))
        assertTrue(cache.containsKey("host2"))
        assertTrue(cache.containsKey("host3"))
        assertTrue(cache.containsKey("host4"))
    }

    @Test
    fun `accessing an entry refreshes its recency so it is not the next eviction victim`() {
        val cache = ExpiringLruCertCache(maxSize = 3)
        cache.getOrCompute("host1") { cheapCertFor(it) }
        cache.getOrCompute("host2") { cheapCertFor(it) }
        cache.getOrCompute("host3") { cheapCertFor(it) }
        // Touch host1 again -- it is now the most-recently-used, host2 becomes the LRU victim.
        cache.getOrCompute("host1") { cheapCertFor(it) }
        cache.getOrCompute("host4") { cheapCertFor(it) }

        assertTrue("recently re-accessed entry must survive eviction", cache.containsKey("host1"))
        assertFalse("host2 is now the least-recently-used entry", cache.containsKey("host2"))
    }

    @Test
    fun `expired entry is regenerated on next access even though it is still within the size cap`() {
        var now = 1_000_000L
        val cache = ExpiringLruCertCache(maxSize = 200, ttlMillis = 500, clock = { now })
        var generations = 0
        cache.getOrCompute("a.example") { generations++; cheapCertFor(it) }

        now += 1000L // past the 500ms TTL
        cache.getOrCompute("a.example") { generations++; cheapCertFor(it) }

        assertEquals("an expired entry must be regenerated, not reused", 2, generations)
    }

    @Test
    fun `entry within TTL is not regenerated`() {
        var now = 1_000_000L
        val cache = ExpiringLruCertCache(maxSize = 200, ttlMillis = 500, clock = { now })
        var generations = 0
        cache.getOrCompute("a.example") { generations++; cheapCertFor(it) }

        now += 100L // still within the 500ms TTL
        cache.getOrCompute("a.example") { generations++; cheapCertFor(it) }

        assertEquals(1, generations)
    }

    @Test
    fun `concurrent getOrCompute calls for the same host generate exactly once`() {
        val cache = ExpiringLruCertCache(maxSize = 200)
        val generations = AtomicInteger(0)
        val threadCount = 16
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = arrayOfNulls<Pair<X509Certificate, KeyPair>>(threadCount)

        val threads = (0 until threadCount).map { i ->
            Thread {
                startLatch.await()
                results[i] = cache.getOrCompute("shared.example") {
                    generations.incrementAndGet()
                    // Simulate expensive keygen taking a moment, to widen the race window.
                    Thread.sleep(20)
                    cheapCertFor(it)
                }
                doneLatch.countDown()
            }
        }
        threads.forEach { it.start() }
        startLatch.countDown()
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS))

        assertEquals("all concurrent callers for the same host must share one generation", 1, generations.get())
        val firstCert = results[0]!!.first
        assertTrue(results.all { it!!.first == firstCert })
    }
}
