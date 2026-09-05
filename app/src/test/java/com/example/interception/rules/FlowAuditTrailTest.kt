package com.example.interception.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4 task 26: [FlowAuditTrail] is the per-flow provenance list persisted as `applied_rules`. */
class FlowAuditTrailTest {

    private val gateway = RuleRef(id = 11, name = "block-it", stage = RuleStage.GATEWAY)
    private val rewrite = RuleRef(id = 12, name = "add-header", stage = RuleStage.REWRITE)
    private val observe = RuleRef(id = 13, name = "watch", stage = RuleStage.OBSERVE)

    @Test
    fun `starts empty and grows in call order with the injected clock`() {
        var now = 1_000L
        val trail = FlowAuditTrail { now++ }
        assertTrue(trail.isEmpty)
        trail.applied(rewrite, FlowAuditTrail.Phase.REQUEST, "set x-a")
        trail.failed(gateway, FlowAuditTrail.Phase.CONNECT, "boom")
        trail.refused(rewrite, FlowAuditTrail.Phase.RESPONSE, "safety")
        assertEquals(3, trail.size)
        val records = trail.toRecords()
        assertEquals(listOf(1_000L, 1_001L, 1_002L), records.map { it.atMs })
        assertEquals(
            listOf(FlowAuditTrail.Outcome.APPLIED, FlowAuditTrail.Outcome.FAILED, FlowAuditTrail.Outcome.REFUSED_SAFETY),
            records.map { it.outcome },
        )
    }

    @Test
    fun `toEntities maps every field onto AppliedRuleEntity and stamps the traffic id`() {
        val trail = FlowAuditTrail { 42L }
        trail.applied(rewrite, FlowAuditTrail.Phase.RESPONSE, "set-status 418")
        val entity = trail.toEntities(trafficId = 99L).single()
        assertEquals(99L, entity.trafficId)
        assertEquals("12", entity.ruleId)
        assertEquals("add-header", entity.ruleName)
        assertEquals("REWRITE", entity.stage)
        assertEquals("RESPONSE", entity.phase)
        assertEquals("APPLIED", entity.outcome)
        assertEquals("set-status 418", entity.detail)
        assertEquals(42L, entity.appliedAt)
        assertEquals(0L, entity.id)
    }

    @Test
    fun `default traffic id is zero so the queue can patch it at write time`() {
        val trail = FlowAuditTrail()
        trail.applied(gateway, FlowAuditTrail.Phase.CONNECT)
        val entity = trail.toEntities().single()
        assertEquals(0L, entity.trafficId)
        assertNull(entity.detail)
    }

    @Test
    fun `recordDecision writes precedence losers and observe matches, nothing else`() {
        val decision = RuleDecision(
            revision = 3,
            gateway = GatewayOutcome.Apply(GatewayMode.BLOCK, gateway, GatewayConfig(GatewayMode.BLOCK)),
            mirror = null,
            rewrite = ResolvedRewrite.EMPTY,
            breakpoint = null,
            scripts = emptyList(),
            mock = null,
            bodyCrypto = null,
            throttle = null,
            chaos = null,
            observe = listOf(observe),
            matchedRules = listOf(gateway, rewrite, observe),
            skipped = listOf(Skipped(rewrite, "precedence: lost")),
        )
        val trail = FlowAuditTrail()
        trail.recordDecision(decision, FlowAuditTrail.Phase.REQUEST)
        val records = trail.toRecords()
        assertEquals(2, records.size)
        assertEquals(FlowAuditTrail.Outcome.SKIPPED_PRECEDENCE, records[0].outcome)
        assertEquals(rewrite, records[0].rule)
        assertEquals("precedence: lost", records[0].detail)
        assertEquals(FlowAuditTrail.Outcome.MATCHED, records[1].outcome)
        assertEquals(observe, records[1].rule)
        assertEquals("observe", records[1].detail)
        // The winning gateway rule is NOT recorded here -- the stage that applies it records APPLIED.
        assertFalse(records.any { it.rule == gateway })
    }

    @Test
    fun `toRecords is a snapshot copy`() {
        val trail = FlowAuditTrail()
        trail.applied(gateway, FlowAuditTrail.Phase.CONNECT)
        val first = trail.toRecords()
        trail.applied(rewrite, FlowAuditTrail.Phase.REQUEST)
        assertEquals(1, first.size)
        assertEquals(2, trail.toRecords().size)
    }

    @Test
    fun `rules badge lists distinct applied and failed rules as stage colon id`() {
        val trail = FlowAuditTrail()
        trail.record(observe, FlowAuditTrail.Phase.REQUEST, FlowAuditTrail.Outcome.MATCHED, "observe")
        trail.applied(rewrite, FlowAuditTrail.Phase.REQUEST, "a")
        trail.applied(rewrite, FlowAuditTrail.Phase.RESPONSE, "b")
        trail.failed(gateway, FlowAuditTrail.Phase.REQUEST, "x")
        assertEquals("rewrite:12,gateway:11", RuleMetadataHeaders.rulesBadge(trail))
        val headers = RuleMetadataHeaders.withRulesBadge(mapOf("content-type" to "text/plain"), trail)
        assertEquals("rewrite:12,gateway:11", headers[RuleMetadataHeaders.RULES])
        assertEquals("text/plain", headers["content-type"])
    }

    @Test
    fun `rules badge is absent for an empty or matched-only trail`() {
        assertNull(RuleMetadataHeaders.rulesBadge(null))
        assertNull(RuleMetadataHeaders.rulesBadge(FlowAuditTrail()))
        val matchedOnly = FlowAuditTrail().apply { record(observe, FlowAuditTrail.Phase.REQUEST, FlowAuditTrail.Outcome.MATCHED) }
        assertNull(RuleMetadataHeaders.rulesBadge(matchedOnly))
        val headers = mapOf("a" to "b")
        assertEquals(headers, RuleMetadataHeaders.withRulesBadge(headers, matchedOnly))
    }
}
