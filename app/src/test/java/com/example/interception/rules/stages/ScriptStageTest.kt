package com.example.interception.rules.stages

import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.Matched
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleRef
import com.example.interception.rules.RuleStage
import com.example.interception.rules.ScriptConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 task 32: the SCRIPT slot's list-order semantics, lifecycle hooks, `context.shared`
 * threading from the request leg into the response leg, and the abort path. Uses the
 * in-process runner (the same [ScriptStage.ScriptCall] contract the host runner implements).
 */
class ScriptStageTest {

    @After
    fun restoreRunner() {
        ScriptStage.runner = ScriptStage.ScriptRunner { ScriptStage.runInProcess(it) }
    }

    private var nextId = 1L
    private fun matched(source: String, name: String = "s${nextId}") =
        Matched(RuleRef(nextId++, name, RuleStage.SCRIPT), ScriptConfig(source))

    private fun request() = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://api.example/v1", host = "api.example",
        headers = mapOf("x-a" to "1"), body = "",
    )

    private fun response() = ParsedHttpMessage(
        isRequest = false, method = null, statusCode = 200, statusMessage = "OK",
        headers = mapOf("content-type" to "text/plain"), body = "hello",
    )

    @Test
    fun `scripts run in list order and each sees the previous one's mutations`() {
        val message = request()
        val trail = FlowAuditTrail()
        val result = ScriptStage.apply(
            message,
            listOf(
                matched("pm.request.headers.set('x-order', 'a');"),
                matched("pm.request.headers.set('x-order', pm.request.headers.get('x-order') + 'b');"),
                matched("pm.request.headers.set('x-order', pm.request.headers.get('x-order') + 'c');"),
            ),
            FlowAuditTrail.Phase.REQUEST, trail,
        )
        assertEquals(3, result.ran)
        assertTrue(result.errors.isEmpty())
        assertFalse(result.aborted)
        assertEquals("abc", message.headers["x-order"])
        assertEquals(3, trail.toRecords().count { it.outcome == FlowAuditTrail.Outcome.APPLIED })
    }

    @Test
    fun `context shared written by onRequest is visible to onResponse of the same flow`() {
        val shared = LinkedHashMap<String, String>()
        val req = request()
        ScriptStage.apply(
            req, listOf(matched("function onRequest(context, request) { context.shared.reqUrl = request.url; context.shared.n = '1'; }")),
            FlowAuditTrail.Phase.REQUEST, flowId = "flow-1", shared = shared,
        )
        assertEquals("https://api.example/v1", shared["reqUrl"])

        val resp = response()
        val result = ScriptStage.apply(
            resp, listOf(matched("function onResponse(context, response) { response.headers.set('x-from-request', context.shared.reqUrl + '#' + context.shared.n); }")),
            FlowAuditTrail.Phase.RESPONSE, flowId = "flow-1", shared = shared,
        )
        assertTrue(result.errors.isEmpty())
        assertEquals("https://api.example/v1#1", resp.headers["x-from-request"])
    }

    @Test
    fun `only the hook for the current phase runs`() {
        val shared = LinkedHashMap<String, String>()
        val source = "function onRequest(c, r) { c.shared.ran = (c.shared.ran || '') + 'Q'; } function onResponse(c, r) { c.shared.ran = (c.shared.ran || '') + 'S'; }"
        ScriptStage.apply(request(), listOf(matched(source)), FlowAuditTrail.Phase.REQUEST, shared = shared)
        assertEquals("Q", shared["ran"])
        ScriptStage.apply(response(), listOf(matched(source)), FlowAuditTrail.Phase.RESPONSE, shared = shared)
        assertEquals("QS", shared["ran"])
    }

    @Test
    fun `a null return from a lifecycle hook aborts the flow and stops the list`() {
        val message = request()
        val trail = FlowAuditTrail()
        val result = ScriptStage.apply(
            message,
            listOf(
                matched("pm.request.headers.set('x-first', 'ran');", name = "first"),
                matched("function onRequest(context, request) { return null; }", name = "dropper"),
                matched("pm.request.headers.set('x-third', 'ran');", name = "third"),
            ),
            FlowAuditTrail.Phase.REQUEST, trail,
        )
        assertTrue(result.aborted)
        assertEquals("dropper", result.abortedBy)
        assertEquals("ran", message.headers["x-first"])
        assertNull(message.headers["x-third"])
        assertEquals(2, result.ran)
        val record = trail.toRecords().single { it.rule.name == "dropper" }
        assertEquals(FlowAuditTrail.Outcome.APPLIED, record.outcome)
        assertTrue(record.detail!!.contains("aborted"))

        val reply = ScriptStage.abortResponse("dropper")
        assertEquals(ScriptStage.ABORT_STATUS, reply.statusCode)
        assertTrue(reply.headers[RuleMetadataHeaders.SCRIPT_ERROR]!!.contains("rule=\"dropper\""))
        assertEquals("close", reply.headers["connection"])
        assertTrue(reply.bodyReplaced)
    }

    @Test
    fun `a hook returning undefined or a value does not abort`() {
        val shared = LinkedHashMap<String, String>()
        val r1 = ScriptStage.apply(request(), listOf(matched("function onRequest(c, r) { }")), FlowAuditTrail.Phase.REQUEST, shared = shared)
        val r2 = ScriptStage.apply(request(), listOf(matched("function onRequest(c, r) { return false; }")), FlowAuditTrail.Phase.REQUEST, shared = shared)
        val r3 = ScriptStage.apply(request(), listOf(matched("pm.request.headers.set('x', '1');")), FlowAuditTrail.Phase.REQUEST, shared = shared)
        assertFalse(r1.aborted); assertFalse(r2.aborted); assertFalse(r3.aborted)
    }

    @Test
    fun `a failing script records FAILED, stamps the error header and the list continues`() {
        val message = response()
        val trail = FlowAuditTrail()
        val result = ScriptStage.apply(
            message,
            listOf(matched("throw new Error('nope');", name = "bad"), matched("pm.response.headers.set('x-after', 'yes');", name = "good")),
            FlowAuditTrail.Phase.RESPONSE, trail,
        )
        assertEquals(1, result.errors.size)
        assertEquals("bad", result.errors.single().second)
        assertTrue(message.headers[RuleMetadataHeaders.SCRIPT_ERROR]!!.contains("nope"))
        assertEquals("yes", message.headers["x-after"])
        assertEquals(FlowAuditTrail.Outcome.FAILED, trail.toRecords().single { it.rule.name == "bad" }.outcome)
    }

    @Test
    fun `blank sources are skipped and an empty list is NONE`() {
        assertEquals(ScriptStage.Result.NONE, ScriptStage.apply(request(), emptyList(), FlowAuditTrail.Phase.REQUEST))
        val result = ScriptStage.apply(request(), listOf(matched("   ")), FlowAuditTrail.Phase.REQUEST)
        assertEquals(0, result.ran)
        assertFalse(result.changed)
    }

    @Test
    fun `the runner seam receives phase, flow id and the shared map`() {
        val seen = ArrayList<String>()
        ScriptStage.runner = ScriptStage.ScriptRunner { call ->
            seen += "${call.phaseName}:${call.flowId}:${call.ruleName}"
            call.shared["touched"] = "1"
            ScriptStage.ScriptOutcome(null)
        }
        val shared = LinkedHashMap<String, String>()
        ScriptStage.apply(request(), listOf(matched("x", name = "r1")), FlowAuditTrail.Phase.REQUEST, flowId = "F", shared = shared)
        ScriptStage.apply(response(), listOf(matched("x", name = "r2")), FlowAuditTrail.Phase.RESPONSE, flowId = "F", shared = shared)
        assertEquals(listOf("onRequest:F:r1", "onResponse:F:r2"), seen)
        assertEquals("1", shared["touched"])
    }
}
