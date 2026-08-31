package com.example.interception

import android.util.Log
import com.example.data.interception.engineLifecycleOwner
import com.example.data.interception.engineTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root-based transparent proxy via iptables nat REDIRECT rules.
 *
 * Requires root (su binary). Redirects all TCP:80 and TCP:443 traffic
 * to the local proxy port WITHOUT a VPN dialog. This is the highest-fidelity
 * capture method — transparent, no app needs proxy configuration.
 *
 * Rules applied:
 *   iptables -t nat -A OUTPUT -p tcp --dport 80  -j REDIRECT --to-port {proxyPort}
 *   iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-port {proxyPort}
 *
 * UID exclusion is added so the proxy itself isn't redirected:
 *   iptables -t nat -A OUTPUT -m owner --uid-owner {uid} -j RETURN
 */
class RootIptablesEngine {
    private var proxyPort: Int = 0
    private var active = false
    private var sessionId = 0L

    companion object {
        private const val TAG = "RootIptables"
    }

    /** Check if root iptables can work — su exists AND iptables binary exists. */
    fun isAvailable(): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "which iptables"))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            out.contains("iptables")
        }.getOrDefault(false)
    }

    fun start(port: Int, excludeUid: Int): Boolean {
        if (active) {
            Log.w(TAG, "Already active on port $proxyPort")
            return false
        }
        proxyPort = port
        sessionId = System.currentTimeMillis()

        val rules = buildList {
            // Exclude own UID from redirection (so proxy can reach upstream)
            add("iptables -t nat -A OUTPUT -m owner --uid-owner $excludeUid -j RETURN")
            // Redirect HTTP
            add("iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to-port $port")
            add("iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-port $port")
            add("iptables -t nat -A OUTPUT -p udp --dport 443 -j REDIRECT --to-port $port")
        }

        val success = executeRules(rules)
        if (success) {
            active = true
            InterceptionStateRepository.markActive(
                sessionId = sessionId, tier = CaptureMethod.ROOT_IPTABLES.engineTier(),
                port = port, method = CaptureMethod.ROOT_IPTABLES,
                lifecycleOwner = CaptureMethod.ROOT_IPTABLES.engineLifecycleOwner(),
            )
            Log.i(TAG, "iptables rules installed for port $port (uid=$excludeUid)")
        }
        return success
    }

    fun stop(excludeUid: Int) {
        if (!active) return
        val rules = buildList {
            add("iptables -t nat -D OUTPUT -m owner --uid-owner $excludeUid -j RETURN")
            add("iptables -t nat -D OUTPUT -p tcp --dport 80 -j REDIRECT --to-port $proxyPort")
            add("iptables -t nat -D OUTPUT -p tcp --dport 443 -j REDIRECT --to-port $proxyPort")
        }
        executeRules(rules)
        active = false
        Log.i(TAG, "iptables rules removed for port $proxyPort")
    }

    fun isActive(): Boolean = active

    /** Verify rules are present in the nat table. */
    fun verify(): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "iptables -t nat -L OUTPUT -n"))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            out.contains("REDIRECT") && out.contains(proxyPort.toString())
        }.getOrDefault(false)
    }

    private fun executeRules(rules: List<String>): Boolean {
        val script = rules.joinToString(" && ") { "$it 2>/dev/null" }
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val exit = proc.waitFor()
            if (exit != 0) {
                val err = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                Log.e(TAG, "iptables failed (exit=$exit): $err")
            }
            exit == 0
        }.getOrElse { e ->
            Log.e(TAG, "su execution failed: ${e.message}")
            false
        }
    }
}
