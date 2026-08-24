package com.example.data.models

import com.example.interception.tun.IcmpRelay
import com.example.interception.tun.TunRelay

/**
 * Task 14 / Lane D: UI-facing relay-health state derived from the real
 * [com.example.interception.tun.TunRelay.RelayStats] counters.
 *
 * Sealed so the compact card ([com.example.ui.components.RelayHealthCard]) and detail sheet
 * ([com.example.ui.components.RelayHealthDetailSheet]) can switch exhaustively over the three
 * approved mockup states — healthy / degraded / down — without a nullable "kind" field, and so a
 * future state can never be silently missed. The three subclasses carry the same measured
 * counters; only the *interpretation* (status label, colour, warning note, CTA) differs, which is
 * exactly what the mockups vary across their compact/expanded × healthy/degraded/down grid.
 *
 * No field here is inferred: [stats] is the live StateFlow value, [pumpRunning] is true only when a
 * PacketPump instance exists, and [icmpUnrelayableCount]/[icmpLastPathUsed] come straight from the
 * live IcmpRelay. The per-reason drop breakdown ([dropReasons]) is an honest partition of
 * [TunRelay.RelayStats.relayErrors] + [TunRelay.RelayStats.unrelayableCount], never a separate
 * invented stream (see the counter doc on RelayStats).
 */
sealed class RelayHealthState {
    abstract val pumpRunning: Boolean
    abstract val stats: TunRelay.RelayStats
    abstract val icmpUnrelayableCount: Int
    abstract val icmpLastPathUsed: IcmpRelay.LastPathUsed

    /** Relay is up and no drop/error counters are non-zero. */
    data class Healthy(
        override val pumpRunning: Boolean,
        override val stats: TunRelay.RelayStats,
        override val icmpUnrelayableCount: Int,
        override val icmpLastPathUsed: IcmpRelay.LastPathUsed,
    ) : RelayHealthState()

    /** Relay is up but at least one drop/error counter is non-zero (real packet loss). */
    data class Degraded(
        override val pumpRunning: Boolean,
        override val stats: TunRelay.RelayStats,
        override val icmpUnrelayableCount: Int,
        override val icmpLastPathUsed: IcmpRelay.LastPathUsed,
    ) : RelayHealthState()

    /** No PacketPump instance — nothing is capturing, so nothing is being relayed. */
    data class Down(
        override val pumpRunning: Boolean,
        override val stats: TunRelay.RelayStats,
        override val icmpUnrelayableCount: Int,
        override val icmpLastPathUsed: IcmpRelay.LastPathUsed,
    ) : RelayHealthState()
}

/** One labelled bucket of dropped packets, shown as a row + thin bar in the detail sheet. */
data class RelayDropReason(val label: String, val count: Int)

/** Total packets dropped across every reason (aggregate of [TunRelay.RelayStats.relayErrors] + unrelayable). */
fun TunRelay.RelayStats.totalDropped(): Int = relayErrors + unrelayableCount

/**
 * The honest per-reason drop breakdown, in the mockup's display order. Reasons whose real counter
 * is zero are omitted rather than shown as fabricated zeroes — the only absent mockup category
 * (tun-MTU over-length) has no drop site in this relay, so it is intentionally not listed.
 */
fun TunRelay.RelayStats.dropReasons(): List<RelayDropReason> = listOfNotNull(
    RelayDropReason("Flow table at cap — entry evicted", evictionCount).takeIf { it.count > 0 },
    RelayDropReason("Upstream socket not writable", dropUpstreamWrite).takeIf { it.count > 0 },
    RelayDropReason("No route to upstream host", dropUpstreamOpen).takeIf { it.count > 0 },
    RelayDropReason("VPN socket protection failed", dropProtectFail).takeIf { it.count > 0 },
    RelayDropReason("Unrelayable protocol / ICMP", dropUnrelayableProto).takeIf { it.count > 0 },
)

/** Active flow count across every transport/family — the "N flows" compact-card figure. */
fun TunRelay.RelayStats.activeFlowCount(): Int =
    activeTcpV4 + activeTcpV6 + activeUdpV4 + activeUdpV6

/** Live flow-table occupancy against the [TunRelay.MAX_FLOWS] cap (e.g. "52 / 512"). */
fun TunRelay.RelayStats.flowTablePressure(): Int = activeFlowCount()

/**
 * Maps the live counters into the three-state [RelayHealthState]. Mirrors the existing
 * healthy/degraded/down classification the compact card already used (errors or unrelayable packets
 * ⇒ degraded; no pump ⇒ down) so this extraction does not change which pill a real capture shows.
 */
fun toRelayHealthState(
    pumpRunning: Boolean,
    stats: TunRelay.RelayStats,
    icmpUnrelayableCount: Int,
    icmpLastPathUsed: IcmpRelay.LastPathUsed,
): RelayHealthState {
    if (!pumpRunning) {
        return RelayHealthState.Down(pumpRunning, stats, icmpUnrelayableCount, icmpLastPathUsed)
    }
    val hasIssues = stats.relayErrors > 0 || stats.unrelayableCount > 0 || icmpUnrelayableCount > 0
    return if (hasIssues) {
        RelayHealthState.Degraded(pumpRunning, stats, icmpUnrelayableCount, icmpLastPathUsed)
    } else {
        RelayHealthState.Healthy(pumpRunning, stats, icmpUnrelayableCount, icmpLastPathUsed)
    }
}
