package com.example.interception

import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction

/**
 * Single evaluation point for a CONNECT (HTTPS tunnel establishment) request, used by every
 * CONNECT call site ([MitmProxyEngine], [HttpProxyEngine], [com.example.interception.gateway.GatewayEngine])
 * so they can't silently drift on rule semantics.
 *
 * Drives off the EXISTING `intercept_rules` table via [RuleEnforcer] -- no parallel rule
 * system. Evaluated BEFORE the `200 Connection Established` response is written, mirroring the
 * pre-200 BLOCK check [MitmProxyEngine] already did for BLOCK only.
 */
object ConnectControl {
    sealed class Disposition {
        /** No rule (or a rule with no CONNECT-level meaning) matched: tunnel opaquely. */
        data object AllowOpaque : Disposition()

        /** A FORCE_MITM rule matched: the caller should terminate TLS itself when it can. */
        data object ForceMitm : Disposition()

        data class Block(val reason: String? = null) : Disposition()

        /** [target] is `"host:port"` -- the upstream to tunnel/dial to instead of the original. */
        data class Redirect(val target: String) : Disposition()
    }

    /**
     * Evaluates CONNECT rules for [host]:[port]. [clientUid]/[sourceIp] are accepted so callers
     * can pass them straight through to [com.example.data.local.ConnectAttemptEntity] persistence
     * without a second lookup; this function itself does not use them for rule matching (rules
     * match on host/URL only, same as [RuleEnforcer.checkRules] everywhere else in the codebase).
     */
    fun evaluate(
        db: RequestrDatabase,
        host: String,
        port: Int,
        clientUid: Int? = null,
        sourceIp: String? = null,
    ): Disposition {
        val action = RuleEnforcer.checkRules(db, "https://$host:$port/", host)
        return when (action) {
            RuleAction.BLOCK -> Disposition.Block("Blocked by rule for $host")
            RuleAction.FORCE_MITM -> Disposition.ForceMitm
            RuleAction.REDIRECT -> {
                val rule = RuleEnforcer.getMatchingRule(db, "https://$host:$port/", host)
                val target = rule?.replacementValue?.takeIf { it.isNotBlank() }
                    ?: return Disposition.AllowOpaque
                Disposition.Redirect(target)
            }
            // LOG_ONLY, MODIFY_REQUEST, MODIFY_RESPONSE have no CONNECT-level meaning (they
            // apply to parsed HTTP requests/responses inside a tunnel, not the tunnel itself) --
            // fall through to an opaque tunnel, which is the pre-existing default behavior.
            else -> Disposition.AllowOpaque
        }
    }
}
