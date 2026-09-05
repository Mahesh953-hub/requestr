package com.example.interception.rules.stages

import com.example.data.local.InterceptorRuleEntity
import com.example.interception.ConnectControl.Disposition
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.MirrorConfig
import com.example.interception.rules.NamePolicy
import com.example.interception.rules.RedirectSpec
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RulePipeline
import com.example.interception.rules.RuleSnapshotCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4 task 27: the GATEWAY slot mapped onto CONNECT-level dispositions, with precedence. */
class AccessControlStageTest {

    private var nextId = 1L

    private fun rule(name: String, config: RuleConfig, host: String = "api.example", sortOrder: Int = 0) =
        InterceptorRuleEntity.fromDomain(
            InterceptorRule(
                id = nextId++, name = name, stage = config.stage, sortOrder = sortOrder,
                matcher = RuleMatcherSpec(hostGlobs = listOf(host)), config = config,
            ),
        )

    private fun decide(vararg rules: InterceptorRuleEntity, host: String = "api.example") =
        RulePipeline.evaluateConnect(RuleSnapshotCompiler.compile(rules.toList(), emptyList(), 1L), host, 443)

    private fun dispositionOf(vararg rules: InterceptorRuleEntity, host: String = "api.example", bypass: Boolean = false, trail: FlowAuditTrail? = null) =
        AccessControlStage.disposition(decide(*rules, host = host), host, 443, trail, pinningBypass = { bypass })

    @Test
    fun `no rule is an opaque tunnel`() {
        assertSame(Disposition.AllowOpaque, dispositionOf())
    }

    @Test
    fun `BLOCK blocks with a reason naming the host`() {
        val d = dispositionOf(rule("b", GatewayConfig(GatewayMode.BLOCK))) as Disposition.Block
        assertEquals("Blocked by rule for api.example", d.reason)
    }

    @Test
    fun `FORCE_DECRYPT is ForceMitm, and degrades to opaque when the pinning bypass has tripped`() {
        val fd = rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT))
        assertSame(Disposition.ForceMitm, dispositionOf(fd))
        val trail = FlowAuditTrail()
        assertSame(Disposition.AllowOpaque, dispositionOf(fd, bypass = true, trail = trail))
        assertEquals(FlowAuditTrail.Outcome.REFUSED_SAFETY, trail.toRecords().single().outcome)
    }

    @Test
    fun `SUSPEND parks the flow with the rule's timeout and name`() {
        val d = dispositionOf(rule("hold", GatewayConfig(GatewayMode.SUSPEND, suspendTimeoutMs = 1_234))) as Disposition.Suspend
        assertEquals(1_234L, d.timeoutMs)
        assertEquals("hold", d.ruleName)
    }

    @Test
    fun `BYPASS wins over FORCE_DECRYPT and BLOCK and never decrypts`() {
        val d = dispositionOf(
            rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT), sortOrder = 0),
            rule("block", GatewayConfig(GatewayMode.BLOCK), sortOrder = 1),
            rule("bypass", GatewayConfig(GatewayMode.BYPASS), sortOrder = 2),
        )
        assertSame(Disposition.AllowOpaque, d)
    }

    @Test
    fun `BLOCK wins over SUSPEND wins over FORCE_DECRYPT`() {
        assertTrue(dispositionOf(rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT)), rule("s", GatewayConfig(GatewayMode.SUSPEND))) is Disposition.Suspend)
        assertTrue(
            dispositionOf(
                rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT)),
                rule("s", GatewayConfig(GatewayMode.SUSPEND)),
                rule("b", GatewayConfig(GatewayMode.BLOCK)),
            ) is Disposition.Block,
        )
    }

    @Test
    fun `ALLOW_ONLY match falls through to the other slots, non-match is an implied block`() {
        val allow = rule("allow", GatewayConfig(GatewayMode.ALLOW_ONLY))
        assertSame(Disposition.AllowOpaque, dispositionOf(allow))
        val fd = rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT))
        // Plan precedence is strict: an ALLOW_ONLY match is the winning gateway rule, so a lower-ranked
        // FORCE_DECRYPT for the same host is a precedence loser (the flow stays opaque).
        assertSame("ALLOW_ONLY outranks FORCE_DECRYPT", Disposition.AllowOpaque, dispositionOf(allow, fd))
        assertSame(Disposition.ForceMitm, dispositionOf(fd))
        val blocked = dispositionOf(allow, host = "other.example") as Disposition.Block
        assertEquals("Blocked: other.example is not in the allow-list", blocked.reason)
    }

    @Test
    fun `MIRROR re-points the tunnel with the name-policy triple`() {
        val d = dispositionOf(
            rule("m", MirrorConfig(targetHost = "staging.example", targetPort = 8443, hostPolicy = NamePolicy.USE_MIRROR_ADDRESS, cnPolicy = NamePolicy.USE_MIRROR_ADDRESS)),
        ) as Disposition.Mirror
        assertEquals("staging.example:8443", d.target)
        assertEquals(NamePolicy.USE_MIRROR_ADDRESS, d.hostPolicy)
        assertEquals(NamePolicy.USE_MIRROR_ADDRESS, d.sniPolicy)
        assertEquals(NamePolicy.USE_MIRROR_ADDRESS, d.cnPolicy)
        assertEquals("m", d.ruleName)
    }

    @Test
    fun `MIRROR without an explicit port keeps the CONNECT port`() {
        val d = dispositionOf(rule("m", MirrorConfig(targetHost = "staging.example"))) as Disposition.Mirror
        assertEquals(443, d.targetPort)
    }

    @Test
    fun `a rewrite redirection re-points the tunnel, legacy host-port and URL forms alike`() {
        val raw = dispositionOf(rule("r", RewriteConfig(redirection = RedirectSpec("new.example:8443")))) as Disposition.Redirect
        assertEquals("new.example:8443", raw.target)
        val url = dispositionOf(rule("r", RewriteConfig(redirection = RedirectSpec("https://new.example/path")))) as Disposition.Redirect
        assertEquals("new.example:443", url.target)
        val http = dispositionOf(rule("r", RewriteConfig(redirection = RedirectSpec("http://new.example/")))) as Disposition.Redirect
        assertEquals("new.example:80", http.target)
        assertSame("blank target is a no-op", Disposition.AllowOpaque, dispositionOf(rule("r", RewriteConfig(redirection = RedirectSpec("  ")))))
    }

    @Test
    fun `gateway BLOCK outranks mirror and redirect and mirror outranks redirect`() {
        val mirror = rule("m", MirrorConfig(targetHost = "staging.example"))
        val redirect = rule("r", RewriteConfig(redirection = RedirectSpec("https://new.example/")))
        assertTrue(dispositionOf(rule("b", GatewayConfig(GatewayMode.BLOCK)), mirror, redirect) is Disposition.Block)
        assertTrue(dispositionOf(mirror, redirect) is Disposition.Mirror)
    }

    @Test
    fun `the trail records what the stage applied`() {
        val trail = FlowAuditTrail()
        dispositionOf(rule("b", GatewayConfig(GatewayMode.BLOCK)), rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT)), trail = trail)
        val outcomes = trail.toRecords().map { it.rule.name to it.outcome }
        assertEquals(listOf("b" to FlowAuditTrail.Outcome.APPLIED), outcomes)
    }
}
