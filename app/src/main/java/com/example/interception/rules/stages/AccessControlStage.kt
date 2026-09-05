package com.example.interception.rules.stages

import com.example.interception.ConnectControl
import com.example.interception.ConnectControl.Disposition
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.GatewayOutcome
import com.example.interception.rules.RuleDecision
import java.net.URI

/**
 * S4 task 27: the GATEWAY slot, mapped onto the CONNECT-level [Disposition] every proxy front
 * end already consumes (`MitmProxyEngine`, `HttpProxyEngine`, `GatewayEngine`).
 *
 * Precedence is decided by the pipeline (`ALLOW_ONLY > BYPASS > BLOCK > SUSPEND > FORCE_DECRYPT`,
 * see [com.example.interception.rules.RulePipeline]); this stage only translates the winner:
 *  - `ALLOW_ONLY` match / no gateway rule → fall through to mirror / redirect / opaque;
 *  - implied block (ALLOW_ONLY rules exist, none matched) → [Disposition.Block];
 *  - `BYPASS` → [Disposition.AllowOpaque] (never decrypted, even if a FORCE_DECRYPT rule also matched);
 *  - `BLOCK` → [Disposition.Block];
 *  - `SUSPEND` → [Disposition.Suspend] -- the flow is parked without a relay permit until the
 *    rule's timeout, then closed;
 *  - `FORCE_DECRYPT` → [Disposition.ForceMitm], unless the pinning-failure registry has tripped
 *    for this host, in which case the tunnel degrades to opaque (pre-existing behaviour).
 * After the gateway: a MIRROR rule re-points the tunnel ([Disposition.Mirror]); a REWRITE
 * redirection re-points it to the target's host:port ([Disposition.Redirect], legacy CONNECT
 * REDIRECT parity); otherwise the tunnel is opaque.
 */
object AccessControlStage {

    fun disposition(
        decision: RuleDecision,
        host: String,
        port: Int,
        trail: FlowAuditTrail? = null,
        pinningBypass: (String) -> Boolean = { ConnectControl.PinningFailureRegistry.shouldBypass(it) },
    ): Disposition {
        val phase = FlowAuditTrail.Phase.CONNECT
        when (val gateway = decision.gateway) {
            GatewayOutcome.Pass -> Unit
            is GatewayOutcome.ImpliedBlock -> {
                for (r in gateway.allowOnlyRules) trail?.applied(r, phase, "implied block: not in allow-list")
                return Disposition.Block("Blocked: $host is not in the allow-list")
            }
            is GatewayOutcome.Apply -> when (gateway.mode) {
                GatewayMode.ALLOW_ONLY -> trail?.applied(gateway.rule, phase, "allow-listed")
                GatewayMode.BYPASS -> {
                    trail?.applied(gateway.rule, phase, "bypass: opaque tunnel")
                    return Disposition.AllowOpaque
                }
                GatewayMode.BLOCK -> {
                    trail?.applied(gateway.rule, phase, "blocked")
                    return Disposition.Block("Blocked by rule for $host")
                }
                GatewayMode.SUSPEND -> {
                    trail?.applied(gateway.rule, phase, "suspended ${gateway.config.suspendTimeoutMs}ms")
                    return Disposition.Suspend(gateway.config.suspendTimeoutMs, gateway.rule.name)
                }
                GatewayMode.FORCE_DECRYPT -> {
                    return if (pinningBypass(host)) {
                        trail?.refused(gateway.rule, phase, "pinning-failure bypass active for $host")
                        Disposition.AllowOpaque
                    } else {
                        trail?.applied(gateway.rule, phase, "force decrypt")
                        Disposition.ForceMitm
                    }
                }
            }
        }
        decision.mirror?.let { mirror ->
            val target = "${mirror.config.targetHost}:${mirror.config.targetPort ?: port}"
            trail?.applied(mirror.rule, phase, "mirror -> $target")
            return Disposition.Mirror(
                targetHost = mirror.config.targetHost,
                targetPort = mirror.config.targetPort ?: port,
                hostPolicy = mirror.config.hostPolicy,
                sniPolicy = mirror.config.sniPolicy,
                cnPolicy = mirror.config.cnPolicy,
                ruleName = mirror.rule.name,
            )
        }
        decision.rewrite.redirection?.let { redirect ->
            val target = redirect.config.redirection?.targetUrl?.trim().orEmpty()
            if (target.isEmpty()) return@let
            trail?.applied(redirect.rule, phase, "redirect tunnel -> $target")
            return Disposition.Redirect(connectTarget(target, port))
        }
        return Disposition.AllowOpaque
    }

    /**
     * Legacy CONNECT REDIRECT took the raw `replacementValue` as `host[:port]`; a typed
     * redirection carries a URL. Accept both: a URL yields its host and explicit/default port.
     */
    internal fun connectTarget(target: String, defaultPort: Int): String {
        if (!target.contains("://")) return target
        val uri = runCatching { URI(target) }.getOrNull() ?: return target
        val host = uri.host ?: return target
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("http", ignoreCase = true) -> 80
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> defaultPort
        }
        return "$host:$port"
    }
}
