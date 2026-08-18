package com.example.interception

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.data.interception.CaState
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.RootState
import com.example.data.interception.ShizukuState
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real runtime capability detection.
 *
 * This exists because v2.1 hardcoded root/Shizuku/Frida/CA to granted, so every chip rendered as
 * "Active"/"Granted" regardless of the device (APP_v2.md Bug 3.1). Every function here performs an
 * actual check and returns a negative result when it cannot prove otherwise.
 */
object CapabilityProbe {

    private const val TAG = "CapabilityProbe"

    /** Standard locations a real `su` binary may live. */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
    )

    private const val FRIDA_DEFAULT_PORT = 27042
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val CA_ALIAS_PREFIX = "requestr"

    /** Runs every probe and pushes real results into the shared state. */
    suspend fun probeAll(context: Context) {
        InterceptionStateRepository.setProbing(true)
        try {
            val root = probeRoot()
            val shizuku = probeShizuku(context)
            val ca = probeCaInstalled()
            val frida = probeFrida()

            InterceptionStateRepository.updateCapabilities(
                root = root,
                shizuku = shizuku,
                ca = ca,
                frida = frida,
            )
        } finally {
            InterceptionStateRepository.setProbing(false)
        }
    }

    /**
     * Two-stage root check: presence of a `su` binary, then a real exec handshake.
     *
     * Presence alone is only AVAILABLE — GRANTED requires `su -c id` to actually return uid 0,
     * which is what proves the user approved the request.
     */
    suspend fun probeRoot(): RootState = withContext(Dispatchers.IO) {
        val binaryExists = SU_PATHS.any { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }

        if (!binaryExists && !whichSuSucceeds()) {
            return@withContext RootState.UNAVAILABLE
        }

        // A real handshake. Bounded so a hanging su prompt cannot block the probe sweep.
        val granted = withTimeoutOrNull(ROOT_HANDSHAKE_TIMEOUT_MS) {
            runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output.contains("uid=0")
            }.getOrDefault(false)
        }

        when (granted) {
            true -> RootState.GRANTED
            false -> RootState.DENIED
            // Timed out: a prompt may be pending, so root exists but is not yet granted.
            null -> RootState.AVAILABLE
        }
    }

    private fun whichSuSucceeds(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
        val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        out.isNotEmpty()
    }.getOrDefault(false)

    /**
     * Real Shizuku state via the Shizuku SDK.
     *
     * Uses reflection so the app still runs when the Shizuku classes are absent at runtime,
     * and distinguishes not-installed from installed-but-not-running from permission-denied.
     */
    suspend fun probeShizuku(context: Context): ShizukuState = withContext(Dispatchers.IO) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        if (!installed) return@withContext ShizukuState.NOT_INSTALLED

        runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")

            val pingBinder = shizuku.getMethod("pingBinder")
            val alive = pingBinder.invoke(null) as? Boolean ?: false
            if (!alive) return@withContext ShizukuState.NOT_RUNNING

            val checkSelfPermission = shizuku.getMethod("checkSelfPermission")
            val result = checkSelfPermission.invoke(null) as? Int ?: PackageManager.PERMISSION_DENIED

            if (result == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.GRANTED
            } else {
                ShizukuState.PERMISSION_DENIED
            }
        }.getOrElse { e ->
            Log.w(TAG, "Shizuku probe failed: ${e.message}")
            ShizukuState.NOT_RUNNING
        }
    }

    /**
     * Enumerates the real trust store looking for requestr's CA.
     *
     * Checks the user store via AndroidCAStore, then the system store directory. Never reports
     * installed based on a cached flag (APP_v2.md Bug 3.1 / 3.4).
     */
    suspend fun probeCaInstalled(): CaState = withContext(Dispatchers.IO) {
        // System store: requires root to write, so a hit here means system-level trust.
        val inSystemStore = runCatching {
            File("/system/etc/security/cacerts").listFiles()?.any { f ->
                runCatching { f.readText().contains(CA_ALIAS_PREFIX, ignoreCase = true) }
                    .getOrDefault(false)
            } ?: false
        }.getOrDefault(false)

        if (inSystemStore) return@withContext CaState.SYSTEM_STORE

        val inUserStore = runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val aliases = ks.aliases()
            var found = false
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (alias.contains(CA_ALIAS_PREFIX, ignoreCase = true)) {
                    found = true
                    break
                }
                val cert = runCatching { ks.getCertificate(alias) }.getOrNull()
                val subject = (cert as? java.security.cert.X509Certificate)
                    ?.subjectX500Principal?.name
                if (subject?.contains(CA_ALIAS_PREFIX, ignoreCase = true) == true) {
                    found = true
                    break
                }
            }
            found
        }.getOrElse { e ->
            Log.w(TAG, "CA store probe failed: ${e.message}")
            false
        }

        if (inUserStore) CaState.USER_STORE else CaState.NOT_INSTALLED
    }

    /**
     * Real TCP handshake against frida-server. A refused connection means not running —
     * never assume it is up.
     */
    suspend fun probeFrida(port: Int = FRIDA_DEFAULT_PORT): FridaState = withContext(Dispatchers.IO) {
        val reachable = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), FRIDA_TIMEOUT_MS)
                socket.isConnected
            }
        }.getOrDefault(false)

        if (reachable) FridaState.RUNNING else FridaState.NOT_RUNNING
    }

    private const val ROOT_HANDSHAKE_TIMEOUT_MS = 3_000L
    private const val FRIDA_TIMEOUT_MS = 600
}
