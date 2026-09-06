package com.example.interception.rules.stages

import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.MirrorConfig
import com.example.interception.rules.NamePolicy
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleRef

/**
 * S4 task 28 -- MIRROR stage. Re-points a flow for host A at host B *before* TLS: the client
 * keeps talking to A (it CONNECTed to A and validates A's leaf), while requestr dials B. The
 * three names a mirrored flow presents are independent ([MirrorConfig.hostPolicy],
 * [MirrorConfig.sniPolicy], [MirrorConfig.cnPolicy]) so a staging box that only answers its own
 * SNI, a CDN that routes on `Host`, and a pinned client that only accepts A's CN can all be
 * served by one rule.
 *
 * [plan] is pure and runs once per CONNECT; [applyHostHeader] runs per decrypted request.
 */
object MirrorStage {

    /**
     * Everything the CONNECT path needs, resolved from the three policies.
     *
     *  - [dialHost]/[dialPort]: where the upstream TCP connection goes (always the mirror).
     *  - [sniName]: `server_name` for the upstream ClientHello, and the name the upstream leaf
     *    is verified against.
     *  - [hostHeader]: value to force onto the request `Host:` header; null = leave it alone.
     *  - [certAuthority]/[certPort]/[certSni]: inputs for the client-facing leaf. PRESERVE keeps
     *    today's identity (client SNI, else CONNECT host); USE_CONNECT_ADDRESS is the CONNECT
     *    host even when the ClientHello said otherwise; USE_MIRROR_ADDRESS issues for the mirror
     *    host -- and, when upstream sniffing is on, sniffs the *mirror's* leaf.
     */
    data class Plan(
        val rule: RuleRef,
        val connectHost: String,
        val connectPort: Int,
        val dialHost: String,
        val dialPort: Int,
        val sniName: String,
        val hostHeader: String?,
        val certAuthority: String,
        val certPort: Int,
        val certSni: String?,
    ) {
        val target: String get() = "$dialHost:$dialPort"

        /** `rule="<name>"; target=<host:port>` -- value of [RuleMetadataHeaders.MIRROR]. */
        val attribution: String get() = "${RuleMetadataHeaders.ruleAttribution(rule.name)}; target=$target"
    }

    fun plan(connectHost: String, connectPort: Int, clientSni: String?, config: MirrorConfig, rule: RuleRef): Plan {
        val dialHost = config.targetHost.trim()
        val dialPort = config.targetPort ?: connectPort
        val sni = clientSni?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        val preservedName = sni ?: connectHost
        val sniName = when (config.sniPolicy) {
            NamePolicy.PRESERVE -> preservedName
            NamePolicy.USE_CONNECT_ADDRESS -> connectHost
            NamePolicy.USE_MIRROR_ADDRESS -> dialHost
        }
        val hostHeader = when (config.hostPolicy) {
            NamePolicy.PRESERVE -> null
            NamePolicy.USE_CONNECT_ADDRESS -> authority(connectHost, connectPort)
            NamePolicy.USE_MIRROR_ADDRESS -> authority(dialHost, dialPort)
        }
        val (certAuthority, certPort, certSni) = when (config.cnPolicy) {
            NamePolicy.PRESERVE -> Triple(connectHost, connectPort, sni)
            NamePolicy.USE_CONNECT_ADDRESS -> Triple(connectHost, connectPort, null)
            NamePolicy.USE_MIRROR_ADDRESS -> Triple(dialHost, dialPort, null)
        }
        return Plan(
            rule = rule, connectHost = connectHost, connectPort = connectPort,
            dialHost = dialHost, dialPort = dialPort, sniName = sniName, hostHeader = hostHeader,
            certAuthority = certAuthority, certPort = certPort, certSni = certSni,
        )
    }

    /** `host` for the default TLS port, `host:port` otherwise -- what a browser would put in `Host:`. */
    fun authority(host: String, port: Int): String = if (port == 443 || port <= 0) host else "$host:$port"

    /**
     * Forces the request's `Host:` header per [Plan.hostHeader]. Returns true when the header
     * value actually changed (the caller must then re-serialize). Case of an existing header name
     * is preserved; [ParsedHttpMessage.host]/[ParsedHttpMessage.url] are left alone so later
     * stages and the persisted row still describe the client's view of the flow.
     */
    fun applyHostHeader(request: ParsedHttpMessage, plan: Plan, trail: FlowAuditTrail? = null): Boolean {
        val wanted = plan.hostHeader ?: return false
        val existingKey = request.headers.keys.firstOrNull { it.equals("host", ignoreCase = true) }
        if (existingKey != null && request.headers[existingKey] == wanted) return false
        val mutable = request.headers.toMutableMap()
        mutable[existingKey ?: "Host"] = wanted
        request.headers = mutable
        trail?.applied(plan.rule, FlowAuditTrail.Phase.REQUEST, "host header -> $wanted")
        return true
    }

    /** Stamps [RuleMetadataHeaders.MIRROR] on the response so the row (and client) can see the mirror. */
    fun stampAttribution(response: ParsedHttpMessage, plan: Plan) {
        val mutable = response.headers.toMutableMap()
        mutable.keys.removeAll { it.equals(RuleMetadataHeaders.MIRROR, ignoreCase = true) }
        mutable[RuleMetadataHeaders.MIRROR] = plan.attribution
        response.headers = mutable
    }
}
