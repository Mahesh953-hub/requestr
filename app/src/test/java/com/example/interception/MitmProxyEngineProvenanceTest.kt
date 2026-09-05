package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.models.HttpMethod
import com.example.data.persistence.RecordingCaptureSink
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BodySource
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.MockConfig
import com.example.interception.rules.Modification
import com.example.interception.rules.ObserveConfig
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotCompiler
import com.example.interception.rules.RuleStage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 task 26 provenance: every rule the MITM engine applies to an exchange must land as an
 * `applied_rules` row under the persisted transaction (via [com.example.data.persistence.CaptureSink.appliedRules])
 * and as the `x-requestr-rules` badge on the row's response headers, so the traffic list can
 * show "which rules touched this flow" without re-evaluating anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineProvenanceTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun snapshot(vararg rules: InterceptorRule): RuleSnapshot =
        RuleSnapshotCompiler.compile(rules.map { InterceptorRuleEntity.fromDomain(it) }, emptyList(), revision = 3L)

    private fun rule(id: Long, name: String, config: RuleConfig, sortOrder: Int = 0) = InterceptorRule(
        id = id, name = name, stage = config.stage, sortOrder = sortOrder,
        matcher = RuleMatcherSpec(hostGlobs = listOf("prov.example")), config = config,
    )

    private fun request() = ParsedHttpMessage(isRequest = true, method = HttpMethod.GET, url = "https://prov.example/api", host = "prov.example", port = 443)

    private fun response(body: String = "real") = ParsedHttpMessage(
        isRequest = false, method = null, url = "https://prov.example/api", host = "prov.example",
        statusCode = 200, statusMessage = "OK", headers = mapOf("content-type" to "text/plain", "content-length" to body.length.toString()),
        body = body, rawBodyBytes = body.toByteArray(),
    )

    private fun neverConnectUpstream() = LazyUpstreamConnection(
        "prov.example", 443, socketConnector = { _, _ -> throw AssertionError("short-circuited exchanges never dial upstream") },
    )

    @Test
    fun `mock short-circuit persists applied_rules rows and stamps the rules badge`() {
        val sink = RecordingCaptureSink()
        val snap = snapshot(
            rule(11, "observe-all", ObserveConfig),
            rule(12, "mock-it", MockConfig(statusCode = 201, body = BodySource.Text("{\"mocked\":true}"))),
        )
        val engine = MitmProxyEngine(sink = sink, ruleSnapshot = { snap })
        val clientOut = ByteArrayOutputStream()

        engine.handleParsedRequest(
            db, request(), ByteArray(0), neverConnectUpstream(), clientOut, Any(), ConcurrentLinkedQueue(),
            "prov.example", 443, true, true,
        )

        assertTrue(clientOut.toString(Charsets.ISO_8859_1.name()).startsWith("HTTP/1.1 201"))
        assertEquals(1, sink.rows.size)
        val row = sink.rows.single()
        assertEquals(201, row.entity.statusCode)
        assertEquals("mock:12", row.entity.responseHeaders[RuleMetadataHeaders.RULES])

        val applied = sink.appliedRules.filter { it.trafficId == row.id }
        assertEquals(setOf("11", "12"), applied.map { it.ruleId }.toSet())
        val mock = applied.single { it.ruleId == "12" }
        assertEquals("mock-it", mock.ruleName)
        assertEquals(RuleStage.MOCK.name, mock.stage)
        assertEquals(FlowAuditTrail.Phase.REQUEST.name, mock.phase)
        assertEquals(FlowAuditTrail.Outcome.APPLIED.name, mock.outcome)
        assertEquals("mock 201", mock.detail)
        val observe = applied.single { it.ruleId == "11" }
        assertEquals(FlowAuditTrail.Outcome.MATCHED.name, observe.outcome)
        assertEquals(RuleStage.OBSERVE.name, observe.stage)
        // The badge is a persisted-row concern only: the wire response to the client carries no such header.
        assertTrue(!clientOut.toString(Charsets.ISO_8859_1.name()).lowercase().contains(RuleMetadataHeaders.RULES))
    }

    @Test
    fun `response rewrite records provenance for the paired exchange`() {
        val sink = RecordingCaptureSink()
        val snap = snapshot(
            rule(21, "add-header", RewriteConfig(responseModifications = listOf(Modification.SetHeader("x-rewritten", "yes")))),
            rule(22, "replace-body", RewriteConfig(responseModifications = listOf(Modification.ReplaceBody(BodySource.Text("swapped")))), sortOrder = 1),
        )
        val engine = MitmProxyEngine(sink = sink, ruleSnapshot = { snap })
        val pending = ConcurrentLinkedQueue<MitmProxyEngine.PendingExchange>().apply {
            add(MitmProxyEngine.PendingExchange(request(), System.currentTimeMillis()))
        }
        val clientOut = ByteArrayOutputStream()

        engine.handleParsedResponse(
            db, response(), ByteArray(0), clientOut, Any(), pending,
            "prov.example", 443, true, true, MitmProxyEngine.ConnectionProtocolState(),
        )

        val wire = clientOut.toString(Charsets.ISO_8859_1.name())
        assertTrue(wire, wire.contains("x-rewritten: yes") || wire.contains("X-Rewritten: yes"))
        assertTrue(wire, wire.endsWith("swapped"))
        val row = sink.rows.single()
        assertEquals("swapped", row.entity.responseBody)
        assertEquals("rewrite:21,rewrite:22", row.entity.responseHeaders[RuleMetadataHeaders.RULES])
        val applied = sink.appliedRules.filter { it.trafficId == row.id }
        assertEquals(listOf("21", "22"), applied.filter { it.outcome == FlowAuditTrail.Outcome.APPLIED.name }.map { it.ruleId }.sorted())
        assertTrue(applied.all { it.phase == FlowAuditTrail.Phase.RESPONSE.name || it.phase == FlowAuditTrail.Phase.REQUEST.name })
    }

    @Test
    fun `an exchange no rule touched persists no provenance and no badge`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink, ruleSnapshot = { RuleSnapshot.EMPTY })
        val pending = ConcurrentLinkedQueue<MitmProxyEngine.PendingExchange>().apply {
            add(MitmProxyEngine.PendingExchange(request(), System.currentTimeMillis()))
        }

        engine.handleParsedResponse(
            db, response(), "raw-bytes".toByteArray(), ByteArrayOutputStream(), Any(), pending,
            "prov.example", 443, true, true, MitmProxyEngine.ConnectionProtocolState(),
        )

        val row = sink.rows.single()
        assertNull(row.entity.responseHeaders[RuleMetadataHeaders.RULES])
        assertTrue(sink.appliedRules.isEmpty())
    }
}
