package com.example.interception.rules

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.models.HttpMethod
import com.example.interception.breakpoint.BreakpointRegistry
import com.example.interception.http.ParsedHttpMessage
import com.example.scripting.host.ScriptHostClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S4 task 33 (D3): the safety gate in front of every mutating stage -- acknowledgement,
 * selection restriction with REFUSED_SAFETY audit records, the kill switch, and the hard caps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class InterceptorSafetyGateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private var nextId = 1L

    private fun rule(config: RuleConfig, name: String = config.stage.name.lowercase(), enabled: Boolean = true, hosts: List<String> = listOf("api.example")) =
        InterceptorRule(id = nextId++, name = name, stage = config.stage, enabled = enabled, matcher = RuleMatcherSpec(hostGlobs = hosts), config = config, createdAt = 1, updatedAt = 1)

    private fun entity(rule: InterceptorRule) = InterceptorRuleEntity.fromDomain(rule)

    private fun snapshot(vararg rules: InterceptorRule) = RuleSnapshotCompiler.compile(rules.map(::entity), emptyList(), revision = 3)

    private fun key(pkg: String? = null, uid: Int? = null) =
        FlowKey(host = "api.example", port = 443, url = "https://api.example/v1", method = "GET", headers = emptyMap(), clientUid = uid, appPackage = pkg)

    private val mixedSnapshot by lazy {
        snapshot(
            rule(GatewayConfig(GatewayMode.FORCE_DECRYPT), name = "gw"),
            rule(RewriteConfig(requestModifications = listOf(Modification.SetHeader("x-a", "1"))), name = "rw"),
            rule(ScriptConfig(source = "1"), name = "js"),
            rule(ThrottleConfig(downlinkBps = 1_000), name = "slow"),
            rule(ChaosConfig(failRate = 1.0), name = "chaos"),
            rule(ObserveConfig, name = "watch"),
        )
    }

    private fun selectedOnly(vararg selected: String, acknowledged: Boolean = true) =
        InterceptorSafetyGate.Policy(acknowledged = acknowledged, restrictToSelection = true, selectedPackages = selected.toSet())

    // ── acknowledgement ──────────────────────────────────────────────────────────────────────

    @Test
    fun `enabling a mutating rule without the acknowledgement fails validation while observe and gateway rules pass`() {
        for (config in listOf(
            RewriteConfig(), BreakpointConfig(), ScriptConfig(source = "1"), MockConfig(),
            BodyCryptoConfig(direction = CryptoDirection.DECRYPT, keyAlias = "k"), ChaosConfig(),
        )) {
            val problems = RuleValidation.validate(rule(config), acknowledged = false)
            assertEquals("stage ${config.stage} must need the acknowledgement", listOf("stage"), problems.map { it.field })
            assertTrue(RuleValidation.validate(rule(config), acknowledged = true).isEmpty())
            // A disabled mutating rule may be saved; it just cannot be switched on.
            assertTrue(RuleValidation.validate(rule(config, enabled = false), acknowledged = false).isEmpty())
        }
        for (config in listOf(GatewayConfig(GatewayMode.BLOCK), MirrorConfig(targetHost = "m.example"), ThrottleConfig(), ObserveConfig)) {
            assertTrue("${config.stage} is not mutating", RuleValidation.validate(rule(config), acknowledged = false).isEmpty())
        }
    }

    @Test
    fun `without the acknowledgement the runtime backstop refuses every mutating rule on every flow`() {
        val gate = InterceptorSafetyGate(InterceptorSafetyGate.Policy.DEFAULT)
        val trail = FlowAuditTrail()
        val screened = gate.screen(RulePipeline.evaluate(mixedSnapshot, key(pkg = "com.any")), FlowAuditTrail.Phase.REQUEST, trail)
        assertTrue(screened.rewrite.isEmpty)
        assertTrue(screened.scripts.isEmpty())
        assertNull(screened.chaos)
        assertEquals(setOf("rw", "js", "chaos"), trail.toRecords().filter { it.outcome == FlowAuditTrail.Outcome.REFUSED_SAFETY }.map { it.rule.name }.toSet())
        assertTrue(trail.toRecords().all { it.detail!!.contains("not acknowledged") })
    }

    // ── selection restriction ────────────────────────────────────────────────────────────────

    @Test
    fun `a flow from a package outside the selection is observed but never mutated and each refusal is in the trail`() {
        val gate = InterceptorSafetyGate(selectedOnly("com.target"))
        val original = RulePipeline.evaluate(mixedSnapshot, key(pkg = "com.other"))
        assertTrue(original.mutates)
        assertEquals(listOf("rw", "js", "chaos"), original.mutatingRules.map { it.name })

        val trail = FlowAuditTrail()
        val screened = gate.screen(original, FlowAuditTrail.Phase.REQUEST, trail, appPackage = "com.other")

        // Non-mutating slots survive untouched ...
        assertEquals(GatewayMode.FORCE_DECRYPT, screened.gateway.effectiveMode)
        assertEquals("slow", screened.throttle!!.rule.name)
        assertEquals(listOf("watch"), screened.observe.map { it.name })
        assertEquals(original.matchedRules, screened.matchedRules)
        assertEquals(original.revision, screened.revision)
        // ... and every mutating one is emptied.
        assertTrue(screened.mutatingRules.isEmpty())
        assertTrue(screened.rewrite.isEmpty)
        assertTrue(screened.scripts.isEmpty())
        assertNull(screened.chaos)
        assertNull(screened.breakpoint)
        assertNull(screened.mock)
        assertNull(screened.bodyCrypto)

        val refused = trail.toRecords().filter { it.outcome == FlowAuditTrail.Outcome.REFUSED_SAFETY }
        assertEquals(listOf("rw", "js", "chaos"), refused.map { it.rule.name })
        assertTrue(refused.all { it.phase == FlowAuditTrail.Phase.REQUEST && it.detail == "package com.other is outside the capture selection" })
    }

    @Test
    fun `a flow from a selected package or an unattributed flow is returned unchanged with no record`() {
        val gate = InterceptorSafetyGate(selectedOnly("com.target"))
        val original = RulePipeline.evaluate(mixedSnapshot, key(pkg = "com.target"))
        val trail = FlowAuditTrail()
        assertSame(original, gate.screen(original, FlowAuditTrail.Phase.REQUEST, trail, appPackage = "com.target"))
        assertSame(original, gate.screen(original, FlowAuditTrail.Phase.REQUEST, trail))
        assertTrue(trail.toRecords().isEmpty())
        assertEquals(InterceptorSafetyGate.Verdict.Allowed, gate.allowMutation())
    }

    @Test
    fun `the uid is resolved to packages when no package is known and shared uids pass if any package is selected`() {
        val gate = InterceptorSafetyGate(selectedOnly("com.target"), packagesForUid = { uid ->
            when (uid) {
                10_001 -> listOf("com.other", "com.target")
                10_002 -> listOf("com.stranger")
                else -> emptyList()
            }
        })
        assertEquals(InterceptorSafetyGate.Verdict.Allowed, gate.allowMutation(clientUid = 10_001))
        assertEquals(InterceptorSafetyGate.Verdict.Refused("package com.stranger is outside the capture selection"), gate.allowMutation(clientUid = 10_002))
        assertEquals(InterceptorSafetyGate.Verdict.Allowed, gate.allowMutation(clientUid = 99))
        // An explicit package wins over the uid.
        assertTrue(gate.allowMutation(appPackage = "com.stranger", clientUid = 10_001) is InterceptorSafetyGate.Verdict.Refused)
    }

    @Test
    fun `a decision with no mutating rule is never touched even when refused`() {
        val gate = InterceptorSafetyGate(InterceptorSafetyGate.Policy.DEFAULT)
        val decision = RulePipeline.evaluate(snapshot(rule(GatewayConfig(GatewayMode.BLOCK)), rule(ThrottleConfig())), key())
        val trail = FlowAuditTrail()
        assertSame(decision, gate.screen(decision, FlowAuditTrail.Phase.REQUEST, trail, appPackage = "com.other"))
        assertTrue(trail.toRecords().isEmpty())
    }

    @Test
    fun `ALL_APPS capture does not restrict mutation and the policy can be swapped live`() {
        val gate = InterceptorSafetyGate(InterceptorSafetyGate.Policy(acknowledged = true, restrictToSelection = false, selectedPackages = setOf("com.target")))
        assertEquals(InterceptorSafetyGate.Verdict.Allowed, gate.allowMutation(appPackage = "com.other"))
        gate.update(selectedOnly("com.target"))
        assertTrue(gate.allowMutation(appPackage = "com.other") is InterceptorSafetyGate.Verdict.Refused)
        assertTrue(gate.hasMutatingRules(mixedSnapshot))
        assertFalse(gate.hasMutatingRules(snapshot(rule(GatewayConfig(GatewayMode.BLOCK)), rule(MirrorConfig(targetHost = "m")))))
    }

    // ── kill switch ──────────────────────────────────────────────────────────────────────────

    private class KillCountingTransport : ScriptHostClient.Transport {
        val kills = AtomicInteger(0)
        override fun call(requestJson: String, deadlineMs: Long): String? = null
        override fun kill(reason: String) { kills.incrementAndGet() }
    }

    private fun message() = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://api.example/x", path = "/x", host = "api.example", port = 443,
        headers = mapOf("host" to "api.example"),
    )

    @Test
    fun `the kill switch disables every mutating rule, resumes every paused flow, kills the host and leaves observe and gateway rules alone`() = runBlocking<Unit> {
        val dao = db.interceptorRuleDao()
        val all = listOf(
            GatewayConfig(GatewayMode.BLOCK), MirrorConfig(targetHost = "m.example"), RewriteConfig(), BreakpointConfig(), ScriptConfig(source = "1"),
            MockConfig(), BodyCryptoConfig(direction = CryptoDirection.DECRYPT, keyAlias = "k"), ThrottleConfig(), ChaosConfig(), ObserveConfig,
        )
        val ids = all.associate { cfg -> cfg.stage to dao.insert(entity(rule(cfg))) }
        assertEquals(all.size, dao.countEnabled())

        val registry = BreakpointRegistry(maxPaused = 8, slotWaitMs = 50)
        val pool = Executors.newCachedThreadPool()
        val started = CountDownLatch(3)
        val ref = RuleRef(ids.getValue(RuleStage.BREAKPOINT), "breakpoint", RuleStage.BREAKPOINT)
        val outcomes = (1..3).map { i ->
            pool.submit<com.example.interception.breakpoint.BreakpointAction?> {
                val r = registry.tryPause(ref, FlowAuditTrail.Phase.REQUEST, message(), "api.example", 60_000, "f$i")
                started.countDown()
                (r as? BreakpointRegistry.PauseResult.Paused)?.let { registry.await(it.flow) }
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(3, registry.pausedCount)
        val transport = KillCountingTransport()
        val client = ScriptHostClient(transport)

        val disabled = InterceptorSafetyGate(InterceptorSafetyGate.Policy.PERMISSIVE)
            .disableAllMutatingRules(dao, breakpoints = registry, scriptHost = client, nowMs = 99)

        assertEquals(RuleStage.MUTATING.size, disabled)
        for ((stage, id) in ids) {
            val row = dao.getById(id)!!
            assertEquals("$stage enabled state", stage !in RuleStage.MUTATING, row.enabled)
            if (stage in RuleStage.MUTATING) assertEquals(99L, row.updatedAt) else assertEquals(1L, row.updatedAt)
        }
        assertEquals(all.size - RuleStage.MUTATING.size, dao.countEnabled())
        assertEquals(0, registry.pausedCount)
        outcomes.forEach { assertEquals(com.example.interception.breakpoint.BreakpointAction.Resume, it.get(5, TimeUnit.SECONDS)) }
        assertEquals(1, transport.kills.get())
        assertTrue(client.isOpen)
        pool.shutdownNow()
    }

    // ── engine integration ───────────────────────────────────────────────────────────────────

    private class Dialed : java.io.IOException("dialed upstream")

    @Test
    fun `the engine observes but never mocks a flow whose uid is outside the selection and pairs REFUSED_SAFETY with the exchange`() {
        val gate = InterceptorSafetyGate(selectedOnly("com.target"), packagesForUid = { uid -> if (uid == 10_002) listOf("com.stranger") else listOf("com.target") })
        val snap = snapshot(rule(MockConfig(statusCode = 201, body = BodySource.Text("mocked")), name = "mock-it"), rule(ObserveConfig, name = "watch"))
        val sink = com.example.data.persistence.RecordingCaptureSink()
        val engine = com.example.interception.MitmProxyEngine(sink = sink, ruleSnapshot = { snap }, safetyGate = gate)
        val pending = java.util.concurrent.ConcurrentLinkedQueue<com.example.interception.MitmProxyEngine.PendingExchange>()
        val clientOut = java.io.ByteArrayOutputStream()
        val upstream = com.example.interception.LazyUpstreamConnection("api.example", 443, socketConnector = { _, _ -> throw Dialed() })

        // Refused uid: the mock must NOT be served -- the request goes upstream unmodified.
        try {
            engine.handleParsedRequest(db, message(), ByteArray(0), upstream, clientOut, Any(), pending, "api.example", 443, true, true, clientUid = 10_002)
            org.junit.Assert.fail("a refused mock must forward the request upstream")
        } catch (_: Dialed) {
        }
        assertEquals(0, clientOut.size())

        // Selected uid: the very same snapshot mocks the flow and never dials.
        val mockedOut = java.io.ByteArrayOutputStream()
        val neverDial = com.example.interception.LazyUpstreamConnection("api.example", 443, socketConnector = { _, _ -> throw AssertionError("mock must not dial") })
        engine.handleParsedRequest(db, message(), ByteArray(0), neverDial, mockedOut, Any(), pending, "api.example", 443, true, true, clientUid = 10_001)
        assertTrue(mockedOut.toString(Charsets.ISO_8859_1.name()).startsWith("HTTP/1.1 201"))
        val row = sink.rows.single()
        val applied = sink.appliedRules.filter { it.trafficId == row.id }
        assertEquals(FlowAuditTrail.Outcome.APPLIED.name, applied.single { it.ruleName == "mock-it" }.outcome)
    }

    @Test
    fun `a refused response-leg exchange persists REFUSED_SAFETY provenance and forwards the origin bytes`() {
        val gate = InterceptorSafetyGate(InterceptorSafetyGate.Policy.DEFAULT)
        val snap = snapshot(rule(RewriteConfig(responseModifications = listOf(Modification.SetHeader("x-rewritten", "yes"))), name = "rw"))
        val sink = com.example.data.persistence.RecordingCaptureSink()
        val engine = com.example.interception.MitmProxyEngine(sink = sink, ruleSnapshot = { snap }, safetyGate = gate)
        val pending = java.util.concurrent.ConcurrentLinkedQueue<com.example.interception.MitmProxyEngine.PendingExchange>().apply {
            add(com.example.interception.MitmProxyEngine.PendingExchange(message(), System.currentTimeMillis()))
        }
        val clientOut = java.io.ByteArrayOutputStream()
        val response = ParsedHttpMessage(
            isRequest = false, method = null, url = "https://api.example/x", host = "api.example", statusCode = 200, statusMessage = "OK",
            headers = mapOf("content-type" to "text/plain", "content-length" to "4"), body = "real", rawBodyBytes = "real".toByteArray(),
        )
        val raw = "HTTP/1.1 200 OK\r\ncontent-type: text/plain\r\ncontent-length: 4\r\n\r\nreal".toByteArray()
        engine.handleParsedResponse(db, response, raw, clientOut, Any(), pending, "api.example", 443, true, true, com.example.interception.MitmProxyEngine.ConnectionProtocolState())

        val wire = clientOut.toString(Charsets.ISO_8859_1.name())
        assertFalse(wire, wire.lowercase().contains("x-rewritten"))
        assertTrue(wire, wire.endsWith("real"))
        val row = sink.rows.single()
        val applied = sink.appliedRules.filter { it.trafficId == row.id }
        val refused = applied.single { it.outcome == FlowAuditTrail.Outcome.REFUSED_SAFETY.name }
        assertEquals("rw", refused.ruleName)
        assertTrue(refused.detail!!.contains("not acknowledged"))
        assertTrue(applied.none { it.outcome == FlowAuditTrail.Outcome.APPLIED.name })
    }

    // ── hard caps ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the 201st enabled rule is rejected by validation and excluded by the compiler`() {
        assertEquals(listOf("enabled"), RuleValidation.validate(rule(ObserveConfig), enabledRules = 200).map { it.field })
        assertTrue(RuleValidation.validate(rule(ObserveConfig), enabledRules = 199).isEmpty())
        val snapshot = RuleSnapshotCompiler.compile((1..201).map { entity(rule(ObserveConfig, name = "o$it")) }, emptyList(), revision = 1)
        assertEquals(200, snapshot.size)
        assertEquals(1, snapshot.issues.size)
        assertEquals("o201", snapshot.issues.single().ruleName)
    }

    @Test
    fun `the 9th paused flow is refused`() {
        val registry = BreakpointRegistry(slotWaitMs = 20)
        assertEquals(8, registry.maxPaused)
        val ref = RuleRef(1, "bp", RuleStage.BREAKPOINT)
        val pool = Executors.newCachedThreadPool()
        val started = CountDownLatch(8)
        (1..8).forEach { i ->
            pool.submit {
                val r = registry.tryPause(ref, FlowAuditTrail.Phase.REQUEST, message(), "api.example", 60_000, "p$i")
                started.countDown()
                (r as? BreakpointRegistry.PauseResult.Paused)?.let { registry.await(it.flow) }
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val ninth = registry.tryPause(ref, FlowAuditTrail.Phase.REQUEST, message(), "api.example", 60_000, "p9")
        assertTrue(ninth is BreakpointRegistry.PauseResult.Skipped)
        assertEquals(8, registry.resumeAll())
        pool.shutdownNow()
    }

    @Test
    fun `the 256th replay connection is refused`() {
        val permits = ReplayPermits()
        repeat(255) { assertTrue(permits.tryAcquire()) }
        assertEquals(255, permits.inFlight)
        assertFalse(permits.tryAcquire())
        permits.release()
        assertTrue(permits.tryAcquire())
        assertFalse(permits.tryAcquire())
    }

    @Test
    fun `an 11 s script timeout is rejected, and the host client never waits longer than the cap`() {
        assertEquals(listOf("timeoutMs"), RuleValidation.validate(rule(ScriptConfig(source = "1", timeoutMs = 11_000))).map { it.field })
        assertTrue(RuleValidation.validate(rule(ScriptConfig(source = "1", timeoutMs = 10_000))).isEmpty())
        assertEquals(RuleLimits.MAX_SCRIPT_TIMEOUT_MS, ScriptHostClient.MAX_TIMEOUT_MS)
        assertEquals(RuleLimits.MAX_PAUSED_FLOWS, BreakpointRegistry.MAX_PAUSED_FLOWS)
    }
}
