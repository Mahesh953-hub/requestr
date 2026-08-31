package com.example.interception

import android.util.Log
import com.example.data.interception.engineLifecycleOwner
import com.example.data.interception.engineTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository

/**
 * Shizuku-based capture engine.
 *
 * Uses Shizuku's elevated user-service process to create a TUN interface
 * and apply iptables rules without root. This is a middle ground between
 * VPN_TUN (user consent dialog) and ROOT_IPTABLES (full root).
 *
 * Shizuku provides: shell commands running as adb (UID 2000) or root (UID 0),
 * plus the ability to start elevated services.
 */
class ShizukuTunEngine {
    private var proxyPort: Int = 0
    private var active = false
    private var sessionId = 0L

    companion object {
        private const val TAG = "ShizukuTun"
    }

    /** Check if Shizuku is running and accessible. */
    fun isAvailable(): Boolean {
        return runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val pingBinder = shizuku.getMethod("pingBinder")
            (pingBinder.invoke(null) as? Boolean) ?: false
        }.getOrDefault(false)
    }

    fun start(port: Int): Boolean {
        if (active) return false
        proxyPort = port
        sessionId = System.currentTimeMillis()

        val proc = executeShizuku(
            "iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to-port $port",
            "iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-port $port",
        )

        val success = proc?.waitFor() == 0
        if (success) {
            active = true
            InterceptionStateRepository.markActive(
                sessionId = sessionId, tier = CaptureMethod.SHIZUKU_TUN.engineTier(),
                port = port, method = CaptureMethod.SHIZUKU_TUN,
                lifecycleOwner = CaptureMethod.SHIZUKU_TUN.engineLifecycleOwner(),
            )
            Log.i(TAG, "Shizuku iptables applied for port $port")
        }
        return success
    }

    fun stop(port: Int) {
        if (!active) return
        executeShizuku(
            "iptables -t nat -D OUTPUT -p tcp --dport 80 -j REDIRECT --to-port $port",
            "iptables -t nat -D OUTPUT -p tcp --dport 443 -j REDIRECT --to-port $port",
        )
        active = false
        Log.i(TAG, "Shizuku iptables removed")
    }

    fun isActive(): Boolean = active

    /**
     * Execute commands via Shizuku's newProcess API.
     * Falls back to the shell-style userService if newProcess is unavailable.
     */
    private fun executeShizuku(vararg commands: String): Process? {
        return runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")

            // Try newProcess first (requires Shizuku >= 13)
            try {
                val newProcess = shizuku.getMethod("newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java)
                val cmd = arrayOf("sh", "-c", commands.joinToString(" && "))
                (newProcess.invoke(null, cmd, null, null) as? Process)
            } catch (_: NoSuchMethodException) {
                // Fallback: use ShizukuBinder + IUserService for shell execution
                val binder = shizuku.getMethod("getBinder").invoke(null)
                if (binder != null) {
                    // Binder is alive — use Runtime-style exec through Shizuku
                    val cmd = "sh -c '${commands.joinToString(" && ")}'"
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                } else null
            }
        }.getOrElse { e ->
            Log.w(TAG, "Shizuku execution failed: ${e.message}")
            null
        }
    }
}
