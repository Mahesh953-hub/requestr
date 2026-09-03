package com.example.data.models

import com.example.interception.relay.RelayMetrics
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
 *
 * Item 3: [icmpChecksumFailureCount] and [icmpUnrelayableIcmpv6Count] are the same kind of honest
 * partition, but of [IcmpRelay.unrelayableCount] rather than [TunRelay.RelayStats] -- see the
 * counter doc on [IcmpRelay] for exactly which call sites increment each one.
 */
sealed class RelayHealthState {
    abstract val pumpRunning: Boolean
    abstract val stats: TunRelay.RelayStats
    abstract val icmpUnrelayableCount: Int
    abstract val icmpLastPathUsed: IcmpRelay.LastPathUsed
    abstract val icmpChecksumFailureCount: Int
    abstract val icmpUnrelayableIcmpv6Count: Int
    /**
     * S1 task 7: bounded relay-pool counters (active / queued / rejected / evicted) for the
     * gateway sessions. Null when no pool has admitted anything yet.
     */
    abstract val relayPool: RelayMetrics.Snapshot?

    /** Relay is up and no drop/error counters are non-zero. */
    data class Healthy(
        override val pumpRunning: Boolean,
        override val stats: TunRelay.RelayStats,
        override val icmpUnrelayableCount: Int,
        override val icmpLastPathUsed: IcmpRelay.LastPathUsed,
        override val icmpChecksumFailureCount: Int = 0,
        override val icmpUnrelayableIcmpv6Count: Int = 0,
        override val relayPool: RelayMetrics.Snapshot? = null,
    ) : RelayHealthState()

    /** Relay is up but at least one drop/error counter is non-zero (real packet loss). */
    data class Degraded(
        override val pumpRunning: Boolean,
        override val stats: TunRelay.RelayStats,
        override val icmpUnrelayableCount: Int,
        override val icmpLastPathUsed: IcmpRelay.LastPathUsed,
        override val icmpChecksumFailureCount: Int = 0,
        override val icmpUnrelayableIcmpv6Count: Int = 0,
        override val relayPool: RelayMetrics.Snapshot? = null,
    ) : RelayHealthState()

    /** No PacketPump instance — nothing is capturing, so nothing is being relayed. */
    data class Down(
        override val pumpRunning: Boolean,
        override val stats: TunRelay.RelayStats,
        override val icmpUnrelayableCount: Int,
        override val icmpLastPathUsed: IcmpRelay.LastPathUsed,
        override val icmpChecksumFailureCount: Int = 0,
        override val icmpUnrelayableIcmpv6Count: Int = 0,
        override val relayPool: RelayMetrics.Snapshot? = null,
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
    RelayDropReason("TCP upstream write failed — connection reset", dropTcpUpstreamWriteFail).takeIf { it.count > 0 },
    RelayDropReason("No route to upstream host", dropUpstreamOpen).takeIf { it.count > 0 },
    RelayDropReason("VPN socket protection failed", dropProtectFail).takeIf { it.count > 0 },
    RelayDropReason("Unrelayable protocol / ICMP", dropUnrelayableProto).takeIf { it.count > 0 },
)

/**
 * Item 3: the same honest breakdown as [TunRelay.RelayStats.dropReasons], extended with the two
 * [com.example.interception.tun.IcmpRelay]-level reasons this state carries
 * ([RelayHealthState.icmpChecksumFailureCount], [RelayHealthState.icmpUnrelayableIcmpv6Count]).
 * A separate function (rather than changing [TunRelay.RelayStats.dropReasons] itself) because the
 * ICMP counters live on [IcmpRelay], a different class with its own independent counters -- not a
 * field of [TunRelay.RelayStats] -- so only the full [RelayHealthState] has enough information to
 * list them together. Zero-count reasons are omitted, exactly like the [TunRelay.RelayStats]
 * version.
 */
fun RelayHealthState.dropReasons(): List<RelayDropReason> = stats.dropReasons() + listOfNotNull(
    RelayDropReason("ICMP echo checksum invalid", icmpChecksumFailureCount).takeIf { it.count > 0 },
    RelayDropReason("ICMPv6 not relayable", icmpUnrelayableIcmpv6Count).takeIf { it.count > 0 },
) + relayPoolDropReasons()

/** S1 task 7: relay-pool refusals and evictions, labelled by [RelayPool.RejectReason]. */
fun RelayHealthState.relayPoolDropReasons(): List<RelayDropReason> {
    val pool = relayPool ?: return emptyList()
    return listOfNotNull(
        pool.rejectReasons[com.example.interception.relay.RelayPool.RejectReason.QUEUE_FULL]?.let {
            RelayDropReason("Relay pool wait queue full", it.toInt()).takeIf { r -> r.count > 0 }
        },
        pool.rejectReasons[com.example.interception.relay.RelayPool.RejectReason.QUEUE_TIMEOUT]?.let {
            RelayDropReason("Relay pool wait timed out", it.toInt()).takeIf { r -> r.count > 0 }
        },
        RelayDropReason("Relay leg stalled past drain deadline (force-closed)", pool.evicted.toInt()).takeIf { it.count > 0 },
    )
}

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
    icmpChecksumFailureCount: Int = 0,
    icmpUnrelayableIcmpv6Count: Int = 0,
    relayPool: RelayMetrics.Snapshot? = null,
): RelayHealthState {
    if (!pumpRunning) {
        return RelayHealthState.Down(pumpRunning, stats, icmpUnrelayableCount, icmpLastPathUsed, icmpChecksumFailureCount, icmpUnrelayableIcmpv6Count, relayPool)
    }
    // A pool that is rejecting or evicting connections is a degraded relay even if the tun
    // counters are clean: those are connections the user asked for that did not get through.
    val poolPressure = relayPool != null && (relayPool.rejected > 0 || relayPool.evicted > 0)
    val hasIssues = stats.relayErrors > 0 || stats.unrelayableCount > 0 || icmpUnrelayableCount > 0 || poolPressure
    return if (hasIssues) {
        RelayHealthState.Degraded(pumpRunning, stats, icmpUnrelayableCount, icmpLastPathUsed, icmpChecksumFailureCount, icmpUnrelayableIcmpv6Count, relayPool)
    } else {
        RelayHealthState.Healthy(pumpRunning, stats, icmpUnrelayableCount, icmpLastPathUsed, icmpChecksumFailureCount, icmpUnrelayableIcmpv6Count, relayPool)
    }
}
