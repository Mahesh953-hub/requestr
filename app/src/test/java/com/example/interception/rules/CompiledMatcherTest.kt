package com.example.interception.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 task 25 -- [CompiledMatcher], [HostGlob] and [CidrBlock]. Pure JVM (no Robolectric): the
 * matcher must run on relay threads without any Android dependency.
 *
 * The legacy-parity block re-implements the exact `RuleEnforcer.getMatchingRule` predicate
 * (`host.contains(domain, ignoreCase) || runCatching { Regex(pattern, IGNORE_CASE).containsMatchIn(url) }.getOrDefault(false)`)
 * and checks the converted matcher against it over a grid of hosts/urls, including an invalid regex.
 */
class CompiledMatcherTest {

    private fun key(
        host: String = "api.example.com",
        port: Int = 443,
        url: String = "https://$host:$port/v1/users",
        method: String? = "GET",
        headers: Map<String, String> = emptyMap(),
        appPackage: String? = null,
        sourceIp: String? = null,
        upstreamIp: String? = null,
    ) = FlowKey(host, port, url, method, headers, null, appPackage, sourceIp, upstreamIp)

    private fun compile(spec: RuleMatcherSpec) = CompiledMatcher.compile(spec)

    // ── glob ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `host glob semantics`() {
        assertTrue(HostGlob.compile("*.example.com").matches("api.example.com"))
        assertTrue(HostGlob.compile("*.example.com").matches("a.b.example.com"))
        assertFalse(HostGlob.compile("*.example.com").matches("example.com"))
        assertTrue(HostGlob.compile("*example.com").matches("example.com"))
        assertTrue(HostGlob.compile("api-?.example.com").matches("api-1.example.com"))
        assertFalse(HostGlob.compile("api-?.example.com").matches("api-10.example.com"))
        assertTrue(HostGlob.compile("*login*").matches("sso.LOGIN.corp"))
        assertTrue(HostGlob.compile("API.EXAMPLE.COM").matches("api.example.com"))
        // Regex metacharacters in the glob are literal.
        assertFalse(HostGlob.compile("a.b").matches("aXb"))
        assertTrue(HostGlob.compile("a+b").matches("a+b"))
        assertFalse(HostGlob.compile("a+b").matches("aab"))
        assertTrue(HostGlob.compile("*").matches("anything"))
        assertTrue(HostGlob.compile("*").matches(""))
    }

    @Test
    fun `hostGlobs predicate is any-of over the list`() {
        val m = compile(RuleMatcherSpec(hostGlobs = listOf("*.a.com", "*.b.com")))
        assertTrue(m.matches(key(host = "x.a.com")))
        assertTrue(m.matches(key(host = "x.b.com")))
        assertFalse(m.matches(key(host = "x.c.com")))
    }

    // ── regex ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `urlRegex is containsMatchIn, case-insensitive`() {
        val m = compile(RuleMatcherSpec(urlRegex = "/V1/USERS"))
        assertTrue(m.matches(key(url = "https://api.example.com/v1/users?x=1")))
        assertFalse(m.matches(key(url = "https://api.example.com/v2/users")))
    }

    @Test
    fun `invalid urlRegex becomes an always-false predicate and is reported, never thrown`() {
        val m = compile(RuleMatcherSpec(urlRegex = "([unclosed", hostGlobs = listOf("*example*"), combine = MatchCombine.ANY))
        assertEquals(1, m.errors.size)
        assertTrue(m.errors[0].contains("urlRegex"))
        // ANY: the host side still matches, exactly like the legacy `getOrDefault(false)` OR.
        assertTrue(m.matches(key(host = "api.example.com")))
        assertFalse(m.matches(key(host = "other.org", url = "https://other.org/([unclosed")))

        val all = compile(RuleMatcherSpec(urlRegex = "([unclosed"))
        assertFalse(all.matches(key()))
        assertFalse(all.isCatchAll)
    }

    // ── method / port / app / headers ─────────────────────────────────────────────────────────

    @Test
    fun `methods are compared upper-cased and require a method on the key`() {
        val m = compile(RuleMatcherSpec(methods = listOf("post", "PUT")))
        assertTrue(m.matches(key(method = "POST")))
        assertTrue(m.matches(key(method = "put")))
        assertFalse(m.matches(key(method = "GET")))
        assertFalse(m.matches(key(method = null)))
    }

    @Test
    fun `port range is inclusive`() {
        val m = compile(RuleMatcherSpec(portRange = PortRange(8000, 8999)))
        assertTrue(m.matches(key(port = 8000)))
        assertTrue(m.matches(key(port = 8999)))
        assertFalse(m.matches(key(port = 443)))
        assertTrue(compile(RuleMatcherSpec(portRange = PortRange.single(443))).matches(key(port = 443)))
    }

    @Test
    fun `app packages match exactly`() {
        val m = compile(RuleMatcherSpec(appPackages = listOf("com.example.app")))
        assertTrue(m.matches(key(appPackage = "com.example.app")))
        assertFalse(m.matches(key(appPackage = "com.example.app.debug")))
        assertFalse(m.matches(key(appPackage = null)))
    }

    @Test
    fun `header matchers are all-of, name case-insensitive, presence-only when regex is null`() {
        val m = compile(RuleMatcherSpec(headerMatchers = listOf(HeaderMatcher("X-Env", "^stag"), HeaderMatcher("authorization"))))
        assertTrue(m.matches(key(headers = mapOf("x-env" to "staging", "Authorization" to "Bearer x"))))
        assertTrue(m.matches(key(headers = mapOf("X-ENV" to "STAGING-2", "authorization" to ""))))
        assertFalse(m.matches(key(headers = mapOf("x-env" to "prod", "authorization" to "b"))))
        assertFalse(m.matches(key(headers = mapOf("x-env" to "staging"))))
        assertFalse(m.matches(key()))

        val broken = compile(RuleMatcherSpec(headerMatchers = listOf(HeaderMatcher("x", "(("))))
        assertEquals(1, broken.errors.size)
        assertFalse(broken.matches(key(headers = mapOf("x" to "(("))))
    }

    // ── CIDR ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cidr parsing and containment for v4 and v6`() {
        val ten = CidrBlock.parse("10.0.0.0/8")!!
        assertTrue(ten.contains("10.255.1.2"))
        assertFalse(ten.contains("11.0.0.1"))
        val slash20 = CidrBlock.parse("192.168.16.0/20")!!
        assertTrue(slash20.contains("192.168.31.255"))
        assertFalse(slash20.contains("192.168.32.0"))
        // Host bits in the block text are ignored (normalised network).
        assertTrue(CidrBlock.parse("192.168.17.9/20")!!.contains("192.168.16.1"))
        // Bare address == /32.
        val single = CidrBlock.parse("1.2.3.4")!!
        assertTrue(single.contains("1.2.3.4"))
        assertFalse(single.contains("1.2.3.5"))
        assertTrue(CidrBlock.parse("0.0.0.0/0")!!.contains("203.0.113.9"))

        val v6 = CidrBlock.parse("2001:db8::/32")!!
        assertTrue(v6.contains("2001:db8:1::1"))
        assertTrue(v6.contains("[2001:DB8::]"))
        assertFalse(v6.contains("2001:db9::1"))
        assertTrue(CidrBlock.parse("fd00::/8")!!.contains("fdab::1%wlan0"))
        // Family mismatch is simply "not contained".
        assertFalse(v6.contains("10.0.0.1"))
        assertFalse(ten.contains("::1"))

        assertNull(CidrBlock.parse("10.0.0.0/33"))
        assertNull(CidrBlock.parse("10.0.0/8"))
        assertNull(CidrBlock.parse("300.0.0.0/8"))
        assertNull(CidrBlock.parse("2001:db8::/129"))
        assertNull(CidrBlock.parse("example.com/8"))
        assertNull(CidrBlock.parse(""))
        assertNull(CidrBlock.parse("10.0.0.0/x"))
        // Never resolves a hostname.
        assertNull(CidrBlock.parseLiteral("localhost"))
        assertFalse(ten.contains("api.example.com"))
    }

    @Test
    fun `ipCidrs prefer the upstream ip and fall back to the source ip`() {
        val m = compile(RuleMatcherSpec(ipCidrs = listOf("10.0.0.0/8", "fd00::/8")))
        assertTrue(m.matches(key(upstreamIp = "10.1.2.3")))
        assertTrue(m.matches(key(sourceIp = "fd12::9")))
        // Upstream known and outside the block: the source ip is not consulted.
        assertFalse(m.matches(key(upstreamIp = "8.8.8.8", sourceIp = "10.0.0.1")))
        assertFalse(m.matches(key()))

        val someBad = compile(RuleMatcherSpec(ipCidrs = listOf("nope", "10.0.0.0/8")))
        assertEquals(1, someBad.errors.size)
        assertTrue(someBad.matches(key(upstreamIp = "10.0.0.1")))
        val allBad = compile(RuleMatcherSpec(ipCidrs = listOf("nope")))
        assertFalse(allBad.matches(key(upstreamIp = "10.0.0.1")))
    }

    // ── combine ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ALL requires every constrained dimension, unconstrained ALL is a catch-all`() {
        val m = compile(RuleMatcherSpec(hostGlobs = listOf("*.example.com"), methods = listOf("POST"), portRange = PortRange.single(443)))
        assertTrue(m.matches(key(method = "POST")))
        assertFalse(m.matches(key(method = "GET")))
        assertFalse(m.matches(key(method = "POST", port = 8443)))
        assertFalse(m.matches(key(host = "x.other.com", method = "POST")))

        val all = compile(RuleMatcherSpec.MATCH_ALL)
        assertTrue(all.isCatchAll)
        assertFalse(all.matchesNothing)
        assertTrue(all.matches(key()))
        assertTrue(all.matches(key(host = "", port = 0, url = "", method = null)))
    }

    @Test
    fun `ANY requires one constrained dimension, unconstrained ANY matches nothing`() {
        val m = compile(RuleMatcherSpec(hostGlobs = listOf("*.a.com"), methods = listOf("DELETE"), combine = MatchCombine.ANY))
        assertTrue(m.matches(key(host = "x.a.com", method = "GET")))
        assertTrue(m.matches(key(host = "x.b.com", method = "DELETE")))
        assertFalse(m.matches(key(host = "x.b.com", method = "GET")))

        val none = compile(RuleMatcherSpec(combine = MatchCombine.ANY))
        assertTrue(none.matchesNothing)
        assertFalse(none.isCatchAll)
        assertFalse(none.matches(key()))
    }

    // ── legacy parity ─────────────────────────────────────────────────────────────────────────

    private fun legacyMatches(domain: String, pattern: String, host: String, url: String): Boolean {
        val matchesDomain = domain.isNotEmpty() && host.contains(domain, ignoreCase = true)
        val matchesPattern = pattern.isNotEmpty() && runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url) }.getOrDefault(false)
        return matchesDomain || matchesPattern
    }

    @Test
    fun `converted legacy matchers agree with RuleEnforcer's predicate over a grid`() {
        val legacyRows = listOf(
            "ads.example" to "",
            "" to "/track(ing)?/",
            "EXAMPLE" to "^https://api\\.",
            "" to "",
            "a.b" to "",
            "cdn" to "([unclosed",
            "" to "([unclosed",
            "." to "",
        )
        val hosts = listOf("ads.example", "api.example.com", "aXb.net", "cdn.other", "example", "", "x")
        val urls = listOf("https://api.example.com/tracking/x", "http://cdn.other/track/", "https://aXb.net/", "", "https://ads.example:443/")
        for ((domain, pattern) in legacyRows) {
            val spec = LegacyRuleConverter.matcherOf(LegacyInterceptRule(id = 1, name = "r", pattern = pattern, domain = domain, action = LegacyRuleAction.LOG_ONLY))
            val compiled = compile(spec)
            for (host in hosts) for (url in urls) {
                assertEquals(
                    "domain='$domain' pattern='$pattern' host='$host' url='$url'",
                    legacyMatches(domain, pattern, host, url),
                    compiled.matches(FlowKey(host = host, port = 443, url = url)),
                )
            }
        }
    }
}
