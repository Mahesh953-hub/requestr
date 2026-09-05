package com.example.interception

import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.NamePolicy
import com.example.interception.rules.RuleDecision
import com.example.interception.rules.RulePipeline
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotStore
import com.example.interception.rules.stages.AccessControlStage
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Single evaluation point for a CONNECT (HTTPS tunnel establishment) request, used by every
 * CONNECT call site ([MitmProxyEngine], [HttpProxyEngine], [com.example.interception.gateway.GatewayEngine])
 * so they can't silently drift on rule semantics.
 *
 * Drives off the EXISTING `intercept_rules` table via [RuleEnforcer] -- no parallel rule
 * system. Evaluated BEFORE the `200 Connection Established` response is written, mirroring the
 * pre-200 BLOCK check [MitmProxyEngine] already did for BLOCK only.
 *
 * Two behaviors are layered on top of the plain rule lookup, both automatic and both opt-in by
 * construction (a caller that never touches them sees exactly the old behavior):
 *  - [resolveClientUid] gives every CONNECT call site a non-root way to attribute a connection to
 *    a UID (see its kdoc) -- [MitmProxyEngine] already has a root-adjacent `ConnectivityManager`
 *    path for API 29+ while it is the active VPN; this is the fallback/complement for older API
 *    levels or contexts where that lookup is unavailable.
 *  - [PinningFailureRegistry] tracks repeated cert-pinning-shaped TLS failures per host and
 *    [evaluate] silently downgrades a would-be [Disposition.ForceMitm] to [Disposition.AllowOpaque]
 *    once a host trips the threshold, so a pinned app doesn't get its connection wedged on every
 *    single attempt forever -- see [PinningFailureRegistry]'s kdoc.
 */
object ConnectControl {
    /**
     * What to do with one CONNECT (or SOCKS/transparent) tunnel request. S4 task 26/27: produced
     * by [com.example.interception.rules.stages.AccessControlStage] from a [RuleDecision]; the
     * legacy `RuleAction`-driven mapping is gone.
     */
    sealed class Disposition {
        /** Relay the tunnel opaquely (no decryption). The default when nothing constrains the flow. */
        data object AllowOpaque : Disposition()

        /** Terminate TLS and inspect (FORCE_DECRYPT). */
        data object ForceMitm : Disposition()

        /** Refuse the tunnel (BLOCK, or an ALLOW_ONLY allow-list this host is not on). */
        data class Block(val reason: String? = null) : Disposition()

        /** Re-point the opaque tunnel at `host:port` (legacy CONNECT REDIRECT parity). */
        data class Redirect(val target: String) : Disposition()

        /**
         * S4 task 27: park the flow -- no relay permit, no bytes either way -- until [timeoutMs]
         * elapses, then close it. Only this flow stalls; the client sees a hung connection.
         */
        data class Suspend(val timeoutMs: Long, val ruleName: String) : Disposition()

        /**
         * S4 task 28: MIRROR host A → B. Dial [targetHost]:[targetPort] instead of the CONNECT
         * authority; the Host header / SNI / issued-cert CN follow the three [NamePolicy]s.
         */
        data class Mirror(
            val targetHost: String,
            val targetPort: Int,
            val hostPolicy: NamePolicy,
            val sniPolicy: NamePolicy,
            val cnPolicy: NamePolicy,
            val ruleName: String,
        ) : Disposition() {
            val target: String get() = "$targetHost:$targetPort"
        }
    }

    /** The pipeline decision and its CONNECT-level translation, for callers that need both. */
    class Verdict(val decision: RuleDecision, val disposition: Disposition, val trail: FlowAuditTrail)

    /**
     * Evaluates the current [RuleSnapshotStore] snapshot for a CONNECT to [host]:[port]. Zero
     * database access: the store keeps the compiled snapshot hot (S4 task 25).
     */
    fun evaluate(
        host: String,
        port: Int,
        clientUid: Int? = null,
        sourceIp: String? = null,
        appPackage: String? = null,
        snapshot: RuleSnapshot = RuleSnapshotStore.current,
    ): Disposition = decide(host, port, clientUid, sourceIp, appPackage, snapshot).disposition

    fun decide(
        host: String,
        port: Int,
        clientUid: Int? = null,
        sourceIp: String? = null,
        appPackage: String? = null,
        snapshot: RuleSnapshot = RuleSnapshotStore.current,
    ): Verdict {
        val decision = RulePipeline.evaluateConnect(snapshot, host, port, clientUid, appPackage, sourceIp)
        val trail = FlowAuditTrail()
        trail.recordDecision(decision, FlowAuditTrail.Phase.CONNECT)
        val disposition = AccessControlStage.disposition(decision, host, port, trail)
        return Verdict(decision, disposition, trail)
    }

    fun resolveClientUid(client: Socket): Int? {
        val local = client.localSocketAddress as? InetSocketAddress ?: return null
        val remote = client.remoteSocketAddress as? InetSocketAddress ?: return null
        // The socket's own view is (local=us, remote=peer); the actual TCP connection's row in
        // /proc/net/tcp is symmetric so either endpoint's (local, remote) pair matches a row,
        // but the row belongs to whichever endpoint's *process* owns the fd -- for an accepted
        // server-side socket, that's the peer's connect()-side view, i.e. this socket's local
        // and remote swapped. Try both orderings so this works regardless of which side of the
        // accept() [client] represents.
        return ProcNetUidResolver.resolve(local, remote) ?: ProcNetUidResolver.resolve(remote, local)
    }

    /**
     * Automatic per-host bypass policy for hosts that keep failing what looks like certificate
     * pinning: repeated TLS/handshake failures against the same host in a short window are a
     * strong signal that the client app pins certs and will never accept requestr's MITM leaf,
     * so retrying [Disposition.ForceMitm] forever just wedges every connection attempt to that
     * host. [MitmProxyEngine] (or any other CONNECT call site) should call [recordFailure] when
     * it observes a handshake failure right after issuing a per-host leaf -- this object does not
     * itself inspect TLS traffic, it only tracks counts/timestamps and exposes the policy
     * decision via [shouldBypass] (consulted automatically by [ConnectControl.evaluate]).
     *
     * Thread-safe: [recordFailure]/[shouldBypass] may be called from any proxy-engine thread.
     */
    object PinningFailureRegistry {
        /** Failures within [WINDOW_MS] of each other at/above this count trips the bypass. */
        const val DEFAULT_THRESHOLD = 3
        const val DEFAULT_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
        const val DEFAULT_BYPASS_DURATION_MS = 60 * 60 * 1000L // 1 hour

        private data class HostState(
            val failureTimestamps: MutableList<Long>,
            var bypassUntil: Long? = null,
            var totalFailures: Int = 0,
            var lastFailureAt: Long? = null,
            var lastReason: String? = null,
        )

        /**
         * Read-only per-host history for [com.example.interception.tls.TrustDiagnostics] (S1
         * task 13). [failureCount] is the lifetime count since the last [clear], not just the
         * trailing window that drives [shouldBypass].
         */
        data class HostHistory(
            val host: String,
            val failureCount: Int,
            val lastFailureAt: Long?,
            val lastReason: String?,
            val bypassedUntil: Long?,
        )

        private val hosts = java.util.concurrent.ConcurrentHashMap<String, HostState>()

        /**
         * Records a pinning-shaped failure for [host] at [at]. Once at least [threshold] failures
         * have landed within the trailing [windowMs], [host] becomes bypassed (see [shouldBypass])
         * for [bypassDurationMs]. Returns true iff this call is what tripped the bypass (i.e. the
         * threshold was just reached, not already-tripped).
         */
        fun recordFailure(
            host: String,
            at: Long = System.currentTimeMillis(),
            threshold: Int = DEFAULT_THRESHOLD,
            windowMs: Long = DEFAULT_WINDOW_MS,
            bypassDurationMs: Long = DEFAULT_BYPASS_DURATION_MS,
            reason: String? = null,
        ): Boolean {
            val key = host.lowercase()
            val state = hosts.computeIfAbsent(key) { HostState(mutableListOf()) }
            synchronized(state) {
                state.failureTimestamps.add(at)
                state.totalFailures++
                state.lastFailureAt = at
                if (reason != null) state.lastReason = reason
                state.failureTimestamps.removeAll { at - it > windowMs }
                val alreadyBypassed = state.bypassUntil?.let { at < it } == true
                if (!alreadyBypassed && state.failureTimestamps.size >= threshold) {
                    state.bypassUntil = at + bypassDurationMs
                    return true
                }
                return false
            }
        }

        /** True while [host] is within an active bypass window tripped by [recordFailure]. */
        fun shouldBypass(host: String, now: Long = System.currentTimeMillis()): Boolean {
            val state = hosts[host.lowercase()] ?: return false
            val until = state.bypassUntil ?: return false
            if (now >= until) {
                synchronized(state) { if (state.bypassUntil == until) state.bypassUntil = null }
                return false
            }
            return true
        }

        /** Snapshot of every tracked host, for diagnostics. Never mutates state. */
        fun snapshot(): List<HostHistory> = hosts.entries.map { (host, state) ->
            synchronized(state) {
                HostHistory(host, state.totalFailures, state.lastFailureAt, state.lastReason, state.bypassUntil)
            }
        }

        /** Clears all tracked state for [host] -- e.g. once the user fixes/removes the app. */
        fun clear(host: String) {
            hosts.remove(host.lowercase())
        }

        /** Clears every host's state. Test seam / "reset interception state" support. */
        fun clearAll() {
            hosts.clear()
        }
    }
}
