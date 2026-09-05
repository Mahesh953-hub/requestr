package com.example.interception.breakpoint

import com.example.data.models.HttpMethod
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleRef
import com.example.interception.rules.RuleStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 task 31 / gate G21: the paused-flow queue. Every wait here runs on its own thread the way a
 * relay leg would, so "a pause blocks only itself" is exercised for real rather than simulated.
 */
class BreakpointRegistryTest {

    private val rule = RuleRef(7L, "pause-it", RuleStage.BREAKPOINT)
    private val pool = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun request(path: String = "/x") = ParsedHttpMessage(
        isRequest = true, method = HttpMethod.GET, url = "https://app.example$path", path = path, host = "app.example", port = 443,
        headers = mapOf("host" to "app.example"),
    )

    private fun pauseOnThread(
        registry: BreakpointRegistry,
        timeoutMs: Long,
        flowId: String,
        started: CountDownLatch? = null,
    ): Future<Pair<BreakpointRegistry.PauseResult, BreakpointAction?>> = pool.submit<Pair<BreakpointRegistry.PauseResult, BreakpointAction?>> {
        val r = registry.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request(), "app.example", timeoutMs, flowId)
        started?.countDown()
        val action = (r as? BreakpointRegistry.PauseResult.Paused)?.let { registry.await(it.flow) }
        r to action
    }

    private fun awaitPaused(registry: BreakpointRegistry, n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (registry.pausedCount < n && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(n, registry.pausedCount)
    }

    @Test
    fun `slot exhaustion fails open -- the ninth flow proceeds unpaused and records why`() {
        val registry = BreakpointRegistry(maxPaused = 8, slotWaitMs = 50)
        val futures = (1..8).map { pauseOnThread(registry, 60_000, "f$it") }
        awaitPaused(registry, 8)
        assertEquals(0, registry.availableSlots)

        val ninth = registry.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request(), "app.example", 60_000, "f9")
        assertTrue(ninth is BreakpointRegistry.PauseResult.Skipped)
        assertEquals("breakpoint queue full (8 paused)", (ninth as BreakpointRegistry.PauseResult.Skipped).reason)
        assertNull(registry["f9"])
        assertEquals(8, registry.pausedCount)

        // Free one slot: the next attempt gets in.
        assertTrue(registry.resume("f1"))
        assertEquals(BreakpointAction.Resume, futures[0].get(5, TimeUnit.SECONDS).second)
        val tenth = registry.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request(), "app.example", 60_000, "f10")
        assertTrue(tenth is BreakpointRegistry.PauseResult.Paused)
        assertEquals(8, registry.pausedCount)
        assertEquals(8, registry.resumeAll())
        futures.drop(1).forEach { assertEquals(BreakpointAction.Resume, it.get(5, TimeUnit.SECONDS).second) }
    }

    @Test
    fun `auto-resume fires at the rule timeout and releases the slot`() {
        val registry = BreakpointRegistry(maxPaused = 2, slotWaitMs = 10)
        val t0 = System.currentTimeMillis()
        val f = pauseOnThread(registry, 200, "auto")
        val (result, action) = f.get(5, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - t0
        assertTrue(result is BreakpointRegistry.PauseResult.Paused)
        assertEquals(BreakpointAction.AutoResume, action)
        assertTrue("auto-resume should wait ~200ms, took ${elapsed}ms", elapsed in 150..2_000)
        assertEquals(0, registry.pausedCount)
        assertEquals(2, registry.availableSlots)
        // Settling after the fact is a no-op: nothing is paused under that id any more.
        assertFalse(registry.resume("auto"))
    }

    @Test
    fun `Abort completes the wait with the reason and frees the slot`() {
        val registry = BreakpointRegistry(maxPaused = 1, slotWaitMs = 10)
        val f = pauseOnThread(registry, 60_000, "a")
        awaitPaused(registry, 1)
        assertTrue(registry.abort("a", "operator dropped it"))
        val (_, action) = f.get(5, TimeUnit.SECONDS)
        assertEquals(BreakpointAction.Abort("operator dropped it"), action)
        assertEquals(1, registry.availableSlots)
        // The slot is really free: a new flow gets in without waiting.
        val next = registry.tryPause(rule, FlowAuditTrail.Phase.RESPONSE, request(), "app.example", 60_000, "b")
        assertTrue(next is BreakpointRegistry.PauseResult.Paused)
        registry.resumeAll()
    }

    @Test
    fun `disconnect invalidation completes the future exactly once -- later actions are ignored`() {
        val registry = BreakpointRegistry(maxPaused = 4, slotWaitMs = 10)
        val released = CopyOnWriteArrayList<BreakpointAction>()
        registry.addListener(object : BreakpointRegistry.Listener {
            override fun onReleased(flow: BreakpointRegistry.PausedFlow, action: BreakpointAction) { released += action }
        })
        val f = pauseOnThread(registry, 60_000, "d")
        awaitPaused(registry, 1)
        assertTrue(registry.invalidate("d", "client socket closed"))
        // Racing operator actions on the same flow lose: the future was already completed.
        assertFalse(registry.resume("d"))
        val (_, action) = f.get(5, TimeUnit.SECONDS)
        assertEquals(BreakpointAction.Abort("client socket closed"), action)
        assertFalse(registry.replace("d", request("/late")))
        assertEquals(listOf<BreakpointAction>(BreakpointAction.Abort("client socket closed")), released)
        assertEquals(0, registry.pausedCount)
    }

    @Test
    fun `invalidateConnection aborts only the flows parked on that connection`() {
        val registry = BreakpointRegistry(maxPaused = 4, slotWaitMs = 10)
        val a = pool.submit<BreakpointAction?> {
            val r = registry.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request(), "app.example", 60_000, "a", connectionKey = "conn-1")
            (r as BreakpointRegistry.PauseResult.Paused).let { registry.await(it.flow) }
        }
        val b = pool.submit<BreakpointAction?> {
            val r = registry.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request(), "app.example", 60_000, "b", connectionKey = "conn-2")
            (r as BreakpointRegistry.PauseResult.Paused).let { registry.await(it.flow) }
        }
        awaitPaused(registry, 2)
        assertEquals(1, registry.invalidateConnection("conn-1", "gone"))
        assertEquals(BreakpointAction.Abort("gone"), a.get(5, TimeUnit.SECONDS))
        assertEquals(1, registry.pausedCount)
        assertNotNull(registry["b"])
        assertTrue(registry.resume("b"))
        assertEquals(BreakpointAction.Resume, b.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `resumeAll drains every paused flow with Resume and reports the count`() {
        val registry = BreakpointRegistry(maxPaused = 8, slotWaitMs = 10)
        val futures = (1..5).map { pauseOnThread(registry, 60_000, "r$it") }
        awaitPaused(registry, 5)
        assertEquals(5, registry.resumeAll())
        futures.forEach { assertEquals(BreakpointAction.Resume, it.get(5, TimeUnit.SECONDS).second) }
        assertEquals(0, registry.pausedCount)
        assertEquals(8, registry.availableSlots)
        assertEquals(0, registry.resumeAll())
    }

    @Test
    fun `Replace hands the edited message to the waiting relay thread`() {
        val registry = BreakpointRegistry(maxPaused = 2, slotWaitMs = 10)
        val f = pauseOnThread(registry, 60_000, "e")
        awaitPaused(registry, 1)
        val paused = registry["e"]!!
        assertEquals("https://app.example/x", paused.url)
        assertEquals("GET", paused.method)
        assertEquals(FlowAuditTrail.Phase.REQUEST, paused.phase)
        assertTrue(paused.timeoutAt - paused.pausedAt == 60_000L)
        val edited = request("/edited")
        assertTrue(registry.replace("e", edited))
        val (_, action) = f.get(5, TimeUnit.SECONDS)
        assertTrue(action is BreakpointAction.Replace)
        assertEquals("https://app.example/edited", (action as BreakpointAction.Replace).message.url)
    }

    @Test
    fun `listeners see onPaused then exactly one onReleased per flow`() {
        val registry = BreakpointRegistry(maxPaused = 2, slotWaitMs = 10)
        val pausedSeen = AtomicInteger()
        val releasedSeen = AtomicInteger()
        registry.addListener(object : BreakpointRegistry.Listener {
            override fun onPaused(flow: BreakpointRegistry.PausedFlow) { pausedSeen.incrementAndGet() }
            override fun onReleased(flow: BreakpointRegistry.PausedFlow, action: BreakpointAction) { releasedSeen.incrementAndGet() }
        })
        val f1 = pauseOnThread(registry, 100, "l1")
        val f2 = pauseOnThread(registry, 60_000, "l2")
        awaitPaused(registry, 2)
        registry.abort("l2")
        f1.get(5, TimeUnit.SECONDS); f2.get(5, TimeUnit.SECONDS)
        assertEquals(2, pausedSeen.get())
        assertEquals(2, releasedSeen.get())
    }

    @Test
    fun `paused flows are listed oldest first`() {
        val clockNow = AtomicInteger(1_000)
        val ticking = BreakpointRegistry(maxPaused = 4, slotWaitMs = 10, clock = { clockNow.getAndAdd(10).toLong() })
        // Register in order on this thread (tryPause never blocks while slots are free), then
        // park each flow's wait on its own thread like a relay leg would.
        val flows = listOf("first", "second", "third").map { id ->
            (ticking.tryPause(rule, FlowAuditTrail.Phase.REQUEST, request("/$id"), "app.example", 60_000, id) as BreakpointRegistry.PauseResult.Paused).flow
        }
        val fs = flows.map { flow -> pool.submit<BreakpointAction> { ticking.await(flow) } }
        assertEquals(listOf("first", "second", "third"), ticking.pausedFlows.map { it.flowId })
        assertEquals(listOf(1_000L, 1_010L, 1_020L), ticking.pausedFlows.map { it.pausedAt })
        assertEquals(3, ticking.resumeAll())
        fs.forEach { assertEquals(BreakpointAction.Resume, it.get(5, TimeUnit.SECONDS)) }
    }
}
