package com.example.interception.rules

/**
 * S4 task 26: the staged rule pipeline -- one pure evaluation of a [RuleSnapshot] against a
 * [FlowKey] producing an immutable [RuleDecision] that every stage consumer reads from.
 *
 * Contract (sub-plan §4, master task 26):
 *  - **Zero DAO calls.** Evaluation touches only the compiled snapshot handed in; the caller
 *    picks it up from [RuleSnapshotStore.current]. The `RuleSnapshotStoreTest` "throwing DAO"
 *    test and [RulePipelineTest] both rely on this.
 *  - **Per-slot precedence, not first-match-wins across stages.** The legacy engine applied the
 *    first matching row whatever its action. The pipeline resolves each stage independently:
 *      - GATEWAY: `ALLOW_ONLY > BYPASS > BLOCK > SUSPEND > FORCE_DECRYPT` regardless of list
 *        order ([GatewayOutcome]). The presence of *any* enabled ALLOW_ONLY rule in the snapshot
 *        flips the default: a flow matching none of them is [GatewayOutcome.ImpliedBlock].
 *      - REWRITE: at most one redirection (the first matching rule that carries one), at most
 *        one request replacement and one response replacement (first each), and *every*
 *        matching rule's modifications in list order ([ResolvedRewrite]).
 *      - MIRROR / BREAKPOINT / MOCK / BODY_CRYPTO / THROTTLE / CHAOS: first match wins; later
 *        matches are recorded as [RuleDecision.skipped] with reason `precedence`.
 *      - SCRIPT: every matching script, in list order.
 *      - OBSERVE: every matching observe rule (drives `forcePersist` on the passive path).
 *  - Evaluation is allocation-light and synchronous: G6 requires p95 < 1 ms against a 200-rule
 *    snapshot on-device, so there is no reflection, no coroutine, no logging in the hot loop.
 */
object RulePipeline {

    fun evaluate(snapshot: RuleSnapshot, key: FlowKey): RuleDecision {
        if (snapshot.isEmpty) return RuleDecision.none(snapshot.revision)

        var gatewayBest: CompiledRule? = null
        var gatewayBestRank = Int.MAX_VALUE
        var mirror: Matched<MirrorConfig>? = null
        var breakpoint: Matched<BreakpointConfig>? = null
        var mock: Matched<MockConfig>? = null
        var bodyCrypto: Matched<BodyCryptoConfig>? = null
        var throttle: Matched<ThrottleConfig>? = null
        var chaos: Matched<ChaosConfig>? = null
        var redirection: Matched<RewriteConfig>? = null
        var replaceRequest: Matched<RewriteConfig>? = null
        var replaceResponse: Matched<RewriteConfig>? = null
        var rewrites: ArrayList<Matched<RewriteConfig>>? = null
        var scripts: ArrayList<Matched<ScriptConfig>>? = null
        var observe: ArrayList<RuleRef>? = null
        var skipped: ArrayList<Skipped>? = null
        val matched = ArrayList<RuleRef>(4)
        var matchedAllowOnly = false

        val rules = snapshot.rules
        for (i in rules.indices) {
            val rule = rules[i]
            if (!rule.matches(key)) continue
            matched += rule.ref
            when (val config = rule.config) {
                is GatewayConfig -> {
                    if (config.mode == GatewayMode.ALLOW_ONLY) matchedAllowOnly = true
                    val rank = gatewayRank(config.mode)
                    if (rank < gatewayBestRank) {
                        val loser = gatewayBest
                        if (loser != null) skipped = addSkipped(skipped, loser.ref, "precedence: ${config.mode} outranks ${(loser.config as GatewayConfig).mode}")
                        gatewayBest = rule
                        gatewayBestRank = rank
                    } else {
                        skipped = addSkipped(skipped, rule.ref, "precedence: ${(gatewayBest!!.config as GatewayConfig).mode} outranks ${config.mode}")
                    }
                }
                is MirrorConfig -> if (mirror == null) mirror = Matched(rule.ref, config) else skipped = addSkipped(skipped, rule.ref, PRECEDENCE_FIRST_MATCH)
                is RewriteConfig -> {
                    if (rewrites == null) rewrites = ArrayList(2)
                    rewrites.add(Matched(rule.ref, config))
                    if (config.redirection != null) {
                        if (redirection == null) redirection = Matched(rule.ref, config)
                        else skipped = addSkipped(skipped, rule.ref, "precedence: redirection already claimed by rule #${redirection.rule.id}")
                    }
                    if (config.replaceRequest != null && replaceRequest == null) replaceRequest = Matched(rule.ref, config)
                    if (config.replaceResponse != null && replaceResponse == null) replaceResponse = Matched(rule.ref, config)
                }
                is BreakpointConfig -> if (breakpoint == null) breakpoint = Matched(rule.ref, config) else skipped = addSkipped(skipped, rule.ref, PRECEDENCE_FIRST_MATCH)
                is ScriptConfig -> {
                    if (scripts == null) scripts = ArrayList(2)
                    scripts.add(Matched(rule.ref, config))
                }
                is MockConfig -> if (mock == null) mock = Matched(rule.ref, config) else skipped = addSkipped(skipped, rule.ref, PRECEDENCE_FIRST_MATCH)
                is BodyCryptoConfig -> if (bodyCrypto == null) bodyCrypto = Matched(rule.ref, config) else skipped = addSkipped(skipped, rule.ref, PRECEDENCE_FIRST_MATCH)
                is ThrottleConfig -> if (throttle == null) throttle = Matched(rule.ref, config) else skipped = addSkipped(skipped, rule.ref, PRECEDENCE_FIRST_MATCH)
                is ChaosConfig -> if (chaos == null) chaos = Matched(rule.ref, config) else skipped = addSkipped(skipped, rule.ref, PRECEDENCE_FIRST_MATCH)
                is ObserveConfig -> {
                    if (observe == null) observe = ArrayList(2)
                    observe.add(rule.ref)
                }
            }
        }

        val gateway: GatewayOutcome = when {
            gatewayBest != null -> {
                val cfg = gatewayBest.config as GatewayConfig
                GatewayOutcome.Apply(cfg.mode, gatewayBest.ref, cfg)
            }
            !matchedAllowOnly && snapshot.allowOnlyGatewayRules.isNotEmpty() ->
                GatewayOutcome.ImpliedBlock(snapshot.allowOnlyGatewayRules.map { it.ref })
            else -> GatewayOutcome.Pass
        }

        return RuleDecision(
            revision = snapshot.revision,
            gateway = gateway,
            mirror = mirror,
            rewrite = if (rewrites == null) ResolvedRewrite.EMPTY else ResolvedRewrite(redirection, replaceRequest, replaceResponse, rewrites),
            breakpoint = breakpoint,
            scripts = scripts ?: emptyList(),
            mock = mock,
            bodyCrypto = bodyCrypto,
            throttle = throttle,
            chaos = chaos,
            observe = observe ?: emptyList(),
            matchedRules = matched,
            skipped = skipped ?: emptyList(),
        )
    }

    /** Convenience for the CONNECT path: gateway + mirror only need host/port. */
    fun evaluateConnect(snapshot: RuleSnapshot, host: String, port: Int, clientUid: Int? = null, appPackage: String? = null, sourceIp: String? = null): RuleDecision =
        evaluate(snapshot, FlowKey.forConnect(host, port, clientUid, appPackage, sourceIp))

    /** Lower rank wins: `ALLOW_ONLY > BYPASS > BLOCK > SUSPEND > FORCE_DECRYPT`. */
    fun gatewayRank(mode: GatewayMode): Int = when (mode) {
        GatewayMode.ALLOW_ONLY -> 0
        GatewayMode.BYPASS -> 1
        GatewayMode.BLOCK -> 2
        GatewayMode.SUSPEND -> 3
        GatewayMode.FORCE_DECRYPT -> 4
    }

    const val PRECEDENCE_FIRST_MATCH = "precedence: an earlier rule in this stage already matched"

    private fun addSkipped(list: ArrayList<Skipped>?, rule: RuleRef, reason: String): ArrayList<Skipped> {
        val target = list ?: ArrayList(2)
        target.add(Skipped(rule, reason))
        return target
    }
}

/** A rule that matched, paired with its typed config, so consumers can attribute what they did. */
data class Matched<out C : RuleConfig>(val rule: RuleRef, val config: C)

/** A rule that matched the flow but lost its stage's precedence contest. */
data class Skipped(val rule: RuleRef, val reason: String)

/** Gateway slot verdict. See [RulePipeline] for the precedence contract. */
sealed interface GatewayOutcome {
    /** No gateway rule matched and no ALLOW_ONLY rule exists: the flow proceeds unmodified. */
    data object Pass : GatewayOutcome

    /** The winning gateway rule for this flow. `mode == ALLOW_ONLY` means "explicitly allowed". */
    data class Apply(val mode: GatewayMode, val rule: RuleRef, val config: GatewayConfig) : GatewayOutcome

    /** At least one ALLOW_ONLY rule is enabled and this flow matched none of them. */
    data class ImpliedBlock(val allowOnlyRules: List<RuleRef>) : GatewayOutcome

    /** The effective mode, or null when nothing constrains this flow. */
    val effectiveMode: GatewayMode?
        get() = when (this) {
            Pass -> null
            is Apply -> if (mode == GatewayMode.ALLOW_ONLY) null else mode
            is ImpliedBlock -> GatewayMode.BLOCK
        }

    val blocks: Boolean get() = effectiveMode == GatewayMode.BLOCK
}

/**
 * Resolved REWRITE slot: [redirection] is the (at most one) redirecting rule, [replaceRequest] /
 * [replaceResponse] the first rule carrying each replacement, and [rules] every matching
 * rewrite rule in list order -- the applier walks [rules] for modifications so per-rule
 * `variables` stay attached to the modifications they template.
 */
class ResolvedRewrite(
    val redirection: Matched<RewriteConfig>?,
    val replaceRequest: Matched<RewriteConfig>?,
    val replaceResponse: Matched<RewriteConfig>?,
    val rules: List<Matched<RewriteConfig>>,
) {
    val isEmpty: Boolean get() = rules.isEmpty()
    val hasRequestWork: Boolean
        get() = redirection != null || replaceRequest != null || rules.any { it.config.requestModifications.isNotEmpty() }
    val hasResponseWork: Boolean
        get() = replaceResponse != null || rules.any { it.config.responseModifications.isNotEmpty() }

    companion object {
        val EMPTY = ResolvedRewrite(null, null, null, emptyList())
    }
}

/**
 * Immutable outcome of one [RulePipeline.evaluate]. Built once per flow (CONNECT, or the
 * request leg), stashed with the pending exchange and reused on the response leg -- there is
 * no second lookup, so both legs see the same snapshot [revision].
 */
class RuleDecision(
    val revision: Long,
    val gateway: GatewayOutcome,
    val mirror: Matched<MirrorConfig>?,
    val rewrite: ResolvedRewrite,
    val breakpoint: Matched<BreakpointConfig>?,
    val scripts: List<Matched<ScriptConfig>>,
    val mock: Matched<MockConfig>?,
    val bodyCrypto: Matched<BodyCryptoConfig>?,
    val throttle: Matched<ThrottleConfig>?,
    val chaos: Matched<ChaosConfig>?,
    val observe: List<RuleRef>,
    /** Every rule that matched, in snapshot order (winners and precedence losers alike). */
    val matchedRules: List<RuleRef>,
    /** Rules that matched but lost their stage's precedence contest. */
    val skipped: List<Skipped>,
) {
    /** True when no rule at all matched (and no implied block applies). */
    val isEmpty: Boolean get() = matchedRules.isEmpty() && gateway === GatewayOutcome.Pass

    /** True when at least one *mutating* stage will act on this flow (drives the safety gate). */
    val mutates: Boolean
        get() = gateway.effectiveMode != null || mirror != null || !rewrite.isEmpty || breakpoint != null ||
            scripts.isNotEmpty() || mock != null || bodyCrypto != null || throttle != null || chaos != null

    override fun toString(): String =
        "RuleDecision(rev=$revision, gateway=$gateway, matched=${matchedRules.size}, skipped=${skipped.size})"

    companion object {
        fun none(revision: Long): RuleDecision = RuleDecision(
            revision = revision,
            gateway = GatewayOutcome.Pass,
            mirror = null,
            rewrite = ResolvedRewrite.EMPTY,
            breakpoint = null,
            scripts = emptyList(),
            mock = null,
            bodyCrypto = null,
            throttle = null,
            chaos = null,
            observe = emptyList(),
            matchedRules = emptyList(),
            skipped = emptyList(),
        )

        val NONE: RuleDecision = none(0L)
    }
}
