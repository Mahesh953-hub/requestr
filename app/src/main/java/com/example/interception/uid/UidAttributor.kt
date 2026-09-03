package com.example.interception.uid

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import com.example.interception.ProcNetUidResolver
import com.example.interception.bridge.BridgeEvent
import com.example.interception.bridge.BridgeEventListener
import com.example.interception.gateway.local.ClientOrigin
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * S1 task 10: one UID attribution path for every flow, with an explicit "we do not know" answer.
 *
 * Resolution order (first hit wins), each step never throwing:
 *
 * 1. **Bridge `+info` source tuple.** In native-bridge mode the vendored tun2proxy authenticates
 *    to the local gateway with `user|tcp|src_ip|src_port`, and its `session_start` event carries
 *    the destination the app *actually* connected to (a virtual-DNS address, not the real one).
 *    [onBridgeEvent] keeps a bounded in-memory `src -> dst` table so the real 4-tuple can be
 *    handed to `ConnectivityManager.getConnectionOwnerUid` (API 29+) and to `/proc/net/tcp*`.
 * 2. **Connection owner of the accepted socket** (API 29+): the peer of the gateway's accepted
 *    socket is the app itself when it talks to the proxy directly (PROXY tier). Skipped for
 *    bridge flows, whose peer is the in-process tun2proxy (it would resolve to requestr itself).
 * 3. **`/proc/net/tcp*`** via [ProcNetUidResolver] for the accepted socket's tuple, both
 *    orderings (pre-29, or where the platform still exposes it). Same bridge-flow exclusion.
 * 4. **Capture-selection inference:** when exactly one package is in the capture selection, its
 *    UID is the only candidate. Marked as an inference, never as a measurement.
 * 5. Otherwise [Attribution.Unattributed] with the reasons every step gave, so the UI can say
 *    "unattributed: no bridge origin; owner lookup returned -1; /proc/net unreadable" instead of
 *    guessing or showing a blank.
 *
 * Nothing here touches Room (the schema ledger reserves v19+ for S2); the bridge table is memory
 * only and bounded to [maxTrackedSessions] entries.
 */
class UidAttributor(
    /** `(protocol, local, remote) -> uid or null`. Default: `getConnectionOwnerUid` on API 29+. */
    private val ownerUidLookup: (Int, InetSocketAddress, InetSocketAddress) -> Int? = { _, _, _ -> null },
    /** `(local, remote) -> uid or null`. Default: [ProcNetUidResolver.resolve]. */
    private val procNetLookup: (InetSocketAddress, InetSocketAddress) -> Int? = { l, r -> ProcNetUidResolver.resolve(l, r) },
    /** Package names currently in the capture selection (empty = not scoped / unknown). */
    private val captureSelection: () -> Set<String> = { emptySet() },
    /** `packageName -> uid or null`; default cannot resolve (needs a PackageManager). */
    private val packageUidLookup: (String) -> Int? = { null },
    private val maxTrackedSessions: Int = 4096,
    private val ownerLookupSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
) : BridgeEventListener {

    /** Which step produced a [Attribution.Resolved] UID. */
    enum class Source { BRIDGE_INFO, CONNECTION_OWNER, PROC_NET, SELECTION_INFERENCE }

    sealed class Attribution {
        abstract val uidOrNull: Int?

        data class Resolved(val uid: Int, val source: Source) : Attribution() {
            override val uidOrNull: Int get() = uid
            /** True when the UID is inferred from the selection rather than observed from the kernel. */
            val isInferred: Boolean get() = source == Source.SELECTION_INFERENCE
        }

        data class Unattributed(val reasons: List<String>) : Attribution() {
            override val uidOrNull: Int? get() = null
            val reason: String get() = reasons.joinToString("; ")
        }
    }

    /** What is known about the flow at attribution time. Every field is optional. */
    class Flow(
        /** `+info` source tuple from the bridge, or null for non-bridge entries. */
        val origin: ClientOrigin? = null,
        /** The gateway's accepted socket (peer = whoever dialed us). */
        val clientSocket: Socket? = null,
        /** Destination as the gateway saw it (may be a virtual-DNS address or a hostname). */
        val destination: InetSocketAddress? = null,
        val isTcp: Boolean = true,
    )

    private data class Tracked(val sessionId: Long, val dst: InetSocketAddress)

    private val lock = Any()
    private val bySource = object : LinkedHashMap<InetSocketAddress, Tracked>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InetSocketAddress, Tracked>): Boolean =
            size > maxTrackedSessions
    }
    private val sessionToSource = HashMap<Long, InetSocketAddress>()

    /** Number of bridge sessions currently tracked (diagnostics / tests). */
    val trackedSessions: Int get() = synchronized(lock) { bySource.size }

    /** Feed of `session_start` / `session_end` events keeps the `src -> dst` table current. */
    override fun onBridgeEvent(event: BridgeEvent) {
        when (event) {
            is BridgeEvent.SessionStart -> {
                val src = parseSocketAddress(event.src) ?: return
                val dst = parseSocketAddress(event.dst) ?: return
                synchronized(lock) {
                    bySource[src] = Tracked(event.sessionId, dst)
                    sessionToSource[event.sessionId] = src
                }
            }
            is BridgeEvent.SessionEnd -> synchronized(lock) {
                val src = sessionToSource.remove(event.sessionId) ?: return
                val tracked = bySource[src]
                if (tracked != null && tracked.sessionId == event.sessionId) bySource.remove(src)
            }
            else -> Unit
        }
    }

    /** Destination the bridge reported for [origin], if its session is still tracked. */
    fun trackedDestination(origin: ClientOrigin): InetSocketAddress? {
        val src = toSocketAddress(origin) ?: return null
        return synchronized(lock) { bySource[src]?.dst }
    }

    fun attribute(flow: Flow): Attribution {
        val reasons = ArrayList<String>(4)
        val protocol = if (flow.isTcp) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP

        // 1. Bridge +info source tuple.
        val origin = flow.origin
        if (origin == null) {
            reasons += "no bridge origin"
        } else {
            val src = toSocketAddress(origin)
            val dst = trackedDestination(origin)
                ?: flow.destination?.takeIf { !it.isUnresolved && it.address != null }
            if (src == null) {
                reasons += "bridge origin ${origin.ip}:${origin.port} unparseable"
            } else if (dst == null) {
                reasons += "bridge origin known but destination untracked"
            } else {
                val uid = safe { if (ownerLookupSupported) ownerUidLookup(protocol, src, dst) else null }
                    ?: safe { procNetLookup(src, dst) }
                if (uid != null && uid >= 0) return Attribution.Resolved(uid, Source.BRIDGE_INFO)
                reasons += "bridge tuple ${src.hostString}:${src.port}->${dst.hostString}:${dst.port} has no owner"
            }
        }

        // 2./3. The accepted socket's own tuple. Never for bridge flows: there the peer of the
        // accepted socket is the in-process tun2proxy, so the kernel would (correctly) answer with
        // requestr's own uid -- a confident, wrong attribution.
        val client = flow.clientSocket
        val local = client?.localSocketAddress as? InetSocketAddress
        val remote = client?.remoteSocketAddress as? InetSocketAddress
        if (origin != null) {
            reasons += "bridge flow: accepted-socket peer is the in-process bridge, skipped"
        } else if (local == null || remote == null) {
            reasons += "no client socket tuple"
        } else {
            if (!ownerLookupSupported) {
                reasons += "owner lookup needs API 29+"
            } else {
                val uid = safe { ownerUidLookup(protocol, remote, local) } ?: safe { ownerUidLookup(protocol, local, remote) }
                if (uid != null && uid >= 0) return Attribution.Resolved(uid, Source.CONNECTION_OWNER)
                reasons += "owner lookup returned no uid"
            }
            val uid = safe { procNetLookup(local, remote) } ?: safe { procNetLookup(remote, local) }
            if (uid != null && uid >= 0) return Attribution.Resolved(uid, Source.PROC_NET)
            reasons += "/proc/net/tcp has no matching row"
        }

        // 4. Single-app selection inference.
        val selection = safe { captureSelection() } ?: emptySet()
        when (selection.size) {
            0 -> reasons += "capture selection empty"
            1 -> {
                val pkg = selection.first()
                val uid = safe { packageUidLookup(pkg) }
                if (uid != null && uid >= 0) return Attribution.Resolved(uid, Source.SELECTION_INFERENCE)
                reasons += "selected package $pkg has no resolvable uid"
            }
            else -> reasons += "${selection.size} apps in capture selection"
        }
        return Attribution.Unattributed(reasons)
    }

    private inline fun <T> safe(block: () -> T?): T? = try { block() } catch (_: Throwable) { null }

    companion object {
        /** Production attributor wired to the platform (`ConnectivityManager`, `PackageManager`). */
        fun platform(
            context: Context,
            captureSelection: () -> Set<String> = { captureSelectionSnapshot },
        ): UidAttributor {
            val app = context.applicationContext
            return UidAttributor(
                ownerUidLookup = { protocol, local, remote ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
                    else (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                        ?.getConnectionOwnerUid(protocol, local, remote)
                        ?.takeIf { it != -1 }
                },
                captureSelection = captureSelection,
                packageUidLookup = { pkg -> runCatching { app.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() },
            )
        }

        /**
         * Packages currently routed through the tun (the VPN allow-list), published by the VPN
         * service so the shared attributor can apply the single-app inference step. Empty when
         * capture is not scoped to specific apps.
         */
        @Volatile var captureSelectionSnapshot: Set<String> = emptySet()

        @Volatile private var shared: UidAttributor? = null

        /** Process-wide attributor (the bridge listener and the engines must share one table). */
        fun shared(context: Context): UidAttributor =
            shared ?: synchronized(this) { shared ?: platform(context).also { shared = it } }

        internal fun resetSharedForTest() { shared = null }

        fun toSocketAddress(origin: ClientOrigin): InetSocketAddress? =
            runCatching { InetSocketAddress(InetAddress.getByName(origin.ip.trim().removePrefix("[").removeSuffix("]")), origin.port) }
                .getOrNull()?.takeIf { it.port in 1..65535 }

        /** Parses `ip:port` or `[v6]:port` as written by the bridge; null when malformed. */
        fun parseSocketAddress(text: String): InetSocketAddress? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val host: String
            val portText: String
            if (t.startsWith("[")) {
                val end = t.indexOf(']')
                if (end < 0 || end + 1 >= t.length || t[end + 1] != ':') return null
                host = t.substring(1, end)
                portText = t.substring(end + 2)
            } else {
                val colon = t.lastIndexOf(':')
                if (colon <= 0 || t.indexOf(':') != colon) return null // bare v6 without brackets is ambiguous
                host = t.substring(0, colon)
                portText = t.substring(colon + 1)
            }
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val literal = if (host.contains(':')) {
                host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }
            } else {
                host.isNotEmpty() && host.all { it.isDigit() || it == '.' } && host.count { it == '.' } == 3
            }
            if (!literal) return null // never DNS-resolve a name here
            return runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
        }
    }
}
