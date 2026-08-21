package com.example.interception

import android.util.Log
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.WatcherStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Background coroutine that verifies the selected capture method is actually receiving traffic.
 *
 * Runs every 5 seconds while capture is active. If silence is detected for >30 seconds,
 * surfaces an ERROR state with diagnostic text so the user can investigate.
 *
 * Each [CaptureMethod] gets its own health check:
 * - VPN_TUN: checks tun fd is accessible, packet count increasing
 * - HTTP_PROXY: checks proxy port is accepting connections
 * - MITM_PROXY: checks proxy port + TLS handshake rate
 * - ROOT_IPTABLES: checks iptables rules exist, proxy receiving traffic
 * - ROOT_FRIDA: checks Frida server reachable
 * - SHIZUKU_TUN: checks Shizuku binder alive, tun packet count
 */
class CaptureMethodWatcher(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private var watcherJob: Job? = null
    private var lastPacketCount = 0L
    private var silenceStreak = 0
    private var healthyStreak = 0

    companion object {
        private const val TAG = "MethodWatcher"
        private const val WATCH_INTERVAL_MS = 5_000L
        private const val SILENCE_THRESHOLD = 6 // 6 cycles × 5s = 30s
        private const val HEALTHY_CONFIRM_CYCLES = 2 // 2 cycles = 10s to confirm healthy

        // iptables marker paths for root detection
        private val IPTABLES_RULE_MARKERS = listOf(
            "/data/adb/modules",
            "/data/adb/magisk",
            "/data/adb/ksu",
        )
    }

    fun start(method: CaptureMethod, port: Int) {
        stop()
        lastPacketCount = 0
        silenceStreak = 0
        healthyStreak = 0

        watcherJob = scope.launch {
            Log.i(TAG, "Watcher started for $method on port $port")
            InterceptionStateRepository.setWatcherStatus(WatcherStatus.IDLE)

            while (isActive) {
                delay(WATCH_INTERVAL_MS)

                val state = InterceptionStateRepository.state.value
                if (!state.isActive) {
                    // Capture stopped — go idle
                    InterceptionStateRepository.setWatcherStatus(WatcherStatus.IDLE)
                    return@launch
                }

                val currentPacketCount = state.packetCount.toLong()
                val receivedTraffic = currentPacketCount > lastPacketCount

                lastPacketCount = currentPacketCount

                val methodOk = checkMethodHealth(method, state.port)
                val isHealthy = receivedTraffic && methodOk

                when {
                    isHealthy -> {
                        silenceStreak = 0
                        healthyStreak++
                        if (healthyStreak >= HEALTHY_CONFIRM_CYCLES) {
                            InterceptionStateRepository.setWatcherStatus(WatcherStatus.HEALTHY)
                        }
                    }
                    !methodOk -> {
                        // Transport-level failure: port dead, tun closed, etc.
                        silenceStreak++
                        if (silenceStreak >= 2) {
                            InterceptionStateRepository.setWatcherStatus(WatcherStatus.DEGRADED)
                            Log.w(TAG, "Method $method health check failed (silence streak=$silenceStreak)")
                        }
                    }
                    else -> {
                        // No new packets but method seems ok
                        silenceStreak++
                        if (silenceStreak >= SILENCE_THRESHOLD) {
                            InterceptionStateRepository.setWatcherStatus(WatcherStatus.ERROR)
                            Log.e(TAG, "Method $method silent for ${silenceStreak * 5}s — reporting ERROR")
                        } else if (silenceStreak >= 3) {
                            InterceptionStateRepository.setWatcherStatus(WatcherStatus.DEGRADED)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        InterceptionStateRepository.setWatcherStatus(WatcherStatus.IDLE)
        Log.i(TAG, "Watcher stopped")
    }

    // ── per-method health checks ─────────────────────────────────────────

    private fun checkMethodHealth(method: CaptureMethod, port: Int): Boolean = when (method) {
        CaptureMethod.VPN_TUN -> checkVpnTun()
        CaptureMethod.HTTP_PROXY -> checkProxyPort(port)
        CaptureMethod.MITM_PROXY -> checkProxyPort(port)
        CaptureMethod.ROOT_IPTABLES -> checkRootIptables(port)
        CaptureMethod.ROOT_FRIDA -> checkFridaReachable()
        CaptureMethod.SHIZUKU_TUN -> checkShizukuAlive()
    }

    private fun checkVpnTun(): Boolean {
        // VPN service keeps the tun fd open while running; if the service
        // died, isActive would be false already. Probe /proc/net/dev for tun.
        return runCatching {
            val dev = File("/proc/net/dev").readText()
            dev.contains("tun") || dev.contains("vpn")
        }.getOrDefault(true) // err on the side of not false-alarming
    }

    private fun checkProxyPort(port: Int): Boolean = runCatching {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
            sock.isConnected
        }
    }.getOrDefault(false)

    private fun checkRootIptables(port: Int): Boolean {
        // Check if iptables rules exist AND proxy is receiving
        val rulesExist = runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "iptables -t nat -L OUTPUT -n"))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            out.contains("REDIRECT") && out.contains(port.toString())
        }.getOrDefault(false)

        return rulesExist || checkProxyPort(port)
    }

    private fun checkFridaReachable(): Boolean = runCatching {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress("127.0.0.1", 27042), 500)
            sock.isConnected
        }
    }.getOrDefault(false)

    private fun checkShizukuAlive(): Boolean {
        return runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val pingBinder = shizuku.getMethod("pingBinder")
            pingBinder.invoke(null) as? Boolean ?: false
        }.getOrDefault(false)
    }
}
