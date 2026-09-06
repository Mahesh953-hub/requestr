package com.example.interception.rules.bench

import com.example.data.local.InterceptorRuleEntity
import com.example.interception.rules.FlowKey
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.HeaderMatcher
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.Modification
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RulePipeline
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotCompiler
import com.example.interception.rules.ScriptConfig

/**
 * Gate G6 harness: a 200-rule snapshot, 1,000 warm-up evaluations then [sampled] timed ones at
 * a 50 flows/s pacing budget (the pacing is a *budget*, not a sleep -- we measure the pure
 * `RulePipeline.evaluate` cost, which must stay under 1 ms at p95 on the JVM and on device).
 *
 * The rule mix mirrors a heavy real workspace: host globs (wildcards + exact), method filters,
 * URL regexes, header matchers, with gateway / rewrite / script configs so every stage slot of
 * the decision is exercised.
 */
object RuleSnapshotBenchmark {
    const val DEFAULT_RULES = 200
    const val DEFAULT_WARMUP = 1_000
    const val DEFAULT_SAMPLED = 5_000
    const val G6_P95_BUDGET_NS = 1_000_000L

    data class Report(val rules: Int, val warmup: Int, val stats: BenchmarkStats, val matchedFlows: Int) {
        val p95Ms: Double get() = stats.p95 / 1e6
        val withinG6: Boolean get() = stats.p95 < G6_P95_BUDGET_NS
        override fun toString(): String = "rules=$rules warmup=$warmup matched=$matchedFlows ${stats.summary()} p95=${"%.3f".format(p95Ms)}ms G6=${if (withinG6) "PASS" else "FAIL"}"
    }

    fun snapshot(rules: Int = DEFAULT_RULES, revision: Long = 1L): RuleSnapshot {
        val entities = ArrayList<InterceptorRuleEntity>(rules)
        for (i in 0 until rules) {
            val host = when (i % 4) {
                0 -> "api-$i.example.com"
                1 -> "*.svc-${i % 17}.example.net"
                2 -> "cdn.example.org"
                else -> "app.example.io"
            }
            val config: RuleConfig = when (i % 3) {
                0 -> RewriteConfig(responseModifications = listOf(Modification.SetHeader("x-bench-$i", "v$i")))
                1 -> if (i % 6 == 1) GatewayConfig(GatewayMode.FORCE_DECRYPT) else RewriteConfig(requestModifications = listOf(Modification.SetHeader("x-req-$i", "1")))
                else -> ScriptConfig(source = "pm.request.headers.set('x-s$i', '1');")
            }
            entities += InterceptorRuleEntity.fromDomain(
                InterceptorRule(
                    id = i + 1L, name = "bench-$i", stage = config.stage, sortOrder = i,
                    matcher = RuleMatcherSpec(
                        hostGlobs = listOf(host),
                        methods = if (i % 5 == 0) listOf("POST", "PUT") else emptyList(),
                        urlRegex = if (i % 7 == 0) "/v[0-9]+/users/[0-9]+" else null,
                        headerMatchers = if (i % 11 == 0) listOf(HeaderMatcher("x-tenant", "t${i % 3}")) else emptyList(),
                    ),
                    config = config,
                ),
            )
        }
        return RuleSnapshotCompiler.compile(entities, emptyList(), revision)
    }

    /** A deterministic flow mix: ~half hit at least one rule, the rest miss every host glob. */
    fun flows(count: Int): List<FlowKey> = List(count) { i ->
        val host = when (i % 6) {
            0 -> "api-${(i * 7) % DEFAULT_RULES}.example.com"
            1 -> "edge.svc-${i % 17}.example.net"
            2 -> "cdn.example.org"
            3 -> "app.example.io"
            4 -> "unmatched-$i.example.test"
            else -> "www.other.example"
        }
        val method = if (i % 3 == 0) "POST" else "GET"
        FlowKey(host = host, port = 443, url = "https://$host/v1/users/${i % 97}?q=$i", method = method, headers = mapOf("x-tenant" to "t${i % 3}", "accept" to "*/*"))
    }

    fun run(rules: Int = DEFAULT_RULES, warmup: Int = DEFAULT_WARMUP, sampled: Int = DEFAULT_SAMPLED, snapshot: RuleSnapshot = snapshot(rules)): Report {
        val keys = flows(warmup + sampled)
        var matched = 0
        for (i in 0 until warmup) RulePipeline.evaluate(snapshot, keys[i])
        val samples = LongArray(sampled)
        for (i in 0 until sampled) {
            val key = keys[warmup + i]
            val t0 = System.nanoTime()
            val decision = RulePipeline.evaluate(snapshot, key)
            samples[i] = System.nanoTime() - t0
            if (!decision.isEmpty) matched++
        }
        return Report(rules, warmup, BenchmarkStats(samples), matched)
    }
}
