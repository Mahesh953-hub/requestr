package com.example.interception.quic

import android.util.Log
import com.example.interception.gateway.UdpAssociationHandler
import com.example.interception.gateway.local.ClientOrigin
import com.example.interception.gateway.local.Destination
import com.example.interception.gateway.local.Socks5Handler
import com.example.interception.net.SocketProtector
import com.example.interception.uid.UidAttributor
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S1 task 9 (master plan D6): capture-scoped UDP/QUIC policy, decided per datagram from the owning
 * UID that [UidAttributor] resolves.
 *
 * Three outcomes, in this order of precedence:
 *
 * 1. **Out of scope, bypassed, or unattributable** -> [Action.PASS_UNTOUCHED]. The datagram is
 *    forwarded byte-for-byte: no tee, no drop, no decode, no row. This is the safety default that
 *    prevents both accidental breakage and privacy capture of traffic the user never asked to
 *    inspect. "In scope" means the owning package is in the capture selection (every package when
 *    the selection is "all apps") and not on the bypass list.
 * 2. **In scope, default policy** -> [Action.TEE_AND_DROP] for UDP/443. The datagram is teed to the
 *    passive `quic/` decoder (and the PCAPng writer, task 12) and then dropped so the client
 *    deterministically falls back to HTTP/2. The row is **blocked**
 *    ([com.example.data.local.QuicDecodeStatus.BLOCKED_BY_POLICY]) with the reason and whatever
 *    version/CID the decoder observed.
 * 3. **In scope, Observe-QUIC override on** -> [Action.TEE_AND_PASS]. Teed, then forwarded. The
 *    row is **observed** (`OBSERVED_NO_KEYS`) and becomes **decoded** only when the decoder really
 *    has session keys (SDK keylog / Frida tier) -- never otherwise.
 *
 * DNS on UDP/53 is never subject to this policy: under the bridge it is answered by virtual DNS
 * before reaching the gateway, and if a datagram does arrive here it is forwarded untouched.
 * Non-443 UDP in scope is forwarded untouched as well (the policy is about QUIC).
 *
 * **Full QUIC MITM is not implemented.** Chromium never trusts user-added CAs for QUIC regardless
 * of trust-store state, so block-or-observe plus passive decode is the honest ceiling. The
 * diagnostics ledger ([recent]) records which branch every flow took and why.
 *
 * The router also implements the gateway's SOCKS5 `UDP ASSOCIATE` (RFC 1928 §7) relay that the
 * vendored tun2proxy drives for every non-DNS UDP session: one loopback relay socket per
 * association facing the bridge, one [SocketProtector]-protected upstream socket, and the policy
 * applied per datagram on the way out. Flag: `InterceptorFlags.quicTeePolicy` (the VPN service
 * only installs the router when it is on; when off the gateway refuses UDP ASSOCIATE as before).
 */
class UdpPolicyRouter(
    private val attributor: UidAttributor,
    private val scope: () -> CaptureScope,
    private val observeOverride: () -> Boolean,
    /** `uid -> package names` (PackageManager.getPackagesForUid); empty when unknown. */
    private val packagesForUid: (Int) -> List<String>,
    private val tee: DatagramTee? = null,
    /** Protects the upstream socket from looping back into the tun. */
    private val protect: (DatagramSocket) -> SocketProtector.ProtectResult = { SocketProtector.protectDatagramSocket(it) },
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val ledgerCapacity: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Destination ports treated as QUIC (tests add an ephemeral loopback port). */
    private val quicPorts: Set<Int> = setOf(QUIC_PORT),
) : UdpAssociationHandler {

    /** Snapshot of the capture selection the policy is scoped to. */
    data class CaptureScope(
        /** True when capture is not limited to selected packages (everything in the tun is in scope). */
        val allApps: Boolean,
        val selectedPackages: Set<String> = emptySet(),
        val bypassPackages: Set<String> = emptySet(),
    )

    enum class Action { PASS_UNTOUCHED, TEE_AND_DROP, TEE_AND_PASS }

    /** Which rule produced the decision; shown verbatim by diagnostics. */
    enum class Branch { DNS, NON_QUIC, OUT_OF_SCOPE, BYPASSED, UNATTRIBUTED, BLOCKED, OBSERVED }

    data class Decision(
        val action: Action,
        val branch: Branch,
        val reason: String,
        val uid: Int? = null,
        val packageName: String? = null,
        val attributionSource: UidAttributor.Source? = null,
    ) {
        val teed: Boolean get() = action != Action.PASS_UNTOUCHED
        val forwarded: Boolean get() = action != Action.TEE_AND_DROP
    }

    /** Receives copies of in-scope datagrams (outbound and inbound) for passive inspection. */
    fun interface DatagramTee {
        fun onDatagram(
            payload: ByteArray,
            offset: Int,
            length: Int,
            source: InetSocketAddress,
            destination: InetSocketAddress,
            outbound: Boolean,
            decision: Decision,
        )
    }

    /** One diagnostics ledger row per (association, destination). Memory only; no Room. */
    data class Record(
        val origin: ClientOrigin?,
        val destination: String,
        val decision: Decision,
        val firstSeenAt: Long,
        val datagrams: Long,
        val bytes: Long,
    )

    private val ledger = object : LinkedHashMap<String, Record>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Record>): Boolean = size > ledgerCapacity
    }
    private val ledgerLock = Any()
    private val counters = ConcurrentHashMap<Branch, AtomicLong>()

    /** Most recent policy decisions, oldest first (for diagnostics / task 13). */
    fun recent(): List<Record> = synchronized(ledgerLock) { ledger.values.toList() }

    /** Flows per branch since start. */
    fun branchCounts(): Map<Branch, Long> = counters.mapValues { it.value.get() }

    /** Pure decision: no side effects, no I/O. */
    fun decide(destination: InetSocketAddress, attribution: UidAttributor.Attribution): Decision {
        val port = destination.port
        if (port == DNS_PORT) {
            return Decision(Action.PASS_UNTOUCHED, Branch.DNS, "DNS/53 always flows through virtual DNS; never subject to QUIC policy")
        }
        val current = scope()
        val resolved = attribution as? UidAttributor.Attribution.Resolved
        val packages = resolved?.let { packagesForUid(it.uid) }.orEmpty()
        val pkg = packages.firstOrNull()

        if (packages.any { it in current.bypassPackages }) {
            return Decision(
                Action.PASS_UNTOUCHED, Branch.BYPASSED, "package ${packages.first { it in current.bypassPackages }} is on the UDP bypass list",
                resolved?.uid, pkg, resolved?.source,
            )
        }
        if (!current.allApps) {
            if (resolved == null) {
                val why = (attribution as UidAttributor.Attribution.Unattributed).reason
                return Decision(Action.PASS_UNTOUCHED, Branch.UNATTRIBUTED, "owning app unknown ($why); treated as out of scope")
            }
            if (packages.none { it in current.selectedPackages }) {
                return Decision(
                    Action.PASS_UNTOUCHED, Branch.OUT_OF_SCOPE,
                    "uid ${resolved.uid} (${pkg ?: "no package"}) is not in the capture selection",
                    resolved.uid, pkg, resolved.source,
                )
            }
        }
        if (port !in quicPorts) {
            return Decision(Action.PASS_UNTOUCHED, Branch.NON_QUIC, "UDP/$port is not QUIC; forwarded untouched", resolved?.uid, pkg, resolved?.source)
        }
        val who = when {
            pkg != null -> pkg
            resolved != null -> "uid ${resolved.uid}"
            else -> "all apps in scope"
        }
        return if (observeOverride()) {
            Decision(
                Action.TEE_AND_PASS, Branch.OBSERVED,
                "$who in scope, Observe-QUIC on: teed then passed; decoded only if session keys are present",
                resolved?.uid, pkg, resolved?.source,
            )
        } else {
            Decision(
                Action.TEE_AND_DROP, Branch.BLOCKED,
                "$who in scope, default policy: UDP/443 teed then dropped so the client falls back to HTTP/2",
                resolved?.uid, pkg, resolved?.source,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SOCKS5 UDP ASSOCIATE relay
    // ---------------------------------------------------------------------------------------------

    override suspend fun handleUdpAssociate(control: Socket, requested: Destination, origin: ClientOrigin?) {
        withContext(Dispatchers.IO) {
            val out = control.getOutputStream()
            val relay = runCatching { DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }.getOrNull()
            val upstream = runCatching { DatagramSocket(InetSocketAddress(0)) }.getOrNull()
            if (relay == null || upstream == null) {
                relay?.close(); upstream?.close()
                runCatching { out.write(Socks5Handler.reply(Socks5Handler.REP_GENERAL_FAILURE)); out.flush() }
                control.close()
                return@withContext
            }
            // Fail closed when protection is available but refused: an unprotected upstream socket
            // would re-enter the tun and loop. Unavailable (no VPN backing, e.g. PROXY tier or
            // tests) is fine -- there is no tun to loop through.
            if (protect(upstream) == SocketProtector.ProtectResult.Failed) {
                relay.close(); upstream.close()
                runCatching { out.write(Socks5Handler.reply(Socks5Handler.REP_GENERAL_FAILURE)); out.flush() }
                control.close()
                Log.w(TAG, "UDP ASSOCIATE refused: upstream socket could not be protected")
                return@withContext
            }
            try {
                out.write(Socks5Handler.replyBound(relay.localAddress, relay.localPort)); out.flush()
            } catch (e: IOException) {
                relay.close(); upstream.close(); control.close()
                return@withContext
            }
            val association = Association(control, relay, upstream, origin, requested)
            try {
                association.run()
            } finally {
                relay.close(); upstream.close(); runCatching { control.close() }
            }
        }
    }

    private inner class Association(
        private val control: Socket,
        private val relay: DatagramSocket,
        private val upstream: DatagramSocket,
        private val origin: ClientOrigin?,
        private val requested: Destination,
    ) {
        @Volatile private var clientAddress: InetSocketAddress? = null
        @Volatile private var lastActivity = clock()
        @Volatile private var closed = false
        private val decisions = HashMap<InetSocketAddress, Decision>()
        /**
         * Attributed once per association (guarded by [decisions]). The bridge tracks the
         * app-side tuple from `SessionStart`; when it has no row for this origin (bridge
         * events disabled, or a non-virtual-DNS flow) the first datagram's destination stands
         * in so the owner/proc-net lookups still have a full 4-tuple to query.
         */
        private var attribution: UidAttributor.Attribution? = null

        private fun attributionFor(destination: InetSocketAddress): UidAttributor.Attribution =
            attribution ?: attributor.attribute(
                UidAttributor.Flow(origin = origin, clientSocket = control, destination = destination, isTcp = false),
            ).also { attribution = it }
        private val appSource: InetSocketAddress? = origin?.let { UidAttributor.toSocketAddress(it) }

        suspend fun run() = coroutineScope {
            relay.soTimeout = POLL_MS
            upstream.soTimeout = POLL_MS
            val outbound = launch(Dispatchers.IO) { pumpOutbound() }
            val inbound = launch(Dispatchers.IO) { pumpInbound() }
            // The association lives exactly as long as the TCP control connection (RFC 1928 §7).
            launch(Dispatchers.IO) {
                try {
                    control.soTimeout = 0
                    val buf = ByteArray(64)
                    while (!closed && control.getInputStream().read(buf) >= 0) { /* ignore stray bytes */ }
                } catch (_: IOException) {
                } finally {
                    close()
                }
            }
            outbound.join(); inbound.join()
        }

        private fun close() {
            closed = true
            runCatching { relay.close() }
            runCatching { upstream.close() }
            runCatching { control.close() }
        }

        private fun decisionFor(destination: InetSocketAddress): Decision =
            synchronized(decisions) {
                decisions.getOrPut(destination) {
                    val d = decide(destination, attributionFor(destination))
                    counters.computeIfAbsent(d.branch) { AtomicLong() }.incrementAndGet()
                    if (d.branch != Branch.OUT_OF_SCOPE && d.branch != Branch.BYPASSED && d.branch != Branch.UNATTRIBUTED) {
                        record(destination, d, 0, 0)
                    }
                    d
                }
            }

        private fun record(destination: InetSocketAddress, decision: Decision, datagrams: Long, bytes: Long) {
            val key = "${origin?.ip}:${origin?.port}->${destination.hostString}:${destination.port}"
            synchronized(ledgerLock) {
                val prev = ledger[key]
                ledger[key] = Record(
                    origin = origin,
                    destination = "${destination.hostString}:${destination.port}",
                    decision = decision,
                    firstSeenAt = prev?.firstSeenAt ?: clock(),
                    datagrams = (prev?.datagrams ?: 0) + datagrams,
                    bytes = (prev?.bytes ?: 0) + bytes,
                )
            }
        }

        private fun pumpOutbound() {
            val buf = ByteArray(MAX_DATAGRAM)
            val packet = DatagramPacket(buf, buf.size)
            while (!closed) {
                try {
                    packet.setData(buf, 0, buf.size)
                    relay.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (clock() - lastActivity > idleTimeoutMs) { close(); return }
                    continue
                } catch (_: IOException) {
                    return
                }
                lastActivity = clock()
                val from = packet.socketAddress as? InetSocketAddress ?: continue
                if (clientAddress == null) clientAddress = from
                val parsed = parseHeader(buf, packet.length) ?: continue
                val destination = parsed.destination
                val decision = decisionFor(destination)
                val dataLen = packet.length - parsed.headerLength
                if (decision.teed) {
                    record(destination, decision, 1, dataLen.toLong())
                    tee?.let { sink ->
                        runCatching {
                            sink.onDatagram(buf, parsed.headerLength, dataLen, appSource ?: from, destination, true, decision)
                        }.onFailure { Log.w(TAG, "tee failed: ${it.message}") }
                    }
                }
                if (!decision.forwarded) continue
                runCatching {
                    upstream.send(DatagramPacket(buf, parsed.headerLength, dataLen, destination))
                }.onFailure { Log.d(TAG, "upstream send to $destination failed: ${it.message}") }
            }
        }

        private fun pumpInbound() {
            val buf = ByteArray(MAX_DATAGRAM)
            val packet = DatagramPacket(buf, buf.size)
            while (!closed) {
                try {
                    packet.setData(buf, 0, buf.size)
                    upstream.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    return
                }
                lastActivity = clock()
                val client = clientAddress ?: continue
                val sender = packet.socketAddress as? InetSocketAddress ?: continue
                val decision = synchronized(decisions) { decisions[sender] }
                if (decision != null && decision.teed) {
                    record(sender, decision, 1, packet.length.toLong())
                    tee?.let { sink ->
                        runCatching {
                            sink.onDatagram(buf, 0, packet.length, sender, appSource ?: client, false, decision)
                        }.onFailure { Log.w(TAG, "tee failed: ${it.message}") }
                    }
                }
                val header = encodeHeader(sender)
                val frame = ByteArray(header.size + packet.length)
                System.arraycopy(header, 0, frame, 0, header.size)
                System.arraycopy(buf, 0, frame, header.size, packet.length)
                runCatching { relay.send(DatagramPacket(frame, frame.size, client)) }
            }
        }
    }

    /** Parsed RFC 1928 §7 datagram header. */
    class ParsedHeader(val destination: InetSocketAddress, val headerLength: Int)

    companion object {
        private const val TAG = "UdpPolicyRouter"
        const val DNS_PORT = 53
        const val QUIC_PORT = 443
        const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 500
        private const val MAX_DATAGRAM = 65_535

        /**
         * RFC 1928 §7 request header: `RSV(2) FRAG(1) ATYP(1) DST.ADDR DST.PORT(2)`. Fragmented
         * datagrams (`FRAG != 0`) are dropped, as the RFC permits for implementations that do not
         * reassemble. Domain destinations are resolved here (the vendored tun2proxy substitutes the
         * virtual-DNS hostname for the destination address).
         */
        fun parseHeader(buf: ByteArray, length: Int): ParsedHeader? {
            if (length < 10) return null
            if (buf[0].toInt() != 0 || buf[1].toInt() != 0) return null
            if (buf[2].toInt() != 0) return null // fragment
            val atyp = buf[3].toInt() and 0xFF
            var pos = 4
            val address: InetAddress = when (atyp) {
                Socks5Handler.ATYP_IPV4 -> {
                    if (length < pos + 4 + 2) return null
                    InetAddress.getByAddress(buf.copyOfRange(pos, pos + 4)).also { pos += 4 }
                }
                Socks5Handler.ATYP_IPV6 -> {
                    if (length < pos + 16 + 2) return null
                    InetAddress.getByAddress(buf.copyOfRange(pos, pos + 16)).also { pos += 16 }
                }
                Socks5Handler.ATYP_DOMAIN -> {
                    val len = buf[pos].toInt() and 0xFF
                    pos += 1
                    if (length < pos + len + 2) return null
                    val name = String(buf, pos, len, Charsets.UTF_8)
                    pos += len
                    runCatching { InetAddress.getByName(name) }.getOrNull() ?: return null
                }
                else -> return null
            }
            val port = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 2
            return ParsedHeader(InetSocketAddress(address, port), pos)
        }

        /** Reply header naming the upstream sender, IPv4 or IPv6. */
        fun encodeHeader(sender: InetSocketAddress): ByteArray {
            val raw = sender.address.address
            val atyp = if (raw.size == 16) Socks5Handler.ATYP_IPV6 else Socks5Handler.ATYP_IPV4
            return byteArrayOf(0, 0, 0, atyp.toByte()) + raw +
                byteArrayOf((sender.port shr 8).toByte(), (sender.port and 0xFF).toByte())
        }
    }
}
