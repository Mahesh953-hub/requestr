package com.example.interception.rules

/**
 * S4 task 26: the closed allow-list of `x-requestr-*` headers requestr itself stamps on a
 * message to describe what IT did to the exchange. These survive the request/response header
 * logging toggles (see `MitmProxyEngine.emitTransaction`) and are stripped from the bytes that
 * reach the origin server where noted.
 *
 * Kept as an explicit set rather than an `x-requestr-` prefix match on purpose: a prefix match
 * would also retain a header the client app under test happened to send with that prefix, and
 * client content is precisely what the logging toggles suppress.
 */
object RuleMetadataHeaders {
    /** CHAOS attribution: `fail; rule="..."`, `delay=<ms>ms; rule="..."`, `corrupt-body; rule="..."`. */
    const val CHAOS = "x-requestr-chaos"

    /** A script rule failed; value is `<error>; rule="<name>"`. */
    const val SCRIPT_ERROR = "x-requestr-script-error"

    /** A BODY_CRYPTO rule failed closed and this synthetic 502 replaced the real message. */
    const val CRYPTO_FAILED = "x-requestr-crypto-failed"

    /** The persisted body text was pretty-printed for display. */
    const val JSON_PRETTIFIED = "x-requestr-json-prettified"

    /** S4 task 31: the flow was paused at a breakpoint; value is `<phase>; rule="<name>"; <action>`. */
    const val BREAKPOINT = "x-requestr-breakpoint"

    /** S5: the exchange was produced by a replay job; value is the job id. */
    const val REPLAY_ID = "x-requestr-replay-id"

    /** S4 task 28: the flow was mirrored to another host; value is `<target>; rule="<name>"`. */
    const val MIRROR = "x-requestr-mirror"

    /** S4 task 26: comma-separated `stage:ruleId` badges for every rule that acted on the exchange. */
    const val RULES = "x-requestr-rules"

    val KEYS: Set<String> = setOf(
        CHAOS,
        SCRIPT_ERROR,
        CRYPTO_FAILED,
        JSON_PRETTIFIED,
        BREAKPOINT,
        REPLAY_ID,
        MIRROR,
        RULES,
    )

    /** `rule="<name>"` attribution suffix shared by every header that names its rule. */
    fun ruleAttribution(ruleName: String): String = "rule=\"$ruleName\""

    /** True for a header requestr owns -- never forwarded to the origin, always persisted. */
    fun isMetadata(name: String): Boolean = name.lowercase() in KEYS

    /** The [RULES] badge value for a trail: `stage:ruleId` for each distinct APPLIED rule, in order. */
    fun rulesBadge(trail: FlowAuditTrail?): String? {
        if (trail == null || trail.isEmpty) return null
        val badges = LinkedHashSet<String>()
        for (record in trail.toRecords()) {
            if (record.outcome == FlowAuditTrail.Outcome.APPLIED || record.outcome == FlowAuditTrail.Outcome.FAILED) {
                badges += "${record.rule.stage.name.lowercase()}:${record.rule.id}"
            }
        }
        return badges.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    /** [headers] plus the [RULES] badge when [trail] has anything to badge; [headers] unchanged otherwise. */
    fun withRulesBadge(headers: Map<String, String>, trail: FlowAuditTrail?): Map<String, String> {
        val badge = rulesBadge(trail) ?: return headers
        return headers + (RULES to badge)
    }
}
