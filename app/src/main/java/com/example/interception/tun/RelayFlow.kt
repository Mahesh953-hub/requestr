package com.example.interception.tun

import java.net.DatagramSocket
import java.net.Socket
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.ArrayDeque

/**
 * `VpnService.protect(...)` has real overloads for `Socket`, `DatagramSocket` and a raw `int`
 * fd ([android.net.VpnService] itself). This indirection lets [TunRelay] be constructed with a
 * production hook (delegating to the real `VpnService`) or a no-op hook in JVM unit tests,
 * where there is no real `VpnService` to protect through.
 */
interface ProtectHook {
    fun protectSocket(socket: Socket): Boolean
    fun protectDatagramSocket(socket: DatagramSocket): Boolean

    /** For descriptors that don't come from a [Socket]/[DatagramSocket] (e.g. the ICMP ping
     * socket in task 6, obtained via `Os.socket(...)` as a raw `FileDescriptor`). */
    fun protectFd(fd: Int): Boolean
}

/** Always returns true without touching any real VPN routing -- used by JVM unit tests. */
object NoOpProtectHook : ProtectHook {
    override fun protectSocket(socket: Socket) = true
    override fun protectDatagramSocket(socket: DatagramSocket) = true
    override fun protectFd(fd: Int) = true
}

/** Common per-flow state: 5-tuple, address family, last-activity stamp for LRU eviction. */
sealed class RelayFlow {
    abstract val family: Int // 4 or 6
    abstract val srcIp: ByteArray
    abstract val srcPort: Int
    abstract val dstIp: ByteArray
    abstract val dstPort: Int
    abstract var lastActivityAt: Long

    val key: String
        get() {
            val srcStr = if (family == 4) IpAddressCodec.formatIpv4(srcIp, 0) else IpAddressCodec.formatIpv6(srcIp, 0)
            val dstStr = if (family == 4) IpAddressCodec.formatIpv4(dstIp, 0) else IpAddressCodec.formatIpv6(dstIp, 0)
            return "$family|$srcStr:$srcPort>$dstStr:$dstPort"
        }
}

enum class TcpRelayState { CONNECTING, ESTABLISHED, CLOSING, CLOSED }

class TcpRelayFlow(
    override val family: Int,
    override val srcIp: ByteArray,
    override val srcPort: Int,
    override val dstIp: ByteArray,
    override val dstPort: Int,
) : RelayFlow() {
    override var lastActivityAt: Long = System.currentTimeMillis()

    var channel: SocketChannel? = null
    var selectionKey: SelectionKey? = null
    var state: TcpRelayState = TcpRelayState.CONNECTING

    /** Next sequence number we expect from the guest (client → us). */
    var guestNextSeq: Long = 0L

    /** Our own sequence number for bytes we send back to the guest. */
    var ourSeq: Long = 0L

    /** Bytes queued for upstream because the channel wasn't connected yet when they arrived. */
    val pendingUpstreamWrites: ArrayDeque<ByteArray> = ArrayDeque()

    var bytesUp: Long = 0
    var bytesDown: Long = 0
}

class UdpRelayFlow(
    override val family: Int,
    override val srcIp: ByteArray,
    override val srcPort: Int,
    override val dstIp: ByteArray,
    override val dstPort: Int,
) : RelayFlow() {
    override var lastActivityAt: Long = System.currentTimeMillis()

    var channel: DatagramChannel? = null
    var selectionKey: SelectionKey? = null

    var bytesUp: Long = 0
    var bytesDown: Long = 0
}
