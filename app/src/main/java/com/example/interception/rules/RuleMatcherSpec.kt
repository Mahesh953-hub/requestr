package com.example.interception.rules

import com.squareup.moshi.JsonClass

/**
 * S4 task 24 -- the *declarative* matcher half of a typed rule, persisted as
 * `interceptor_rules.matcherJson`. This is pure data: nothing here compiles a regex or parses a
 * CIDR. Compilation happens once per snapshot revision in `RuleSnapshotStore` (task 25), and
 * matching runs only on the compiled form, so a relay thread never recompiles a pattern.
 *
 * Every list is a constraint; an empty list means "no constraint on that dimension". How the
 * non-empty constraints combine is decided by [combine]:
 *
 *  - [MatchCombine.ALL] (default for rules authored in the typed editors): every non-empty
 *    constraint must match. A spec with every list empty matches **every** flow -- this is what a
 *    "catch-all" throttle or observe rule looks like.
 *  - [MatchCombine.ANY]: at least one non-empty constraint must match. A spec with every list
 *    empty matches **nothing**. This exists for exactly one reason: the legacy `intercept_rules`
 *    matcher is `host.contains(domain) OR pattern.containsMatchIn(url)`, and G16 demands
 *    byte-identical behaviour for every backfilled row, so `LegacyRuleConverter` emits `ANY`.
 *    A legacy rule with both `domain` and `pattern` blank never matched anything, and its converted
 *    form must not suddenly become a catch-all.
 *
 * Field semantics (enforced by the compiled matcher in task 25):
 *  - [hostGlobs]: case-insensitive glob over the target host; `*` matches any run of characters
 *    (including dots), `?` one character. `*.example.com`, `api-?.example.com`, `*login*`.
 *  - [urlRegex]: Java/Kotlin regex, `IGNORE_CASE`, `containsMatchIn` over the full URL -- the same
 *    contract the legacy `pattern` column had.
 *  - [methods]: upper-cased HTTP method names.
 *  - [portRange]: inclusive `[first, last]`, or null for any port.
 *  - [ipCidrs]: IPv4 or IPv6 CIDR blocks (`10.0.0.0/8`, `fd00::/8`) matched against the *resolved
 *    upstream address* when known, else against `FlowKey.sourceIp`.
 *  - [headerMatchers]: each must be present on the request with a value matching [HeaderMatcher.valueRegex]
 *    (or merely present when the regex is null).
 *  - [appPackages]: client package names (from `UidAttributor`); matched exactly.
 */
@JsonClass(generateAdapter = true)
data class RuleMatcherSpec(
    val hostGlobs: List<String> = emptyList(),
    val urlRegex: String? = null,
    val methods: List<String> = emptyList(),
    val portRange: PortRange? = null,
    val ipCidrs: List<String> = emptyList(),
    val headerMatchers: List<HeaderMatcher> = emptyList(),
    val appPackages: List<String> = emptyList(),
    val combine: MatchCombine = MatchCombine.ALL,
) {
    /** True when no dimension is constrained. Under [MatchCombine.ALL] this is a catch-all; under [MatchCombine.ANY] it matches nothing. */
    val isUnconstrained: Boolean
        get() = hostGlobs.isEmpty() && urlRegex.isNullOrEmpty() && methods.isEmpty() && portRange == null &&
            ipCidrs.isEmpty() && headerMatchers.isEmpty() && appPackages.isEmpty()

    companion object {
        /** Matches every flow (ALL with no constraints). */
        val MATCH_ALL: RuleMatcherSpec = RuleMatcherSpec()
    }
}

/** How a [RuleMatcherSpec]'s non-empty constraints combine. See the class KDoc. */
enum class MatchCombine { ALL, ANY }

/** Inclusive port range. `PortRange(443, 443)` is the single port 443. */
@JsonClass(generateAdapter = true)
data class PortRange(val first: Int, val last: Int) {
    init {
        require(first in 0..65535 && last in 0..65535 && first <= last) { "invalid port range $first..$last" }
    }

    operator fun contains(port: Int): Boolean = port in first..last

    companion object {
        fun single(port: Int) = PortRange(port, port)
    }
}

/**
 * A request-header constraint. [name] is compared case-insensitively. When [valueRegex] is null the
 * header only has to be present; otherwise its value must `containsMatchIn` the regex
 * (`IGNORE_CASE`).
 */
@JsonClass(generateAdapter = true)
data class HeaderMatcher(val name: String, val valueRegex: String? = null)

/**
 * Everything a matcher may look at for one flow. Built once per flow by the engine that owns the
 * connection (`MitmProxyEngine`, `CaptureEngine`, `ConnectControl`) and handed to
 * `RulePipeline.evaluate` (task 26). Immutable; cheap to construct.
 *
 * At CONNECT time only [host], [port], [clientUid], [appPackage] and [sourceIp] are known --
 * [url] is then the synthetic `https://host:port/` the legacy `ConnectControl` already used, and
 * [method]/[headers] are null/empty. The typed matcher makes [clientUid], [appPackage] and
 * [sourceIp] usable; `ConnectControl.evaluate` already receives them but the legacy matcher
 * ignored them.
 */
data class FlowKey(
    val host: String,
    val port: Int,
    val url: String,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val clientUid: Int? = null,
    val appPackage: String? = null,
    val sourceIp: String? = null,
    /** Resolved upstream address when the dial has happened or the tun path knows it; null at CONNECT time. */
    val upstreamIp: String? = null,
) {
    companion object {
        /** The CONNECT-time key, matching the synthetic URL `ConnectControl` has always evaluated against. */
        fun forConnect(host: String, port: Int, clientUid: Int? = null, appPackage: String? = null, sourceIp: String? = null) =
            FlowKey(host = host, port = port, url = "https://$host:$port/", clientUid = clientUid, appPackage = appPackage, sourceIp = sourceIp)
    }
}
