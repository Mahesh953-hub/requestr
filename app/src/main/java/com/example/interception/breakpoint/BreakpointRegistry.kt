package com.example.interception.breakpoint

import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleRef
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What the operator (or the engine on the operator's behalf) decided for a paused flow. */
sealed interface BreakpointAction {
    /** Forward the message exactly as it was paused. */
    data object Resume : BreakpointAction

    /** Forward [message] instead of the paused one (edited in the breakpoint workspace). */
    data class Replace(val message: ParsedHttpMessage) : BreakpointAction

    /** Do not forward; the flow is answered with a synthetic abort. */
    data class Abort(val reason: String = "aborted at breakpoint") : BreakpointAction

    /** Nobody acted before [com.example.interception.rules.BreakpointConfig.timeoutMs]; forwarded unchanged. */
    data object AutoResume : BreakpointAction
}

/**
 * S4 task 31 (gate G21): the bounded queue of flows parked at a BREAKPOINT rule.
 *
 *  - A slot ([MAX_PAUSED_FLOWS] = 8) is acquired **before** pausing. A flow that cannot get one
 *    within [slotWaitMs] proceeds unpaused (fail-open -- a full queue must never wedge traffic)
 *    and the stage records `SKIPPED_PRECEDENCE`.
 *  - Every paused flow waits on its own [CompletableFuture] on its own relay thread, so a pause
 *    blocks exactly that flow. Auto-resume is mandatory: [await] completes the future with
 *    [BreakpointAction.AutoResume] at the rule's timeout.
 *  - [invalidate] (client or upstream socket gone) completes with `Abort` and frees the slot;
 *    [resumeAll] is the kill switch (task 33) and the notification's "resume all" action.
 *  - [Listener]s mirror the queue into Room (`breakpoint_paused_flows`) and the foreground
 *    notification; the registry itself is pure in-memory state and has no Android dependency.
 */
class BreakpointRegistry(
    val maxPaused: Int = MAX_PAUSED_FLOWS,
    private val slotWaitMs: Long = SLOT_WAIT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class PausedFlow internal constructor(
        val flowId: String,
        val rule: RuleRef,
        val phase: FlowAuditTrail.Phase,
        val host: String,
        val url: String,
        val method: String?,
        /** The message as paused -- the workspace edits a copy and submits it via [replace]. */
        val message: ParsedHttpMessage,
        val pausedAt: Long,
        val timeoutAt: Long,
        /** Opaque per-connection key so a torn-down connection can [invalidateConnection] its flows. */
        val connectionKey: String?,
    ) {
        internal val future = CompletableFuture<BreakpointAction>()
        val isSettled: Boolean get() = future.isDone
    }

    interface Listener {
        fun onPaused(flow: PausedFlow) {}
        fun onReleased(flow: PausedFlow, action: BreakpointAction) {}
    }

    sealed interface PauseResult {
        data class Paused(val flow: PausedFlow) : PauseResult
        data class Skipped(val reason: String) : PauseResult
    }

    private val slots = Semaphore(maxPaused, true)
    private val paused = ConcurrentHashMap<String, PausedFlow>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    val pausedCount: Int get() = paused.size
    val pausedFlows: List<PausedFlow> get() = paused.values.sortedBy { it.pausedAt }
    val availableSlots: Int get() = slots.availablePermits()

    fun addListener(listener: Listener) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    operator fun get(flowId: String): PausedFlow? = paused[flowId]

    /**
     * Reserves a slot and registers the flow. Returns [PauseResult.Skipped] when no slot frees
     * up within [slotWaitMs] -- the caller then forwards the message unchanged.
     */
    fun tryPause(
        rule: RuleRef,
        phase: FlowAuditTrail.Phase,
        message: ParsedHttpMessage,
        host: String,
        timeoutMs: Long,
        flowId: String = UUID.randomUUID().toString(),
        connectionKey: String? = null,
    ): PauseResult {
        val acquired = try {
            slots.tryAcquire(slotWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return PauseResult.Skipped("breakpoint queue full ($maxPaused paused)")
        val now = clock()
        val flow = PausedFlow(
            flowId = flowId, rule = rule, phase = phase, host = host, url = message.url,
            method = message.method?.name, message = message, pausedAt = now,
            timeoutAt = now + timeoutMs.coerceAtLeast(0L), connectionKey = connectionKey,
        )
        paused[flowId] = flow
        listeners.forEach { runCatching { it.onPaused(flow) } }
        return PauseResult.Paused(flow)
    }

    /**
     * Blocks the calling (relay) thread until the flow is acted on or its timeout elapses.
     * Always releases the slot and de-registers the flow, whatever happened.
     */
    fun await(flow: PausedFlow): BreakpointAction {
        val action = try {
            val remaining = (flow.timeoutAt - clock()).coerceAtLeast(0L)
            flow.future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            flow.future.complete(BreakpointAction.AutoResume)
            flow.future.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            flow.future.complete(BreakpointAction.Abort("relay interrupted"))
            flow.future.get()
        } catch (e: Exception) {
            flow.future.complete(BreakpointAction.Abort("breakpoint wait failed: ${e.message}"))
            flow.future.get()
        } finally {
            release(flow)
        }
        listeners.forEach { runCatching { it.onReleased(flow, action) } }
        return action
    }

    private fun release(flow: PausedFlow) {
        if (paused.remove(flow.flowId, flow)) slots.release()
    }

    private fun settle(flowId: String, action: BreakpointAction): Boolean {
        val flow = paused[flowId] ?: return false
        return flow.future.complete(action)
    }

    /** Operator: forward unchanged. */
    fun resume(flowId: String): Boolean = settle(flowId, BreakpointAction.Resume)

    /** Operator: forward the edited message. */
    fun replace(flowId: String, message: ParsedHttpMessage): Boolean = settle(flowId, BreakpointAction.Replace(message))

    /** Operator: drop the flow. */
    fun abort(flowId: String, reason: String = "aborted by operator"): Boolean = settle(flowId, BreakpointAction.Abort(reason))

    /** Engine: the client or upstream vanished while paused -- completes exactly once. */
    fun invalidate(flowId: String, reason: String): Boolean = settle(flowId, BreakpointAction.Abort(reason))

    /** Engine: the connection that owns these flows is gone -- abort every flow still paused on it. */
    fun invalidateConnection(connectionKey: String, reason: String): Int {
        var n = 0
        for (flow in paused.values.toList()) {
            if (flow.connectionKey == connectionKey && flow.future.complete(BreakpointAction.Abort(reason))) n++
        }
        return n
    }

    /** Kill switch / notification action: every paused flow proceeds unchanged. */
    fun resumeAll(): Int {
        var n = 0
        for (flow in paused.values.toList()) if (flow.future.complete(BreakpointAction.Resume)) n++
        return n
    }

    companion object {
        const val MAX_PAUSED_FLOWS = com.example.interception.rules.RuleLimits.MAX_PAUSED_FLOWS
        const val SLOT_WAIT_MS = 250L

        /** Process-wide queue the MITM engine, the API and the UI share. */
        val shared: BreakpointRegistry by lazy { BreakpointRegistry() }
    }
}
