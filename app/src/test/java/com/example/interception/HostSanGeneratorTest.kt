package com.example.interception

import org.bouncycastle.asn1.x509.GeneralName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2.15 coverage for [HostSanGenerator]: wildcard SAN generation and IP-literal SAN handling.
 *
 * The wildcard tests specifically exercise the public-suffix-list-backed guard
 * ([okhttp3.HttpUrl.topPrivateDomain]) that replaced a naive label-count check -- a label count
 * alone would happily emit `*.co.uk` for `api.co.uk` or `*.github.io` for `foo.github.io`, both
 * of which impersonate far more than the one real registrant.
 *
 * Pure JVM -- OkHttp is a plain JVM library, so no Robolectric is needed here.
 */
class HostSanGeneratorTest {

    // ── wildcardFor ──────────────────────────────────────────────────

    @Test
    fun `multi-label host gets a wildcard for its registrable parent domain`() {
        assertEquals("*.example.com", HostSanGenerator.wildcardFor("api.example.com"))
    }

    @Test
    fun `www subdomain gets a wildcard for its parent domain`() {
        assertEquals("*.example.com", HostSanGenerator.wildcardFor("www.example.com"))
    }

    @Test
    fun `four-label host wildcards its immediate parent, not the root domain`() {
        assertEquals("*.b.example.com", HostSanGenerator.wildcardFor("a.b.example.com"))
    }

    @Test
    fun `single-label host gets no wildcard`() {
        assertNull(HostSanGenerator.wildcardFor("localhost"))
    }

    @Test
    fun `a bare registrable domain gets no wildcard (its parent is a public suffix)`() {
        assertNull(HostSanGenerator.wildcardFor("example.com"))
    }

    @Test
    fun `host directly under a multi-label public suffix gets no wildcard`() {
        // "co.uk" is a public suffix -- "*.co.uk" would impersonate every other co.uk site,
        // not just subdomains of this one registrant. A label-count-only check (>= 3 labels)
        // would have wrongly allowed this.
        assertNull(HostSanGenerator.wildcardFor("api.co.uk"))
    }

    @Test
    fun `host on a multi-label PaaS public suffix gets no wildcard`() {
        // "github.io" is a public suffix -- "*.github.io" would impersonate every GitHub Pages
        // site, not just this one.
        assertNull(HostSanGenerator.wildcardFor("foo.github.io"))
    }

    @Test
    fun `malformed host with empty labels gets no wildcard`() {
        assertNull(HostSanGenerator.wildcardFor("a..b.com"))
    }

    @Test
    fun `an unparseable host degrades to no wildcard rather than guessing`() {
        assertNull(HostSanGenerator.wildcardFor("not a valid host!"))
    }

    // ── ipGeneralNameOrNull / isIpLiteral ────────────────────────────

    @Test
    fun `IPv4 literal produces an iPAddress GeneralName`() {
        val name = HostSanGenerator.ipGeneralNameOrNull("192.168.1.10")
        assertTrue(name != null)
        assertEquals(GeneralName.iPAddress, name!!.tagNo)
    }

    @Test
    fun `IPv6 literal produces an iPAddress GeneralName`() {
        val name = HostSanGenerator.ipGeneralNameOrNull("2001:db8::1")
        assertTrue(name != null)
        assertEquals(GeneralName.iPAddress, name!!.tagNo)
    }

    @Test
    fun `a normal hostname is not treated as an IP literal`() {
        assertNull(HostSanGenerator.ipGeneralNameOrNull("api.example.com"))
    }

    @Test
    fun `an out-of-range IPv4-looking string is not treated as a valid IP literal`() {
        assertNull(HostSanGenerator.ipGeneralNameOrNull("999.999.999.999"))
    }

    @Test
    fun `an IP literal never gets a wildcard`() {
        assertNull(HostSanGenerator.wildcardFor("192.168.1.10"))
    }

    // ── build ────────────────────────────────────────────────────────

    @Test
    fun `build emits both the exact hostname and the wildcard dNSName for a multi-label host`() {
        val names = HostSanGenerator.build("api.example.com").names
        assertEquals(2, names.size)
        assertTrue(names.all { it.tagNo == GeneralName.dNSName })
        val values = names.map { it.name.toString() }
        assertTrue(values.contains("api.example.com"))
        assertTrue(values.contains("*.example.com"))
    }

    @Test
    fun `build emits only the exact hostname dNSName for a single-label host`() {
        val names = HostSanGenerator.build("localhost").names
        assertEquals(1, names.size)
        assertEquals(GeneralName.dNSName, names[0].tagNo)
        assertEquals("localhost", names[0].name.toString())
    }

    @Test
    fun `build emits only the exact hostname dNSName for a host on a public suffix`() {
        val names = HostSanGenerator.build("api.co.uk").names
        assertEquals(1, names.size)
        assertEquals(GeneralName.dNSName, names[0].tagNo)
        assertEquals("api.co.uk", names[0].name.toString())
    }

    @Test
    fun `build emits only an iPAddress SAN for an IP literal host, never a dNSName`() {
        val names = HostSanGenerator.build("10.0.0.5").names
        assertEquals(1, names.size)
        assertEquals(GeneralName.iPAddress, names[0].tagNo)
    }
}
