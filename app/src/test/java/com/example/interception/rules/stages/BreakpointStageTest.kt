package com.example.interception.rules.stages

import com.example.data.models.HttpMethod
import com.example.interception.breakpoint.BreakpointAction
import com.example.interception.breakpoint.BreakpointPersistence
import com.example.interception.breakpoint.BreakpointRegistry
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BreakpointConfig
import com.example.interception.rules.BreakpointPhase
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleRef
import com.example.interception.rules.RuleStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4 task 31: stage-level contract (phase gating, replacement copy, abort reply, audit, snapshot). */
class BreakpointStageTest {

    private val rule = RuleRef(3L, "pause-it", RuleStage.BREAKPOINT)

    private fun request(body: String = "") = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.POST, url = "https://app.example/v1", path = "/v1", host = "app.example", port = 443,
        headers = mapOf("host" to "app.example", "content-length" to body.length.toString()), body = body, rawBodyBytes = body.toByteArray(),
    )

    private fun response(body: String = "orig") = ParsedHttpMessage(
        isRequest = false, method = null, statusCode = 200, statusMessage = "OK",
        headers = mapOf("content-type" to "text/plain", "content-length" to body.length.toString()), body = body, rawBodyBytes = body.toByteArray(),
    )

    @Test
    fun `phase gating -- BOTH never pauses CONNECT`() {
        assertTrue(BreakpointStage.applies(BreakpointConfig(BreakpointPhase.BOTH), FlowAuditTrail.Phase.REQUEST))
        assertTrue(BreakpointStage.applies(BreakpointConfig(BreakpointPhase.BOTH), FlowAuditTrail.Phase.RESPONSE))
        assertFalse(BreakpointStage.applies(BreakpointConfig(BreakpointPhase.BOTH), FlowAuditTrail.Phase.CONNECT))
        assertTrue(BreakpointStage.applies(BreakpointConfig(BreakpointPhase.REQUEST), FlowAuditTrail.Phase.REQUEST))
        assertFalse(BreakpointStage.applies(BreakpointConfig(BreakpointPhase.REQUEST), FlowAuditTrail.Phase.RESPONSE))
        assertFalse(BreakpointStage.applies(BreakpointConfig(BreakpointPhase.RESPONSE), FlowAuditTrail.Phase.REQUEST))
        assertTrue(BreakpointStage.applies(BreakpointConfig(BreakpointPhase.RESPONSE), FlowAuditTrail.Phase.RESPONSE))
    }

    @Test
    fun `queue full -- forwarded unchanged with a SKIPPED_PRECEDENCE audit entry`() {
        val registry = BreakpointRegistry(maxPaused = 1, slotWaitMs = 20)
        val occupant = registry.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request(), "app.example", 60_000, "occupant")
        assertTrue(occupant is BreakpointRegistry.PauseResult.Paused)
        val trail = FlowAuditTrail()
        val message = request("body")
        val outcome = BreakpointStage.pause(registry, rule, BreakpointConfig(), FlowAuditTrail.Phase.REQUEST, message, "app.example", trail)
        assertEquals(BreakpointStage.Outcome.Skipped("breakpoint queue full (1 paused)"), outcome)
        val rec = trail.toRecords().single()
        assertEquals(FlowAuditTrail.Outcome.SKIPPED_PRECEDENCE, rec.outcome)
        assertEquals("breakpoint queue full (1 paused)", rec.detail)
        assertNull(message.headers[RuleMetadataHeaders.BREAKPOINT])
        assertEquals("body", message.body)
        registry.resumeAll()
    }

    @Test
    fun `Replace copies the operator's edits and marks the body replaced so Content-Length is recomputed`() {
        val registry = BreakpointRegistry(maxPaused = 2, slotWaitMs = 20)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val trail = FlowAuditTrail()
            val message = request("original")
            val f = pool.submit<BreakpointStage.Outcome> {
                BreakpointStage.pause(registry, rule, BreakpointConfig(timeoutMs = 30_000), FlowAuditTrail.Phase.REQUEST, message, "app.example", trail, flowId = "r")
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (registry["r"] == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val edited = ParsedHttpMessage(
                isRequest = true, method = HttpMethod.POST, url = "https://app.example/v2", host = "app.example",
                headers = mapOf("host" to "app.example", "x-edited" to "yes", "content-length" to "1"), body = "edited-body",
            )
            assertTrue(registry.replace("r", edited))
            val outcome = f.get(5, TimeUnit.SECONDS) as BreakpointStage.Outcome.Forward
            assertTrue(outcome.changed)
            assertEquals("replaced", outcome.actionName)
            assertTrue(outcome.action is BreakpointAction.Replace)
            assertEquals("https://app.example/v2", message.url)
            assertEquals("yes", message.headers["x-edited"])
            assertEquals("edited-body", message.body)
            assertTrue(message.bodyReplaced)
            assertFalse(message.isBodyBinary)
            assertNull(message.replacedBodyBytes)
            val rec = trail.toRecords().single()
            assertEquals(FlowAuditTrail.Outcome.APPLIED, rec.outcome)
            assertTrue(rec.detail!!, rec.detail!!.matches(Regex("paused \\d+ms; replaced")))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `Replace with a binary body carries the exact bytes`() {
        val target = response("text")
        val bytes = byteArrayOf(0, 1, 2, 0x7f, -1, -2)
        val edited = ParsedHttpMessage(
            isRequest = false, method = null, statusCode = 201, headers = mapOf("content-type" to "application/octet-stream"),
            body = "", isBodyBinary = true, replacedBodyBytes = bytes, bodyReplaced = true,
        )
        BreakpointStage.applyReplacement(target, edited)
        assertEquals(201, target.statusCode)
        assertTrue(target.isBodyBinary)
        assertTrue(target.bodyReplaced)
        assertArrayEquals(bytes, target.replacedBodyBytes)
        assertEquals("application/octet-stream", target.headers["content-type"])
    }

    @Test
    fun `Resume and AutoResume leave the message untouched and only differ in the action name`() {
        val registry = BreakpointRegistry(maxPaused = 2, slotWaitMs = 20)
        val trail = FlowAuditTrail()
        val message = response()
        val auto = BreakpointStage.pause(registry, rule, BreakpointConfig(timeoutMs = 50), FlowAuditTrail.Phase.RESPONSE, message, "app.example", trail)
        assertEquals(BreakpointStage.Outcome.Forward(BreakpointAction.AutoResume, changed = false, actionName = "auto-resumed"), auto)
        assertEquals("orig", message.body)
        assertFalse(message.bodyReplaced)
        assertTrue(trail.toRecords().single().detail!!.endsWith("auto-resumed at 50ms"))
        BreakpointStage.stamp(message, rule, "auto-resumed")
        assertEquals("rule=\"pause-it\"; action=auto-resumed", message.headers[RuleMetadataHeaders.BREAKPOINT])
    }

    @Test
    fun `Abort yields the 499 reply carrying the attribution header`() {
        val registry = BreakpointRegistry(maxPaused = 2, slotWaitMs = 20)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val trail = FlowAuditTrail()
            val f = pool.submit<BreakpointStage.Outcome> {
                BreakpointStage.pause(registry, rule, BreakpointConfig(), FlowAuditTrail.Phase.REQUEST, request(), "app.example", trail, flowId = "a")
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (registry["a"] == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertTrue(registry.abort("a", "operator dropped it"))
            val outcome = f.get(5, TimeUnit.SECONDS)
            assertEquals(BreakpointStage.Outcome.Aborted("operator dropped it"), outcome)
            val reply = BreakpointStage.abortResponse(rule, "operator dropped it")
            assertEquals(499, reply.statusCode)
            assertEquals("Aborted At Breakpoint", reply.statusMessage)
            assertEquals("rule=\"pause-it\"; action=aborted", reply.headers[RuleMetadataHeaders.BREAKPOINT])
            assertEquals("close", reply.headers["connection"])
            assertTrue(reply.bodyReplaced)
            assertTrue(reply.body.contains("operator dropped it"))
            assertTrue(trail.toRecords().single().detail!!.endsWith("aborted (operator dropped it)"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `persistence snapshot is head + headers + bounded body, never raw binary`() {
        val registry = BreakpointRegistry(maxPaused = 2, slotWaitMs = 20)
        val big = "x".repeat(BreakpointPersistence.MAX_SNAPSHOT_BODY_CHARS + 5)
        val paused = registry.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request(big), "app.example", 1_000, "s", connectionKey = "c") as BreakpointRegistry.PauseResult.Paused
        val entity = BreakpointPersistence.toEntity(paused.flow)
        assertEquals("s", entity.flowId)
        assertEquals(3L, entity.ruleId)
        assertEquals("pause-it", entity.ruleName)
        assertEquals("REQUEST", entity.phase)
        assertEquals("POST", entity.method)
        assertEquals("https://app.example/v1", entity.url)
        assertEquals(paused.flow.pausedAt + 1_000, entity.timeoutAt)
        assertNull(entity.responseSnapshot)
        val snap = entity.requestSnapshot!!
        assertTrue(snap.startsWith("POST https://app.example/v1 HTTP/1.1\nhost: app.example\n"))
        assertTrue(snap.endsWith("<truncated 5 chars>"))

        val binary = ParsedHttpMessage(isRequest = false, method = null, statusCode = 200, statusMessage = "OK", isBodyBinary = true, rawBodyBytes = ByteArray(17))
        assertTrue(BreakpointPersistence.snapshotOf(binary).endsWith("<binary body, 17 bytes>"))
        registry.resumeAll()
    }
}
