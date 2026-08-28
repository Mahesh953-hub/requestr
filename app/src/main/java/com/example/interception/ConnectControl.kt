package com.example.interception

import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
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
     *
     * A [Disposition.ForceMitm] result is automatically downgraded to [Disposition.AllowOpaque]
     * when [PinningFailureRegistry] has already recorded enough recent pinning-shaped failures
     * for [host] to trip its bypass -- see that object's kdoc. BLOCK/REDIRECT/AllowOpaque results
     * are never touched by the registry: bypassing MITM is only meaningful when MITM was the
     * thing about to be forced.
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
            RuleAction.FORCE_MITM ->
                if (PinningFailureRegistry.shouldBypass(host)) Disposition.AllowOpaque else Disposition.ForceMitm
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

    /**
     * Non-root UID attribution for [client], a CONNECT's already-accepted local [Socket].
     *
     * Delegates to [ProcNetUidResolver], which reads the `uid` column `/proc/net/tcp`/`tcp6`
     * already stamp on every row -- no `su`, no walking `/proc/\<pid\>/fd` for an inode, and no
     * API-level floor (unlike `ConnectivityManager.getConnectionOwnerUid`, which is API 29+ and
     * only resolves while requestr itself is the active VPN). The tradeoff is the SELinux
     * per-app socket-visibility restriction Android has applied to `/proc/net/tcp*` since Nougat:
     * on a stock, unrooted, N+ device a non-VPN, non-system app generally can only see its own
     * sockets there, so this will legitimately return null for another app's connection in that
     * configuration -- same as the existing `ConnectivityManager` path returning null when it
     * doesn't apply. Never throws.
     */
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

        private data class HostState(val failureTimestamps: MutableList<Long>, var bypassUntil: Long? = null)

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
        ): Boolean {
            val key = host.lowercase()
            val state = hosts.computeIfAbsent(key) { HostState(mutableListOf()) }
            synchronized(state) {
                state.failureTimestamps.add(at)
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
