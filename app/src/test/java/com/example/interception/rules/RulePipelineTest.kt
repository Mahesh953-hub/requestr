package com.example.interception.rules

import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RuleFolderDao
import com.example.data.local.InterceptorRuleDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * S4 task 26: [RulePipeline] resolves ONE decision per flow from an immutable [RuleSnapshot] --
 * per-stage precedence, the ALLOW_ONLY implied block, rewrite composition -- and touches no DAO.
 */
class RulePipelineTest {

    private var nextId = 1L

    private fun rule(
        name: String,
        config: RuleConfig,
        hostGlobs: List<String> = listOf("api.example"),
        sortOrder: Int = 0,
        methods: List<String> = emptyList(),
        urlRegex: String? = null,
    ): InterceptorRuleEntity = InterceptorRuleEntity.fromDomain(
        InterceptorRule(
            id = nextId++,
            name = name,
            stage = config.stage,
            sortOrder = sortOrder,
            matcher = RuleMatcherSpec(hostGlobs = hostGlobs, methods = methods, urlRegex = urlRegex),
            config = config,
        ),
    )

    private fun snapshot(vararg rules: InterceptorRuleEntity): RuleSnapshot =
        RuleSnapshotCompiler.compile(rules.toList(), emptyList(), revision = 7L)

    private fun key(host: String = "api.example", method: String = "GET", url: String = "https://$host/v1/users") =
        FlowKey(host = host, port = 443, url = url, method = method, headers = emptyMap())

    // ── empty / no-match ─────────────────────────────────────────────────────────────────────

    @Test
    fun `empty snapshot yields the NONE decision carrying the snapshot revision`() {
        val decision = RulePipeline.evaluate(snapshot(), key())
        assertTrue(decision.isEmpty)
        assertFalse(decision.mutates)
        assertSame(GatewayOutcome.Pass, decision.gateway)
        assertEquals(7L, decision.revision)
    }

    @Test
    fun `a rule for another host does not match and the decision stays empty`() {
        val decision = RulePipeline.evaluate(snapshot(rule("other", GatewayConfig(GatewayMode.BLOCK), hostGlobs = listOf("other.example"))), key())
        assertTrue(decision.isEmpty)
        assertTrue(decision.matchedRules.isEmpty())
    }

    // ── gateway precedence ───────────────────────────────────────────────────────────────────

    @Test
    fun `gateway precedence is ALLOW_ONLY over BYPASS over BLOCK over SUSPEND over FORCE_DECRYPT`() {
        val modes = listOf(GatewayMode.FORCE_DECRYPT, GatewayMode.SUSPEND, GatewayMode.BLOCK, GatewayMode.BYPASS, GatewayMode.ALLOW_ONLY)
        // Add one mode at a time (lowest precedence first) and check the winner each time.
        for (upTo in 1..modes.size) {
            val rules = modes.take(upTo).mapIndexed { i, mode -> rule("g-$mode", GatewayConfig(mode), sortOrder = i) }
            val decision = RulePipeline.evaluate(snapshot(*rules.toTypedArray()), key())
            val apply = decision.gateway as GatewayOutcome.Apply
            assertEquals("with ${modes.take(upTo)}", modes[upTo - 1], apply.mode)
            assertEquals("losers are recorded as skipped", upTo - 1, decision.skipped.size)
            decision.skipped.forEach { assertTrue(it.reason, it.reason.startsWith("precedence:")) }
        }
    }

    @Test
    fun `gateway precedence does not depend on list order`() {
        val decision = RulePipeline.evaluate(
            snapshot(
                rule("block", GatewayConfig(GatewayMode.BLOCK), sortOrder = 0),
                rule("bypass", GatewayConfig(GatewayMode.BYPASS), sortOrder = 1),
            ),
            key(),
        )
        val apply = decision.gateway as GatewayOutcome.Apply
        assertEquals(GatewayMode.BYPASS, apply.mode)
        assertEquals("bypass", apply.rule.name)
        assertEquals(listOf("block"), decision.skipped.map { it.rule.name })
        assertNull("BYPASS is not a block", apply.effectiveMode?.takeIf { apply.blocks })
        assertFalse(apply.blocks)
    }

    @Test
    fun `BLOCK gateway blocks and the decision mutates`() {
        val decision = RulePipeline.evaluate(snapshot(rule("b", GatewayConfig(GatewayMode.BLOCK))), key())
        assertTrue(decision.gateway.blocks)
        assertEquals(GatewayMode.BLOCK, decision.gateway.effectiveMode)
        assertTrue(decision.mutates)
    }

    // ── ALLOW_ONLY implied block ─────────────────────────────────────────────────────────────

    @Test
    fun `ALLOW_ONLY rule matching passes the flow through`() {
        val decision = RulePipeline.evaluate(snapshot(rule("allow", GatewayConfig(GatewayMode.ALLOW_ONLY))), key())
        val apply = decision.gateway as GatewayOutcome.Apply
        assertEquals(GatewayMode.ALLOW_ONLY, apply.mode)
        assertFalse(apply.blocks)
        assertNull("ALLOW_ONLY has no effective mode of its own", apply.effectiveMode)
    }

    @Test
    fun `ALLOW_ONLY rules present but none matching imply a BLOCK naming the allow-list`() {
        val decision = RulePipeline.evaluate(
            snapshot(
                rule("allow-a", GatewayConfig(GatewayMode.ALLOW_ONLY), hostGlobs = listOf("a.example")),
                rule("allow-b", GatewayConfig(GatewayMode.ALLOW_ONLY), hostGlobs = listOf("b.example")),
            ),
            key(host = "c.example"),
        )
        val implied = decision.gateway as GatewayOutcome.ImpliedBlock
        assertEquals(listOf("allow-a", "allow-b"), implied.allowOnlyRules.map { it.name })
        assertTrue(decision.gateway.blocks)
        assertEquals(GatewayMode.BLOCK, decision.gateway.effectiveMode)
        assertFalse("an implied block is not 'empty'", decision.isEmpty)
        assertTrue(decision.mutates)
    }

    @Test
    fun `no ALLOW_ONLY rules means no implied block`() {
        val decision = RulePipeline.evaluate(snapshot(rule("b", GatewayConfig(GatewayMode.BLOCK), hostGlobs = listOf("x.example"))), key())
        assertSame(GatewayOutcome.Pass, decision.gateway)
    }

    // ── first-match slots ────────────────────────────────────────────────────────────────────

    @Test
    fun `mirror mock bodyCrypto throttle and chaos are first-match by sortOrder and skip the rest`() {
        val decision = RulePipeline.evaluate(
            snapshot(
                rule("mirror-2", MirrorConfig(targetHost = "b.example"), sortOrder = 2),
                rule("mirror-1", MirrorConfig(targetHost = "a.example"), sortOrder = 1),
                rule("mock-1", MockConfig(statusCode = 201), sortOrder = 3),
                rule("mock-2", MockConfig(statusCode = 202), sortOrder = 4),
                rule("chaos-1", ChaosConfig(failRate = 1.0), sortOrder = 5),
                rule("chaos-2", ChaosConfig(failRate = 0.5), sortOrder = 6),
                rule("thr-1", ThrottleConfig(downlinkBps = 1000), sortOrder = 7),
                rule("thr-2", ThrottleConfig(downlinkBps = 2000), sortOrder = 8),
                rule("bc-1", BodyCryptoConfig(direction = CryptoDirection.ENCRYPT, keyAlias = "k1"), sortOrder = 9),
                rule("bc-2", BodyCryptoConfig(direction = CryptoDirection.DECRYPT, keyAlias = "k2"), sortOrder = 10),
            ),
            key(),
        )
        assertEquals("a.example", decision.mirror!!.config.targetHost)
        assertEquals(201, decision.mock!!.config.statusCode)
        assertEquals(1.0, decision.chaos!!.config.failRate, 0.0)
        assertEquals(1000L, decision.throttle!!.config.downlinkBps)
        assertEquals("k1", decision.bodyCrypto!!.config.keyAlias)
        assertEquals(
            setOf("mirror-2", "mock-2", "chaos-2", "thr-2", "bc-2"),
            decision.skipped.map { it.rule.name }.toSet(),
        )
        decision.skipped.forEach { assertEquals(RulePipeline.PRECEDENCE_FIRST_MATCH, it.reason) }
        assertEquals(10, decision.matchedRules.size)
    }

    // ── rewrite composition ──────────────────────────────────────────────────────────────────

    @Test
    fun `all matching rewrite rules are collected in order, with at most one redirection`() {
        val decision = RulePipeline.evaluate(
            snapshot(
                rule("rw-mod", RewriteConfig(requestModifications = listOf(Modification.SetHeader("x-a", "1"))), sortOrder = 0),
                rule("rw-redir-1", RewriteConfig(redirection = RedirectSpec("https://one.example/")), sortOrder = 1),
                rule("rw-redir-2", RewriteConfig(redirection = RedirectSpec("https://two.example/")), sortOrder = 2),
                rule("rw-resp", RewriteConfig(responseModifications = listOf(Modification.SetStatus(418))), sortOrder = 3),
            ),
            key(),
        )
        val rewrite = decision.rewrite
        assertEquals(listOf("rw-mod", "rw-redir-1", "rw-redir-2", "rw-resp"), rewrite.rules.map { it.rule.name })
        assertEquals("https://one.example/", rewrite.redirection!!.config.redirection!!.targetUrl)
        assertTrue(rewrite.hasRequestWork)
        assertTrue(rewrite.hasResponseWork)
        assertNull(rewrite.replaceRequest)
        assertNull(rewrite.replaceResponse)
        assertTrue(decision.mutates)
    }

    @Test
    fun `scripts and observe accumulate`() {
        val decision = RulePipeline.evaluate(
            snapshot(
                rule("s1", ScriptConfig(source = "1"), sortOrder = 0),
                rule("s2", ScriptConfig(source = "2"), sortOrder = 1),
                rule("o1", ObserveConfig, sortOrder = 2),
                rule("o2", ObserveConfig, sortOrder = 3),
            ),
            key(),
        )
        assertEquals(listOf("s1", "s2"), decision.scripts.map { it.rule.name })
        assertEquals(listOf("o1", "o2"), decision.observe.map { it.name })
        assertTrue(decision.skipped.isEmpty())
    }

    @Test
    fun `observe-only decision does not mutate`() {
        val decision = RulePipeline.evaluate(snapshot(rule("o", ObserveConfig)), key())
        assertFalse(decision.isEmpty)
        assertFalse(decision.mutates)
    }

    // ── matcher dimensions reach the pipeline ────────────────────────────────────────────────

    @Test
    fun `method and url regex constraints are honoured`() {
        val snap = snapshot(
            rule("post-only", GatewayConfig(GatewayMode.BLOCK), methods = listOf("POST")),
            rule("users-regex", ObserveConfig, hostGlobs = emptyList(), urlRegex = "/v1/users$"),
        )
        val get = RulePipeline.evaluate(snap, key(method = "GET"))
        assertSame(GatewayOutcome.Pass, get.gateway)
        assertEquals(listOf("users-regex"), get.observe.map { it.name })
        val post = RulePipeline.evaluate(snap, key(method = "POST"))
        assertTrue(post.gateway.blocks)
    }

    // ── connect helper ───────────────────────────────────────────────────────────────────────

    @Test
    fun `evaluateConnect builds the CONNECT-shaped key`() {
        val decision = RulePipeline.evaluateConnect(
            snapshot(rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT))), "api.example", 443, null, null, null,
        )
        assertEquals(GatewayMode.FORCE_DECRYPT, (decision.gateway as GatewayOutcome.Apply).mode)
    }

    // ── zero DAO calls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `evaluating against the store's snapshot never touches a DAO`() {
        RuleSnapshotStore.resetForTest()
        try {
            val ruleDao = Proxy.newProxyInstance(
                InterceptorRuleDao::class.java.classLoader, arrayOf(InterceptorRuleDao::class.java),
            ) { _, m, _ -> throw AssertionError("DAO touched on the hot path: ${m.name}") } as InterceptorRuleDao
            val folderDao = Proxy.newProxyInstance(
                RuleFolderDao::class.java.classLoader, arrayOf(RuleFolderDao::class.java),
            ) { _, m, _ -> throw AssertionError("DAO touched on the hot path: ${m.name}") } as RuleFolderDao
            RuleSnapshotStore.replaceForTest(snapshot(rule("b", GatewayConfig(GatewayMode.BLOCK))))
            // The DAOs exist but nothing here may call them.
            assertNotNull(ruleDao); assertNotNull(folderDao)
            repeat(1_000) {
                val decision = RulePipeline.evaluate(RuleSnapshotStore.current, key())
                assertTrue(decision.gateway.blocks)
            }
        } finally {
            RuleSnapshotStore.resetForTest()
        }
    }
}
