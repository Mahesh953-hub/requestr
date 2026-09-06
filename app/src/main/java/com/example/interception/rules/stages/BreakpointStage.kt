package com.example.interception.rules.stages

import com.example.interception.http.HttpBodyDecoder
import com.example.interception.breakpoint.BreakpointAction
import com.example.interception.breakpoint.BreakpointRegistry
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BreakpointConfig
import com.example.interception.rules.BreakpointPhase
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleRef

/**
 * S4 task 31: the BREAKPOINT stage. Sits after rewrite and before scripts on both legs.
 *
 * The stage decides whether this phase pauses ([applies]), parks the flow in the
 * [BreakpointRegistry] on the flow's own relay thread ([pause]), and turns the operator's
 * [BreakpointAction] into an [Outcome] the engine acts on: forward (possibly edited) or abort.
 * The message is stamped with `x-requestr-breakpoint: rule="<name>"; action=<...>` so the
 * persisted row and the client under test both show the pause happened.
 */
object BreakpointStage {
    sealed interface Outcome {
        /**
         * Forward. [changed] is true when the message was edited (Replace) and must be
         * re-serialized. [actionName] is what [stamp] writes: the engine stamps the request leg
         * AFTER serialization (persisted row only -- never leaks to the origin) and the response
         * leg before (the client under test sees the pause happened).
         */
        data class Forward(val action: BreakpointAction, val changed: Boolean, val actionName: String) : Outcome

        /** Do not forward; the engine answers with [abortResponse]. */
        data class Aborted(val reason: String) : Outcome

        /** No slot within `SLOT_WAIT_MS`: forwarded unchanged, audit says SKIPPED_PRECEDENCE. */
        data class Skipped(val reason: String) : Outcome
    }

    /** True when [config] pauses [phase]. */
    fun applies(config: BreakpointConfig, phase: FlowAuditTrail.Phase): Boolean = when (config.phase) {
        BreakpointPhase.BOTH -> phase != FlowAuditTrail.Phase.CONNECT
        BreakpointPhase.REQUEST -> phase == FlowAuditTrail.Phase.REQUEST
        BreakpointPhase.RESPONSE -> phase == FlowAuditTrail.Phase.RESPONSE
    }

    /**
     * Pauses [message] (blocking the caller) until acted on or auto-resumed. Records the audit
     * entry and stamps the metadata header. Must be called on the flow's own relay thread.
     */
    fun pause(
        registry: BreakpointRegistry,
        rule: RuleRef,
        config: BreakpointConfig,
        phase: FlowAuditTrail.Phase,
        message: ParsedHttpMessage,
        host: String,
        trail: FlowAuditTrail?,
        flowId: String? = null,
        connectionKey: String? = null,
    ): Outcome {
        val paused = when (
            val r = if (flowId != null) registry.tryPause(rule, phase, message, host, config.timeoutMs, flowId, connectionKey)
            else registry.tryPause(rule, phase, message, host, config.timeoutMs, connectionKey = connectionKey)
        ) {
            is BreakpointRegistry.PauseResult.Skipped -> {
                trail?.record(rule, phase, FlowAuditTrail.Outcome.SKIPPED_PRECEDENCE, r.reason)
                return Outcome.Skipped(r.reason)
            }
            is BreakpointRegistry.PauseResult.Paused -> r.flow
        }
        val action = registry.await(paused)
        val waitedMs = System.currentTimeMillis() - paused.pausedAt
        return when (action) {
            BreakpointAction.Resume -> {
                trail?.applied(rule, phase, "paused ${waitedMs}ms; resumed")
                Outcome.Forward(action, changed = false, actionName = "resumed")
            }
            BreakpointAction.AutoResume -> {
                trail?.applied(rule, phase, "paused ${waitedMs}ms; auto-resumed at ${config.timeoutMs}ms")
                Outcome.Forward(action, changed = false, actionName = "auto-resumed")
            }
            is BreakpointAction.Replace -> {
                applyReplacement(message, action.message)
                trail?.applied(rule, phase, "paused ${waitedMs}ms; replaced")
                Outcome.Forward(action, changed = true, actionName = "replaced")
            }
            is BreakpointAction.Abort -> {
                trail?.applied(rule, phase, "paused ${waitedMs}ms; aborted (${action.reason})")
                Outcome.Aborted(action.reason)
            }
        }
    }

    /** Copies the operator's edits onto the paused message so the engine re-serializes it. */
    internal fun applyReplacement(target: ParsedHttpMessage, edited: ParsedHttpMessage) {
        if (target.isRequest) {
            if (edited.url.isNotEmpty()) target.url = edited.url
            if (edited.host.isNotEmpty()) target.host = edited.host
        } else if (edited.statusCode > 0) {
            target.statusCode = edited.statusCode
        }
        target.headers = edited.headers.toMap()
        val bytes = edited.replacedBodyBytes ?: edited.rawBodyBytes?.takeIf { edited.isBodyBinary }
        if (bytes != null && (edited.isBodyBinary || edited.bodyReplaced)) {
            target.body = HttpBodyDecoder.BINARY_BODY_PLACEHOLDER
            target.replacedBodyBytes = bytes
            target.isBodyBinary = true
        } else {
            target.body = edited.body
            target.replacedBodyBytes = null
            target.isBodyBinary = false
        }
        target.bodyReplaced = true
    }

    /** The synthetic reply the client gets when a paused flow is aborted. */
    fun abortResponse(rule: RuleRef, reason: String, version: String = "HTTP/1.1"): ParsedHttpMessage =
        ParsedHttpMessage(
            isRequest = false,
            method = null,
            version = version.ifEmpty { "HTTP/1.1" },
            statusCode = ABORT_STATUS,
            statusMessage = ABORT_STATUS_MESSAGE,
            headers = mapOf(
                "content-type" to "text/plain; charset=utf-8",
                "connection" to "close",
                RuleMetadataHeaders.BREAKPOINT to "${RuleMetadataHeaders.ruleAttribution(rule.name)}; action=aborted",
            ),
            body = "requestr: flow aborted at breakpoint ($reason)",
            contentType = "text/plain",
            bodyReplaced = true,
        )

    /** `x-requestr-breakpoint: rule="<name>"; action=<action>` on [message]. */
    fun stamp(message: ParsedHttpMessage, rule: RuleRef, action: String) {
        val mutable = message.headers.toMutableMap()
        mutable[RuleMetadataHeaders.BREAKPOINT] = "${RuleMetadataHeaders.ruleAttribution(rule.name)}; action=$action"
        message.headers = mutable
    }

    /** Non-standard but conventional "client closed request" -- the exchange never completed. */
    const val ABORT_STATUS = 499
    const val ABORT_STATUS_MESSAGE = "Aborted At Breakpoint"
}
