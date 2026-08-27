package com.example.interception

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames

/**
 * §2.15: builds the Subject Alternative Name set for a generated per-host MITM leaf certificate.
 *
 * [MitmProxyEngine.generateHostCert] used to emit a single exact-hostname `dNSName` SAN.
 * RoRoIntercept's `CertificateManager.generateServerCertificate()` additionally adds:
 *  - a wildcard `*.<parent-domain>` `dNSName`, which improves HTTP/2 connection-coalescing
 *    compatibility -- clients may reuse one TLS connection across subdomains when a single
 *    cert's SAN set covers both, and some strict clients validate SAN completeness before
 *    coalescing;
 *  - an `iPAddress` `GeneralName` instead of a `dNSName` when the "host" the client actually
 *    connected to is a literal IPv4/IPv6 address rather than a name.
 *
 * Emitting a nonsense wildcard is worse than emitting none. A wildcard label count alone is NOT
 * sufficient to keep it narrow: `api.co.uk` and `foo.github.io` both have three dot-separated
 * labels, but their "parent" (`co.uk`, `github.io`) is a public suffix -- a wildcard there would
 * impersonate every other site under that suffix, not just subdomains of one real registrant.
 * [wildcardFor] instead uses [okhttp3.HttpUrl.topPrivateDomain] (OkHttp's PSL-backed
 * effective-TLD-plus-one) to require the wildcard's parent to be at or below the host's real
 * top-private (registrable) domain. Any parse failure or uncertainty fails closed to "no
 * wildcard" -- the cert cache is keyed by exact host anyway, so a missing wildcard never breaks
 * interception, it only forgoes the HTTP/2-coalescing benefit.
 */
internal object HostSanGenerator {

    /** IPv4 literal syntax (no DNS resolution risk -- purely textual). */
    private val IPV4_SYNTAX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Loose IPv6 literal syntax: hex digits and colons, at least one colon present. */
    private val IPV6_SYNTAX = Regex("^[0-9a-fA-F:]*:[0-9a-fA-F:]*$")

    /**
     * Builds the full SAN [GeneralNames] for [host]: either a single `iPAddress` entry (IP
     * literal host), or the exact-hostname `dNSName` plus an optional wildcard `dNSName`.
     */
    fun build(host: String): GeneralNames {
        val ipName = ipGeneralNameOrNull(host)
        if (ipName != null) return GeneralNames(arrayOf(ipName))

        val names = mutableListOf(GeneralName(GeneralName.dNSName, host))
        wildcardFor(host)?.let { names += GeneralName(GeneralName.dNSName, it) }
        return GeneralNames(names.toTypedArray())
    }

    /**
     * Returns `"*.<parent-domain>"` when [host] has a real registrable parent domain to
     * wildcard over, or `null` when it does not (single-label host, malformed host, IP literal,
     * or -- critically -- when the candidate parent is itself a public suffix or an ancestor of
     * one, e.g. `co.uk` or `github.io`).
     *
     * The parent is only accepted when it equals, or is a subdomain of,
     * [okhttp3.HttpUrl.topPrivateDomain] for [host] -- e.g. for `a.b.example.com` the top private
     * domain is `example.com`, so the parent `b.example.com` qualifies (it is a subdomain of
     * `example.com`); for `api.co.uk` the top private domain is `api.co.uk` itself (because
     * `co.uk` is a public suffix), so the shorter candidate parent `co.uk` does NOT qualify.
     *
     * Any failure to parse [host] as a URL host, or a `null` [okhttp3.HttpUrl.topPrivateDomain]
     * (host is itself a public suffix or an IP literal), fails closed to `null` -- never guesses.
     */
    fun wildcardFor(host: String): String? {
        val labels = host.split(".")
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        val parent = labels.drop(1).joinToString(".")

        val topPrivateDomain = runCatching {
            "https://$host".toHttpUrlOrNull()?.topPrivateDomain()
        }.getOrNull() ?: return null

        val parentIsAtOrBelowTopPrivateDomain =
            parent == topPrivateDomain || parent.endsWith(".$topPrivateDomain")
        return if (parentIsAtOrBelowTopPrivateDomain) "*.$parent" else null
    }

    /**
     * Returns an `iPAddress` [GeneralName] when [host] is a literal IPv4/IPv6 address, or `null`
     * for a normal hostname. Deliberately never performs a DNS lookup: [java.net.InetAddress]
     * only skips DNS resolution when handed a *syntactically* numeric address, so this checks
     * that syntax itself first via [IPV4_SYNTAX]/[IPV6_SYNTAX] before ever calling into it.
     */
    fun ipGeneralNameOrNull(host: String): GeneralName? {
        if (!isIpLiteral(host)) return null
        return GeneralName(GeneralName.iPAddress, host)
    }

    private fun isIpLiteral(host: String): Boolean {
        if (!IPV4_SYNTAX.matches(host) && !IPV6_SYNTAX.matches(host)) return false
        // Safe here: InetAddress.getByName() never does a DNS lookup for a syntactically numeric
        // address (see its javadoc) -- the regex above is exactly what guarantees that, so this
        // call is local/instant and only used to reject syntactically-numeric-but-invalid input
        // (e.g. "999.999.999.999" or a malformed IPv6 literal).
        return runCatching { java.net.InetAddress.getByName(host) }.isSuccess
    }
}
