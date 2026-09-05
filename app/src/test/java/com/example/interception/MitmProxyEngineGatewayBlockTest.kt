package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.models.HttpMethod
import com.example.data.persistence.RecordingCaptureSink
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotCompiler
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 task 27: the gateway stage at the decrypted-request phase of [MitmProxyEngine]. A `BLOCK`
 * (or an ALLOW_ONLY implied block) answers the client `403 Blocked by requestr`, never dials
 * upstream and never enqueues a pending exchange. Legacy parity keeps the exchange out of the
 * traffic list unless the rule opts in via [GatewayConfig.showBlockedInList].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineGatewayBlockTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private var nextId = 100L

    private fun rule(name: String, config: RuleConfig, hostGlobs: List<String> = listOf("blocked.example")) = InterceptorRule(
        id = nextId++, name = name, stage = config.stage, matcher = RuleMatcherSpec(hostGlobs = hostGlobs), config = config,
    )

    private fun snapshot(vararg rules: InterceptorRule): RuleSnapshot =
        RuleSnapshotCompiler.compile(rules.map { InterceptorRuleEntity.fromDomain(it) }, emptyList(), 1L)

    private fun request(host: String = "blocked.example") =
        ParsedHttpMessage(isRequest = true, method = HttpMethod.GET, url = "https://$host/secret", host = host, port = 443)

    private class DialAttempted : java.io.IOException("upstream dial attempted")

    /** Upstream whose dial is observable: the engine rethrows the connect failure, which [drive] reports as [DIALLED]. */
    private fun observableUpstream(host: String) = LazyUpstreamConnection(host, 443, socketConnector = { _, _ -> throw DialAttempted() })

    private val DIALLED = "<dialled upstream>"

    private fun drive(engine: MitmProxyEngine, req: ParsedHttpMessage, pending: ConcurrentLinkedQueue<MitmProxyEngine.PendingExchange> = ConcurrentLinkedQueue()): String {
        val clientOut = ByteArrayOutputStream()
        return try {
            engine.handleParsedRequest(db, req, "GET /secret HTTP/1.1\r\n\r\n".toByteArray(), observableUpstream(req.host), clientOut, Any(), pending, req.host, 443, true, true)
            clientOut.toString(Charsets.ISO_8859_1.name())
        } catch (e: DialAttempted) {
            assertEquals("nothing is written to the client before the dial", 0, clientOut.size())
            DIALLED
        }
    }

    @Test
    fun `BLOCK answers 403 with the legacy status line and is not persisted by default`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink, ruleSnapshot = { snapshot(rule("deny", GatewayConfig(GatewayMode.BLOCK))) })
        val pending = ConcurrentLinkedQueue<MitmProxyEngine.PendingExchange>()

        val wire = drive(engine, request(), pending)

        assertEquals("HTTP/1.1 403 Blocked by requestr\r\nContent-Length: 0\r\n\r\n", wire)
        assertTrue(wire != DIALLED)
        assertTrue("blocked exchanges never wait for an upstream response", pending.isEmpty())
        assertTrue("legacy parity: hidden from the traffic list unless the rule opts in", sink.rows.isEmpty())
    }

    @Test
    fun `showBlockedInList persists the 403 with provenance`() {
        val sink = RecordingCaptureSink()
        val block = rule("deny-visible", GatewayConfig(GatewayMode.BLOCK, showBlockedInList = true))
        val engine = MitmProxyEngine(sink = sink, ruleSnapshot = { snapshot(block) })

        val wire = drive(engine, request())

        assertTrue(wire, wire.startsWith("HTTP/1.1 403 Blocked by requestr"))
        val row = sink.rows.single()
        assertEquals(403, row.entity.statusCode)
        assertEquals("gateway:${block.id}", row.entity.responseHeaders[RuleMetadataHeaders.RULES])
        val applied = sink.appliedRules.single { it.trafficId == row.id }
        assertEquals(FlowAuditTrail.Outcome.APPLIED.name, applied.outcome)
        assertEquals("blocked", applied.detail)
    }

    @Test
    fun `ALLOW_ONLY implied block applies to hosts outside the allow-list only`() {
        val sink = RecordingCaptureSink()
        val allow = rule("allow-api", GatewayConfig(GatewayMode.ALLOW_ONLY, showBlockedInList = true), hostGlobs = listOf("api.example"))
        val engine = MitmProxyEngine(sink = sink, ruleSnapshot = { snapshot(allow) })

        val blocked = drive(engine, request("other.example"))
        assertTrue(blocked, blocked.startsWith("HTTP/1.1 403 Blocked by requestr"))
        // The implied block has no single rule to show in the list; nothing is persisted for it.
        assertTrue(sink.rows.isEmpty())

        // An allow-listed host proceeds to the upstream dial.
        assertEquals(DIALLED, drive(engine, request("api.example")))
    }

    @Test
    fun `BLOCK outranks FORCE_DECRYPT and BYPASS outranks BLOCK at the request phase`() {
        val sink = RecordingCaptureSink()
        val blockAndDecrypt = snapshot(rule("fd", GatewayConfig(GatewayMode.FORCE_DECRYPT)), rule("deny", GatewayConfig(GatewayMode.BLOCK)))
        assertTrue(drive(MitmProxyEngine(sink = sink, ruleSnapshot = { blockAndDecrypt }), request()).startsWith("HTTP/1.1 403"))

        val bypassed = snapshot(rule("deny", GatewayConfig(GatewayMode.BLOCK)), rule("bypass", GatewayConfig(GatewayMode.BYPASS)))
        assertEquals("BYPASS wins over BLOCK, so the request is forwarded", DIALLED, drive(MitmProxyEngine(sink = sink, ruleSnapshot = { bypassed }), request()))
    }
}
