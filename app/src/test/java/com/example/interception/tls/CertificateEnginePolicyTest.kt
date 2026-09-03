package com.example.interception.tls

import android.content.Context
import android.content.ContextWrapper
import com.example.interception.ExpiringLruCertCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger

/**
 * S1 task 8, `CertificateEnginePolicyTest`: identity selection and profile source. Everything with
 * I/O is injected -- a fake CA, a scripted upstream sniffer -- so this runs on the plain JVM.
 */
class CertificateEnginePolicyTest {

    private val context: Context = ContextWrapper(null)
    private val ca = CertTestFixtures.fakeCa()

    private fun engine(
        sniffer: (String, Int) -> X509Certificate? = { _, _ -> null },
        cache: ExpiringLruCertCache = ExpiringLruCertCache(),
    ) = CertificateEngine(caProvider = { ca }, upstreamSniffer = sniffer, cache = cache)

    private fun issued(outcome: CertificateEngine.Outcome) = outcome as CertificateEngine.Outcome.Issued

    @Test
    fun `SNI beats the CONNECT authority`() = runBlocking {
        val e = engine()
        val r = CertificateEngine.Request(connectAuthority = "cdn-edge-17.example.net", port = 443, sni = "www.example.com")
        val identity = e.resolveIdentity(r)
        assertEquals("www.example.com", identity.host)
        assertEquals(LeafProfile.Source.SNI, identity.source)

        val out = issued(e.issue(context, r))
        assertEquals("CN=www.example.com", out.certificate.subjectX500Principal.name)
        assertTrue(CertTestFixtures.dnsSans(out.certificate).contains("www.example.com"))
        assertFalse(out.fromCache)
    }

    @Test
    fun `IP CONNECT with SNI yields a DNS-name leaf and never sniffs`() = runBlocking {
        val sniffs = AtomicInteger(0)
        val e = engine(sniffer = { _, _ -> sniffs.incrementAndGet(); null })
        val r = CertificateEngine.Request(connectAuthority = "93.184.216.34", port = 443, sni = "example.org")
        assertFalse(e.wantsUpstreamSniff(r))
        val out = issued(e.issue(context, r))
        assertEquals("CN=example.org", out.certificate.subjectX500Principal.name)
        assertTrue(CertTestFixtures.ipSans(out.certificate).isEmpty())
        assertEquals(0, sniffs.get())
        assertEquals(LeafProfile.Source.SNI, out.profileSource)
    }

    @Test
    fun `IP CONNECT without SNI yields a sniffed-profile leaf cloning the upstream subject and SANs`() = runBlocking {
        val remote = CertTestFixtures.remoteLeaf("real.example.com", sans = listOf("real.example.com", "alt.example.com", "93.184.216.34"))
        val dialed = mutableListOf<String>()
        val e = engine(sniffer = { h, p -> dialed += "$h:$p"; remote })
        val r = CertificateEngine.Request(connectAuthority = "93.184.216.34", port = 8443, sni = null)
        assertTrue(e.wantsUpstreamSniff(r))
        val out = issued(e.issue(context, r))
        assertEquals(listOf("93.184.216.34:8443"), dialed)
        assertEquals(remote.subjectX500Principal, out.certificate.subjectX500Principal)
        assertEquals(listOf("real.example.com", "alt.example.com"), CertTestFixtures.dnsSans(out.certificate))
        assertEquals(listOf("93.184.216.34"), CertTestFixtures.ipSans(out.certificate))
        assertEquals(LeafProfile.Source.UPSTREAM_SNIFF, out.profileSource)
        // Signed by OUR CA, not the remote's key.
        out.certificate.verify(ca.certificate.publicKey)
    }

    @Test
    fun `no upstream available falls back to an IP-SAN leaf`() = runBlocking {
        val e = engine(sniffer = { _, _ -> throw java.io.IOException("unreachable") })
        val out = issued(e.issue(context, CertificateEngine.Request("10.0.0.7", 443, sni = null)))
        assertEquals("CN=10.0.0.7", out.certificate.subjectX500Principal.name)
        assertEquals(listOf("10.0.0.7"), CertTestFixtures.ipSans(out.certificate))
        assertEquals(LeafProfile.Source.IP_FALLBACK, out.profileSource)
    }

    @Test
    fun `SNI-less named CONNECT uses the authority name and never dials the origin`() = runBlocking {
        val e = engine(sniffer = { _, _ -> error("a named CONNECT must not be sniffed (§1.5 mock guarantee)") })
        val r = CertificateEngine.Request("api.example.com", 443, sni = null)
        assertFalse(e.wantsUpstreamSniff(r))
        val out = issued(e.issue(context, r))
        assertEquals("CN=api.example.com", out.certificate.subjectX500Principal.name)
        assertEquals(LeafProfile.Source.CONNECT_AUTHORITY, out.profileSource)
    }

    @Test
    fun `original destination outranks the CONNECT authority but not SNI`() {
        val e = engine()
        val noSni = e.resolveIdentity(CertificateEngine.Request("proxy-hop.local", 443, sni = null, originalDst = "origin.example.com"))
        assertEquals("origin.example.com", noSni.host)
        assertEquals(LeafProfile.Source.ORIGINAL_DST, noSni.source)
        val withSni = e.resolveIdentity(CertificateEngine.Request("proxy-hop.local", 443, sni = "sni.example.com", originalDst = "origin.example.com"))
        assertEquals("sni.example.com", withSni.host)
    }

    @Test
    fun `an IP literal in SNI is ignored as an identity`() {
        val e = engine()
        val id = e.resolveIdentity(CertificateEngine.Request("host.example.com", 443, sni = "192.168.1.1"))
        assertEquals("host.example.com", id.host)
        assertEquals(LeafProfile.Source.CONNECT_AUTHORITY, id.source)
    }

    @Test
    fun `second issue for the same identity is served from the cache and reuses the session key`() = runBlocking {
        val sniffs = AtomicInteger(0)
        val e = engine(sniffer = { _, _ -> sniffs.incrementAndGet(); null })
        val r = CertificateEngine.Request("1.1.1.1", 443, sni = null)
        val a = issued(e.issue(context, r))
        val b = issued(e.issue(context, r))
        assertFalse(a.fromCache)
        assertTrue(b.fromCache)
        assertEquals(a.certificate, b.certificate)
        assertEquals(1, sniffs.get())
        assertEquals(e.sessionKeyPair.public, a.certificate.publicKey)
        assertEquals(e.sessionKeyPair.public, b.certificate.publicKey)
    }

    @Test
    fun `preferUpstreamSniff also sniffs named hosts and a different profile tag is a different cache entry`() = runBlocking {
        val remote = CertTestFixtures.remoteLeaf("www.example.com", sans = listOf("www.example.com", "example.com"))
        val cache = ExpiringLruCertCache()
        val e = engine(sniffer = { _, _ -> remote }, cache = cache)
        val plain = issued(e.issue(context, CertificateEngine.Request("www.example.com", 443, sni = "www.example.com")))
        val mirrored = issued(e.issue(context, CertificateEngine.Request("www.example.com", 443, sni = "www.example.com", preferUpstreamSniff = true)))
        assertEquals("CN=www.example.com", plain.certificate.subjectX500Principal.name)
        assertEquals(remote.subjectX500Principal, mirrored.certificate.subjectX500Principal)
        assertEquals(2, cache.size)
    }

    @Test
    fun `no policy store means no passthrough, no custom cert and no client key managers`() = runBlocking {
        val e = engine()
        assertNull(e.clientKeyManagersFor("anything.example"))
        val out = e.issue(context, CertificateEngine.Request("anything.example", 443, sni = "anything.example"))
        assertTrue(out is CertificateEngine.Outcome.Issued)
        assertNull(out.policy)
        assertNotNull((out as CertificateEngine.Outcome.Issued).keyPair.private)
    }

    @Test
    fun `looksLikeIpLiteral recognises v4 and v6 but not names`() {
        assertTrue(CertificateEngine.looksLikeIpLiteral("10.0.0.1"))
        assertTrue(CertificateEngine.looksLikeIpLiteral("[2001:db8::1]"))
        assertTrue(CertificateEngine.looksLikeIpLiteral("::1"))
        assertFalse(CertificateEngine.looksLikeIpLiteral("example.com"))
        assertFalse(CertificateEngine.looksLikeIpLiteral("1.example"))
        assertFalse(CertificateEngine.looksLikeIpLiteral(""))
    }
}
