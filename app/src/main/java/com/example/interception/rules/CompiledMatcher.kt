package com.example.interception.rules

import java.net.InetAddress
import java.util.regex.PatternSyntaxException

/**
 * S4 task 25 -- the executable form of a [RuleMatcherSpec]. Compiled once per snapshot revision
 * by [RuleSnapshotStore]; [matches] runs on relay threads and performs no allocation beyond the
 * regex engine's own, no I/O, no DNS and never throws.
 *
 * Semantics (the contract stated on [RuleMatcherSpec]):
 *  - each constrained dimension becomes one predicate; empty dimensions contribute no predicate;
 *  - [MatchCombine.ALL]: every predicate must hold (no predicates -> matches every flow);
 *  - [MatchCombine.ANY]: at least one predicate must hold (no predicates -> matches nothing).
 *
 * A dimension that cannot be compiled (an invalid regex or CIDR) is kept as a predicate that is
 * always false and reported through [errors]. This reproduces the legacy `RuleEnforcer` behaviour
 * exactly -- `runCatching { Regex(pattern) }.getOrDefault(false)` -- so a backfilled row with a
 * broken `pattern` still matches on `domain` under `ANY`, and the editor can show the error
 * instead of the rule silently vanishing from the snapshot.
 */
class CompiledMatcher private constructor(
    val spec: RuleMatcherSpec,
    private val predicates: Array<(FlowKey) -> Boolean>,
    /** Non-empty when a dimension failed to compile; see the class KDoc. */
    val errors: List<String>,
) {
    val isCatchAll: Boolean get() = predicates.isEmpty() && spec.combine == MatchCombine.ALL
    val matchesNothing: Boolean get() = predicates.isEmpty() && spec.combine == MatchCombine.ANY

    fun matches(key: FlowKey): Boolean {
        val ps = predicates
        return when (spec.combine) {
            MatchCombine.ALL -> {
                for (p in ps) if (!p(key)) return false
                true
            }
            MatchCombine.ANY -> {
                for (p in ps) if (p(key)) return true
                false
            }
        }
    }

    companion object {
        private val NEVER: (FlowKey) -> Boolean = { false }

        fun compile(spec: RuleMatcherSpec): CompiledMatcher {
            val predicates = ArrayList<(FlowKey) -> Boolean>(7)
            val errors = ArrayList<String>(0)

            if (spec.hostGlobs.isNotEmpty()) {
                val globs = spec.hostGlobs.map { HostGlob.compile(it) }
                predicates += { key -> val h = key.host; globs.any { it.matches(h) } }
            }
            spec.urlRegex?.takeIf { it.isNotEmpty() }?.let { pattern ->
                try {
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    predicates += { key -> regex.containsMatchIn(key.url) }
                } catch (e: PatternSyntaxException) {
                    errors += "urlRegex '$pattern' is not a valid regex: ${e.description}"
                    predicates += NEVER
                }
            }
            if (spec.methods.isNotEmpty()) {
                val methods = spec.methods.mapTo(HashSet()) { it.uppercase() }
                predicates += { key -> val m = key.method; m != null && methods.contains(m.uppercase()) }
            }
            spec.portRange?.let { range ->
                predicates += { key -> key.port in range }
            }
            if (spec.ipCidrs.isNotEmpty()) {
                val blocks = ArrayList<CidrBlock>(spec.ipCidrs.size)
                for (cidr in spec.ipCidrs) {
                    val block = CidrBlock.parse(cidr)
                    if (block == null) errors += "ipCidr '$cidr' is not a valid CIDR block" else blocks += block
                }
                if (blocks.isEmpty()) {
                    predicates += NEVER
                } else {
                    predicates += { key ->
                        val ip = key.upstreamIp ?: key.sourceIp
                        ip != null && blocks.any { it.contains(ip) }
                    }
                }
            }
            if (spec.headerMatchers.isNotEmpty()) {
                val compiled = ArrayList<Pair<String, Regex?>>(spec.headerMatchers.size)
                var broken = false
                for (hm in spec.headerMatchers) {
                    val regex = hm.valueRegex?.takeIf { it.isNotEmpty() }?.let { pattern ->
                        try {
                            Regex(pattern, RegexOption.IGNORE_CASE)
                        } catch (e: PatternSyntaxException) {
                            errors += "header '${hm.name}' valueRegex '$pattern' is not a valid regex: ${e.description}"
                            broken = true
                            null
                        }
                    }
                    compiled += hm.name.lowercase() to regex
                }
                if (broken) {
                    predicates += NEVER
                } else {
                    predicates += { key ->
                        val headers = key.headers
                        compiled.all { (name, regex) ->
                            val value = headerValue(headers, name)
                            value != null && (regex == null || regex.containsMatchIn(value))
                        }
                    }
                }
            }
            if (spec.appPackages.isNotEmpty()) {
                val pkgs = spec.appPackages.toHashSet()
                predicates += { key -> val p = key.appPackage; p != null && pkgs.contains(p) }
            }
            return CompiledMatcher(spec, predicates.toTypedArray(), errors)
        }

        /** Case-insensitive header lookup without allocating a lower-cased copy of the map on the hot path. */
        private fun headerValue(headers: Map<String, String>, lowerName: String): String? {
            headers[lowerName]?.let { return it }
            for ((k, v) in headers) if (k.equals(lowerName, ignoreCase = true)) return v
            return null
        }
    }
}

/**
 * A host glob: `*` matches any run of characters (dots included), `?` exactly one; everything else
 * is literal and compared case-insensitively. Compiled to an anchored regex once.
 */
class HostGlob private constructor(val source: String, private val regex: Regex) {
    fun matches(host: String): Boolean = regex.matches(host)

    companion object {
        fun compile(glob: String): HostGlob {
            val sb = StringBuilder(glob.length + 8)
            var literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) {
                    sb.append(Regex.escape(literal.toString()))
                    literal = StringBuilder()
                }
            }
            for (ch in glob) {
                when (ch) {
                    '*' -> { flush(); sb.append(".*") }
                    '?' -> { flush(); sb.append('.') }
                    else -> literal.append(ch)
                }
            }
            flush()
            return HostGlob(glob, Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)))
        }
    }
}

/**
 * An IPv4 or IPv6 CIDR block. [contains] accepts a textual address and never throws; a literal
 * that does not parse (or is the wrong family) is simply not contained. Parsing is literal-only:
 * no hostname is ever resolved.
 */
class CidrBlock private constructor(val source: String, private val network: ByteArray, private val prefixLength: Int) {

    fun contains(ip: String): Boolean {
        val addr = parseLiteral(ip) ?: return false
        return contains(addr)
    }

    fun contains(addr: ByteArray): Boolean {
        if (addr.size != network.size) return false
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) if (addr[i] != network[i]) return false
        val rem = prefixLength % 8
        if (rem == 0) return true
        val mask = (0xFF shl (8 - rem)) and 0xFF
        return (addr[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        /** `a.b.c.d/n`, `a.b.c.d` (= /32), `xx::/n`, `[xx::]/n`; null when malformed. */
        fun parse(cidr: String): CidrBlock? {
            val text = cidr.trim()
            val slash = text.lastIndexOf('/')
            val addrText = if (slash >= 0) text.substring(0, slash) else text
            val addr = parseLiteral(addrText) ?: return null
            val bits = addr.size * 8
            val prefix = if (slash >= 0) text.substring(slash + 1).toIntOrNull() ?: return null else bits
            if (prefix < 0 || prefix > bits) return null
            // Normalise the network address so host bits never affect the comparison.
            val net = addr.copyOf()
            val fullBytes = prefix / 8
            val rem = prefix % 8
            if (fullBytes < net.size) {
                if (rem != 0) net[fullBytes] = (net[fullBytes].toInt() and ((0xFF shl (8 - rem)) and 0xFF)).toByte()
                for (i in (fullBytes + if (rem != 0) 1 else 0) until net.size) net[i] = 0
            }
            return CidrBlock(text, net, prefix)
        }

        /** Parses a textual IPv4/IPv6 literal (optionally bracketed, optionally zone-scoped) to bytes; null when it is not a literal. */
        fun parseLiteral(ip: String): ByteArray? {
            var s = ip.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
            IPV4.matchExpression(s)?.let { return it }
            if (s.indexOf(':') < 0) return null // not IPv6 and not IPv4 -> a hostname; never resolve it
            val zone = s.indexOf('%')
            if (zone >= 0) s = s.substring(0, zone)
            return try {
                // A literal IPv6 string never triggers a DNS lookup in InetAddress.getByName.
                InetAddress.getByName(s).address.takeIf { it.size == 16 }
            } catch (_: Exception) {
                null
            }
        }

        private fun Regex.matchExpression(s: String): ByteArray? {
            val m = matchEntire(s) ?: return null
            val out = ByteArray(4)
            for (i in 0 until 4) {
                val v = m.groupValues[i + 1].toIntOrNull() ?: return null
                if (v < 0 || v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }
    }
}
