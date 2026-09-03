package com.example.interception

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S1 task 11: the pure, `android.net`-free half of the VPN service's network-handoff logic so it
 * can be unit-tested with virtual time (a real `ConnectivityManager.NetworkCallback` cannot be
 * driven from a JVM test).
 *
 * [RequestrVpnService] translates `onAvailable` / `onLinkPropertiesChanged` /
 * `onCapabilitiesChanged` / `onLost` into [onNetworkChanged] / [onNetworkLost] with a
 * [LinkFingerprint] summarising what the tun actually depends on (resolver set and IP families).
 * The coordinator then decides between three outcomes:
 *
 * - **Re-bind only.** The underlying network changed (Wi-Fi to LTE) but the resolvers and IP
 *   families are the same: `setUnderlyingNetworks` is enough, the tun stays up, no flow is cut.
 * - **Debounced re-establish.** The fingerprint changed (different resolvers, IPv6 appeared or
 *   disappeared). Several callbacks fire in quick succession during a handover, so exactly one
 *   tear-down/re-establish runs after [debounceMs] of quiet -- through the same path
 *   `filterObserverJob` already uses -- instead of one per callback.
 * - **Degraded, not stopped.** `onLost` with no replacement keeps the tun up and reports
 *   `degraded = true`; the next `onAvailable` clears it. Stopping capture here would throw away
 *   the session the user is watching every time a train goes through a tunnel.
 */
class VpnNetworkHandoff(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onUnderlyingNetwork: (network: Any) -> Unit,
    private val onReestablish: suspend (reason: String) -> Unit,
    private val onDegraded: (degraded: Boolean) -> Unit = {},
) {
    /** What the established tun depends on; a change here requires a re-establish. */
    data class LinkFingerprint(
        val dnsServers: List<String>,
        val hasIpv4: Boolean,
        val hasIpv6: Boolean,
    ) {
        companion object {
            fun of(dnsServers: List<InetAddress>, addresses: List<InetAddress>): LinkFingerprint =
                LinkFingerprint(
                    dnsServers = dnsServers.mapNotNull { it.hostAddress }.sorted(),
                    hasIpv4 = addresses.any { it is Inet4Address },
                    hasIpv6 = addresses.any { it is Inet6Address && !it.isLinkLocalAddress },
                )
        }
    }

    private val lock = Any()
    private var established: LinkFingerprint? = null
    private var current: Any? = null
    private var pending: Job? = null
    private var pendingFingerprint: LinkFingerprint? = null
    private var degraded = false

    /** Number of re-establishes actually executed (diagnostics / tests). */
    @Volatile var reestablishCount: Long = 0
        private set

    /** Number of underlying-network re-binds performed without a re-establish. */
    @Volatile var rebindCount: Long = 0
        private set

    val isDegraded: Boolean get() = synchronized(lock) { degraded }

    /** Called once the tun is established so later callbacks have a baseline to compare with. */
    fun onEstablished(fingerprint: LinkFingerprint, network: Any?) {
        synchronized(lock) {
            established = fingerprint
            current = network
            pending?.cancel()
            pending = null
            pendingFingerprint = null
            if (degraded) {
                degraded = false
                onDegraded(false)
            }
        }
    }

    /** A network is available / its properties changed. [fingerprint] is null when unknown. */
    fun onNetworkChanged(network: Any, fingerprint: LinkFingerprint?) {
        synchronized(lock) {
            if (degraded) {
                degraded = false
                onDegraded(false)
            }
            if (current != network) {
                current = network
                rebindCount++
                onUnderlyingNetwork(network)
            }
            val baseline = established ?: return
            if (fingerprint == null || fingerprint == baseline) {
                // Same dependencies: the rebind above is all the handover needs. Also cancels
                // a pending re-establish scheduled by a transient intermediate fingerprint.
                pending?.cancel()
                pending = null
                pendingFingerprint = null
                return
            }
            // Restart the quiet window on every callback: a handover typically fires
            // onAvailable, onCapabilitiesChanged and onLinkPropertiesChanged within a few
            // hundred milliseconds, and exactly one re-establish should follow the last one.
            pending?.cancel()
            pendingFingerprint = fingerprint
            pending = scope.launch {
                delay(debounceMs)
                val reason = synchronized(lock) {
                    if (pendingFingerprint != fingerprint) return@launch
                    pending = null
                    pendingFingerprint = null
                    reestablishCount++
                    describeChange(established, fingerprint)
                }
                onReestablish(reason)
            }
        }
    }

    /** A network went away. Only degrades when it was the one we were bound to. */
    fun onNetworkLost(network: Any) {
        synchronized(lock) {
            if (current != null && current != network) return
            current = null
            pending?.cancel()
            pending = null
            pendingFingerprint = null
            if (!degraded) {
                degraded = true
                onDegraded(true)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
            pendingFingerprint = null
            established = null
            current = null
        }
    }

    private fun describeChange(from: LinkFingerprint?, to: LinkFingerprint): String {
        val parts = mutableListOf<String>()
        if (from?.dnsServers != to.dnsServers) parts += "resolvers ${from?.dnsServers} -> ${to.dnsServers}"
        if (from?.hasIpv4 != to.hasIpv4) parts += "ipv4 ${from?.hasIpv4} -> ${to.hasIpv4}"
        if (from?.hasIpv6 != to.hasIpv6) parts += "ipv6 ${from?.hasIpv6} -> ${to.hasIpv6}"
        return "Network handover changed " + parts.joinToString(", ").ifEmpty { "link properties" }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 750L
    }
}

/**
 * S1 task 11: which resolvers the tun advertises to apps.
 *
 * - **Virtual DNS** (native bridge available): a single resolver *inside* the virtual-DNS pool
 *   the bridge owns ([VIRTUAL_DNS_RESOLVER] in `Tun2ProxyConfig.VIRTUAL_DNS_POOL`). Every
 *   query then terminates in tun2proxy's virtual resolver, which is what yields hostnames for
 *   `SessionStart` events, DNS rows and SNI-less attribution. The public resolvers the tun used
 *   to advertise were reachable only by leaking real DNS out of the capture.
 * - **Legacy relay path** (no native bridge): the active network's own resolvers first, so DNS
 *   keeps working on captive/enterprise networks that block public resolvers, with
 *   [PUBLIC_FALLBACK_RESOLVERS] only when the system reports none.
 *
 * Pure and `android.net`-free so it is unit-testable; the service passes `LinkProperties.dnsServers`.
 */
object VpnDnsPlan {
    /** First usable host inside `Tun2ProxyConfig.VIRTUAL_DNS_POOL` (198.18.0.0/15). */
    const val VIRTUAL_DNS_RESOLVER = "198.18.0.1"

    val PUBLIC_FALLBACK_RESOLVERS: List<String> = listOf("1.1.1.1", "8.8.8.8")

    fun resolvers(virtualDns: Boolean, systemResolvers: List<String>, ipv6Enabled: Boolean = true): List<String> {
        if (virtualDns) return listOf(VIRTUAL_DNS_RESOLVER)
        val usable = systemResolvers
            .map { it.trim().removePrefix("/") }
            .filter { it.isNotEmpty() && (ipv6Enabled || !it.contains(':')) }
            .distinct()
        return if (usable.isEmpty()) PUBLIC_FALLBACK_RESOLVERS else usable
    }
}
