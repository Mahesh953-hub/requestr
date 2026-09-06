package com.example.interception

import android.os.Build
import com.example.interception.http.FakeH1Origin
import com.example.interception.http.H2ProxyHarness
import com.example.interception.http.H2ProxyHarness.Companion.waitUntil
import com.example.interception.http.Http2Session
import com.example.interception.http.Http2Transaction
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BodySource
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.InterceptorSafetyGate
import com.example.interception.rules.MockConfig
import com.example.interception.rules.Modification
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotCompiler
import com.example.data.local.InterceptorRuleEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S4 task 35: the staged rule pipeline applied to translated HTTP/2 flows. A real
 * [MitmProxyEngine] evaluates the snapshot through [Http2RuleInterceptor] exactly as the h2
 * branch of `handleConnection` wires it, and the frames a client sees must match what the same
 * rules produce on HTTP/1.1: gateway BLOCK answers 403 without dialing, MOCK synthesizes the body
 * with a correct `content-length`, REWRITE mutates request and response and re-frames, and a
 * safety-gate refusal leaves the flow untouched while the audit trail says why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineH2RulesTest {

    private class Persisted(val request: ParsedHttpMessage, val tx: Http2Transaction, val trail: FlowAuditTrail?)

    private var harness: H2ProxyHarness? = null
    private var origin: FakeH1Origin? = null

    @After fun tearDown() {
        origin?.let { runCatching { it.toServer.close() } }
        harness?.finish()
        origin?.thread?.join(2_000)
    }

    private fun rule(name: String, config: RuleConfig, host: String = "origin.test", methods: List<String> = emptyList()) = InterceptorRule(
        id = name.hashCode().toLong() and 0xffff, name = name, stage = config.stage,
        matcher = RuleMatcherSpec(hostGlobs = listOf(host), methods = methods), config = config,
    )

    private fun snapshot(vararg rules: InterceptorRule): RuleSnapshot =
        RuleSnapshotCompiler.compile(rules.mapIndexed { i, r -> InterceptorRuleEntity.fromDomain(r.copy(sortOrder = i)) }, emptyList(), revision = 7L)

    /** Wires engine + interceptor + observer like `MitmProxyEngine.handleConnection`'s h2 branch. */
    private val observed = CopyOnWriteArrayList<Http2Transaction>()

    private fun start(snapshot: RuleSnapshot, gate: InterceptorSafetyGate = InterceptorSafetyGate(followSettings = false)): Pair<H2ProxyHarness, CopyOnWriteArrayList<Persisted>> {
        val engine = MitmProxyEngine(ruleSnapshot = { snapshot }, safetyGate = gate)
        val rules = Http2RuleInterceptor(engine, "origin.test", 443, mirror = null, clientUid = null, connectionKey = "h2-test")
        val persisted = CopyOnWriteArrayList<Persisted>()
        val session = Http2Session(
            onTransaction = { tx ->
                observed += tx
                val record = rules.take(tx.requestHeaders?.streamId)
                if (record?.suppressed != true) persisted += Persisted(record?.request ?: tx.request, tx, record?.trail)
            },
            onGrpcMessage = {},
        )
        val o = origin!!
        val h = H2ProxyHarness("http/1.1", o.fromServer, o.toServer, session = session, interceptor = rules).also { harness = it }
        return h to persisted
    }

    private fun originEchoing() = FakeH1Origin { req ->
        FakeH1Origin.response("200 OK", "Content-Type: text/plain\r\nX-Origin: h1\r\n", "origin saw ${req.method} ${req.target} body=${req.bodyText}")
    }.also { origin = it }

    @Test
    fun `gateway BLOCK answers 403 on the stream, never dials upstream and stays out of the list`() {
        originEchoing()
        val (h, persisted) = start(snapshot(rule("no ads", GatewayConfig(GatewayMode.BLOCK))))

        h.request(1, "/ad.js")
        h.listener.expect("HEADERS 1 end=true")
        assertEquals("403", h.listener.status(1))
        assertEquals("0", h.listener.header(1, "content-length"))
        assertEquals(0L, h.connects.get())
        assertTrue(origin!!.requests.isEmpty())
        waitUntil { observed.size == 1 }
        assertTrue("a blocked flow with showBlockedInList=false must not be persisted", persisted.isEmpty())
    }

    @Test
    fun `gateway BLOCK with showBlockedInList persists the 403 with its audit trail`() {
        originEchoing()
        val (h, persisted) = start(snapshot(rule("no ads", GatewayConfig(GatewayMode.BLOCK, showBlockedInList = true))))

        h.request(1, "/ad.js")
        h.listener.expect("HEADERS 1 end=true")
        assertEquals("403", h.listener.status(1))
        waitUntil { persisted.size == 1 }
        val row = persisted.single()
        assertEquals(403, row.tx.response.statusCode)
        assertEquals("https://origin.test/ad.js", row.request.url)
        assertTrue(row.trail!!.toRecords().any { it.rule.name == "no ads" && it.outcome == FlowAuditTrail.Outcome.APPLIED })
    }

    @Test
    fun `MOCK synthesizes the response body with a recomputed content-length and skips the origin`() {
        originEchoing()
        val mock = MockConfig(statusCode = 201, headers = mapOf("Content-Type" to "application/json"), body = BodySource.Text("""{"mocked":true}"""))
        val (h, persisted) = start(snapshot(rule("mock api", mock)))

        h.request(1, "/api/items")
        h.listener.expect("DATA_END 1")
        assertEquals("201", h.listener.status(1))
        assertEquals("""{"mocked":true}""", h.listener.body(1))
        assertEquals("15", h.listener.header(1, "content-length"))
        assertEquals("application/json", h.listener.header(1, "content-type"))
        assertEquals(0L, h.connects.get())
        waitUntil { persisted.size == 1 }
        assertEquals(201, persisted.single().tx.response.statusCode)
        assertTrue(persisted.single().trail!!.toRecords().any { it.rule.name == "mock api" && it.outcome == FlowAuditTrail.Outcome.APPLIED })
    }

    @Test
    fun `REWRITE mutates request headers and body toward the origin and the response back to the client`() {
        originEchoing()
        val rewrite = RewriteConfig(
            requestModifications = listOf(Modification.SetHeader("X-Injected", "yes"), Modification.ReplaceBody(BodySource.Text("new body"))),
            responseModifications = listOf(Modification.SetHeader("X-Rewritten", "1"), Modification.RemoveHeader("X-Origin"), Modification.RegexBody("origin saw", "proxy rewrote")),
        )
        val (h, persisted) = start(snapshot(rule("rewrite", rewrite)))

        h.requestWithBody(1, "/submit", "old body!")
        h.listener.expect("DATA_END 1")
        val seen = origin!!.requests.single()
        assertEquals("yes", seen.header("X-Injected"))
        assertEquals("new body", seen.bodyText)
        assertEquals("8", seen.header("Content-Length"))

        assertEquals("200", h.listener.status(1))
        assertEquals("proxy rewrote POST /submit body=new body", h.listener.body(1))
        assertEquals("1", h.listener.header(1, "x-rewritten"))
        assertNull(h.listener.header(1, "x-origin"))
        assertEquals(h.listener.body(1).length.toString(), h.listener.header(1, "content-length"))

        waitUntil { persisted.size == 1 }
        val row = persisted.single()
        // Persisted request is the rules' view; persisted response is what was actually framed.
        assertEquals("yes", row.request.headers.entries.first { it.key.equals("x-injected", ignoreCase = true) }.value)
        assertEquals("new body", row.request.body)
        assertEquals("proxy rewrote POST /submit body=new body", row.tx.response.body)
        assertTrue(row.trail!!.toRecords().any { it.rule.name == "rewrite" && it.phase == FlowAuditTrail.Phase.REQUEST && it.outcome == FlowAuditTrail.Outcome.APPLIED })
        assertTrue(row.trail.toRecords().any { it.rule.name == "rewrite" && it.phase == FlowAuditTrail.Phase.RESPONSE && it.outcome == FlowAuditTrail.Outcome.APPLIED })
    }

    @Test
    fun `a safety-gate refusal leaves the h2 flow untouched and records the refusal`() {
        originEchoing()
        val mock = MockConfig(statusCode = 201, body = BodySource.Text("mocked"))
        val (h, persisted) = start(snapshot(rule("mock api", mock)), gate = InterceptorSafetyGate(InterceptorSafetyGate.Policy.DEFAULT, followSettings = false))

        h.request(1, "/api/items")
        h.listener.expect("DATA_END 1")
        assertEquals("200", h.listener.status(1))
        assertEquals("origin saw GET /api/items body=", h.listener.body(1))
        assertEquals(1, origin!!.requests.size)
        waitUntil { persisted.size == 1 }
        val trail = persisted.single().trail!!
        assertTrue(trail.toString(), trail.toRecords().any { it.rule.name == "mock api" && it.outcome == FlowAuditTrail.Outcome.REFUSED_SAFETY })
    }

    @Test
    fun `rules that do not match keep the frame-by-frame passthrough`() {
        originEchoing()
        val (h, persisted) = start(snapshot(rule("elsewhere", GatewayConfig(GatewayMode.BLOCK), host = "other.test")))

        h.request(1, "/plain")
        h.listener.expect("DATA_END 1")
        assertEquals("200", h.listener.status(1))
        assertEquals("origin saw GET /plain body=", h.listener.body(1))
        assertEquals(0, h.proxy.interceptedStreamCount)
        waitUntil { persisted.size == 1 }
        assertNull(persisted.single().trail)
    }
}
