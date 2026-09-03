package com.example.interception.net

import android.util.Log
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide socket protection for egress created while the local VPN is active.
 *
 * [install] is called only after a tun has been established and before any relay starts. While
 * installed, [protectSocket] wildcard-binds an unbound socket so Android has a real fd to protect.
 * With no active installation it leaves sockets untouched. Its
 * returned [Registration] owns that installation: stale service instances cannot clear a newer
 * service's protector during a restart. [clear] is called after all relays and the tun have been
 * closed. Therefore [ProtectResult.Unavailable] means no VPN session is active and callers may
 * connect normally; an installed backing returning false or throwing is always [ProtectResult.Failed]
 * and egress callers must fail closed.
 */
object SocketProtector {
    private const val TAG = "SocketProtector"

    enum class ProtectResult {
        Protected,
        Failed,
        Unavailable,
    }

    interface Backing {
        fun protectSocket(socket: Socket): Boolean
        fun protectDatagramSocket(socket: DatagramSocket): Boolean
        fun protectFd(fd: Int): Boolean
    }

    class Registration internal constructor(internal val id: Long)

    private data class Installed(val registration: Registration, val backing: Backing)

    private val nextRegistrationId = AtomicLong(0)
    private val installed = AtomicReference<Installed?>(null)
    private val successCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)
    private val unavailableCount = AtomicLong(0)

    /** Installs the backing for one active VPN service instance. */
    fun install(backing: Backing): Registration {
        val registration = Registration(nextRegistrationId.incrementAndGet())
        installed.set(Installed(registration, backing))
        Log.i(TAG, "Backing installed")
        return registration
    }

    /**
     * Clears only the installation owned by [registration]. This makes delayed teardown from an
     * old service instance harmless after a replacement service has already installed its backing.
     */
    fun clear(registration: Registration) {
        while (true) {
            val current = installed.get() ?: return
            if (current.registration.id != registration.id) return
            if (installed.compareAndSet(current, null)) {
                Log.i(TAG, "Backing cleared")
                return
            }
        }
    }

    val isInstalled: Boolean get() = installed.get() != null

    /**
     * Materializes the Java socket's native fd before delegating to [Backing]. Android's
     * `VpnService.protect(Socket)` reads that fd directly; a plain unbound `Socket()` may not have
     * one yet. A wildcard bind creates it without choosing the remote address or connecting, and
     * keeps address-family selection compatible with both IPv4 and IPv6 destinations.
     */
    fun protectSocket(socket: Socket): ProtectResult {
        val activeBacking = installed.get()?.backing ?: return ProtectResult.Unavailable.also {
            unavailableCount.incrementAndGet()
        }
        try {
            if (!socket.isBound) socket.bind(null)
        } catch (t: Throwable) {
            failureCount.incrementAndGet()
            Log.w(TAG, "prepareSocketForProtection threw: ${t.message}")
            return ProtectResult.Failed
        }
        return protectWith(activeBacking, "protectSocket") { it.protectSocket(socket) }
    }

    fun protectDatagramSocket(socket: DatagramSocket): ProtectResult = protect("protectDatagramSocket") {
        it.protectDatagramSocket(socket)
    }

    fun protectFd(fd: Int): ProtectResult = protect("protectFd($fd)") {
        it.protectFd(fd)
    }

    private inline fun protect(operation: String, call: (Backing) -> Boolean): ProtectResult {
        val backing = installed.get()?.backing ?: return ProtectResult.Unavailable.also {
            unavailableCount.incrementAndGet()
        }
        return protectWith(backing, operation, call)
    }

    private inline fun protectWith(backing: Backing, operation: String, call: (Backing) -> Boolean): ProtectResult {
        return try {
            if (call(backing)) {
                successCount.incrementAndGet()
                ProtectResult.Protected
            } else {
                failureCount.incrementAndGet()
                Log.w(TAG, "$operation returned false")
                ProtectResult.Failed
            }
        } catch (t: Throwable) {
            failureCount.incrementAndGet()
            Log.w(TAG, "$operation threw: ${t.message}")
            ProtectResult.Failed
        }
    }

    data class Metrics(
        val successes: Long,
        val failures: Long,
        val unavailable: Long,
    )

    fun metrics(): Metrics = Metrics(
        successes = successCount.get(),
        failures = failureCount.get(),
        unavailable = unavailableCount.get(),
    )

    /** Reset at VPN-session start and by isolated unit-test setup. */
    fun resetMetrics() {
        successCount.set(0)
        failureCount.set(0)
        unavailableCount.set(0)
    }
}
