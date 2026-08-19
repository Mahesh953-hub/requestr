package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.CaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.RootState
import com.example.data.interception.ShizukuState
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Automates CA installation to the system trust store when root or Shizuku
 * is available, so all apps trust requestr's CA without per-app network
 * security configuration.
 *
 * Strategy priority:
 * 1. Root: copy CA cert to /system/etc/security/cacerts/
 * 2. Shizuku: use Shizuku's elevated shell to do the same
 *
 * If neither root nor Shizuku is available, this is a no-op (there is no Magisk-module
 * fallback implemented). Wired to a real UI trigger in SettingsScreen's CA card (v3.1 B11).
 */
object CaWhitelistService {

    private const val TAG = "CaWhitelist"
    private const val SYSTEM_CACERTS_DIR = "/system/etc/security/cacerts"
    const val CA_FILENAME = "requestr_ca"

    /**
     * Attempt to promote requestr's CA from user store to system store.
     * Returns the new [CaState] after the attempt.
     */
    suspend fun promoteToSystem(context: Context): CaState = withContext(Dispatchers.IO) {
        val caps = InterceptionStateRepository.capabilities.value

        // Only proceed if we have a CA in the user store
        val caCert = extractRequestrCertFromUserStore() ?: run {
            Log.w(TAG, "No requestr CA in user store — cannot promote")
            return@withContext CaState.NOT_INSTALLED
        }

        val success = when {
            caps.root == RootState.GRANTED -> installViaRoot(caCert)
            caps.shizuku == ShizukuState.GRANTED -> installViaShizuku(caCert)
            else -> {
                Log.i(TAG, "No root or Shizuku — system CA install not available")
                return@withContext caps.ca
            }
        }

        if (success) {
            // Re-probe to confirm
            val newState = CapabilityProbe.probeCaInstalled()
            InterceptionStateRepository.updateCapabilities(ca = newState)
            Log.i(TAG, "CA promotion result: $newState")
            return@withContext newState
        }

        Log.w(TAG, "CA promotion failed — CA remains in user store")
        return@withContext CaState.USER_STORE
    }

    // ── CA extraction ────────────────────────────────────────────────────

    private fun extractRequestrCertFromUserStore(): X509Certificate? {
        return runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (alias.contains(CapabilityProbe.CA_ALIAS_PREFIX, ignoreCase = true)) {
                    return ks.getCertificate(alias) as? X509Certificate
                }
                val cert = ks.getCertificate(alias) as? X509Certificate
                if (cert?.subjectX500Principal?.name?.contains(
                        CapabilityProbe.CA_ALIAS_PREFIX, ignoreCase = true
                    ) == true
                ) {
                    return cert
                }
            }
            null
        }.getOrElse { e ->
            Log.w(TAG, "Failed to extract CA from user store: ${e.message}")
            null
        }
    }

    // ── Root-based install ───────────────────────────────────────────────

    private fun installViaRoot(cert: X509Certificate): Boolean {
        return runCatching {
            // Compute the OpenSSL hash for the cert filename
            val hash = computeCertHash(cert)
            val destFile = "$SYSTEM_CACERTS_DIR/$hash.0"

            // Export cert to PEM
            val pem = certToPem(cert)

            // Write to a temp location first
            val tmpFile = File.createTempFile("requestr_ca_", ".pem")
            tmpFile.writeText(pem)
            tmpFile.setReadable(true, false)

            val commands = arrayOf(
                "mount -o rw,remount /system",
                "cp ${tmpFile.absolutePath} $destFile",
                "chmod 644 $destFile",
                "chown root:root $destFile",
                "mount -o ro,remount /system",
            )

            val script = commands.joinToString(" && ")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val exitCode = proc.waitFor()

            // Clean up temp
            tmpFile.delete()

            if (exitCode == 0) {
                Log.i(TAG, "CA installed to system store via root: $destFile")
                true
            } else {
                val err = proc.errorStream.bufferedReader().use { it.readText() }
                Log.w(TAG, "Root CA install failed (exit=$exitCode): $err")
                false
            }
        }.getOrElse { e ->
            Log.w(TAG, "Root CA install exception: ${e.message}")
            false
        }
    }

    // ── Shizuku-based install ────────────────────────────────────────────

    private fun installViaShizuku(cert: X509Certificate): Boolean {
        return runCatching {
            val hash = computeCertHash(cert)
            val destFile = "$SYSTEM_CACERTS_DIR/$hash.0"
            val pem = certToPem(cert)

            val tmpFile = File.createTempFile("requestr_ca_", ".pem")
            tmpFile.writeText(pem)

            // Use Shizuku's newProcess to run as root-equivalent
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val newProcess = shizuku.getMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )

            val commands = arrayOf(
                "mount -o rw,remount /system",
                "cp ${tmpFile.absolutePath} $destFile",
                "chmod 644 $destFile",
                "chown root:root $destFile",
                "mount -o ro,remount /system",
            )

            val process = newProcess.invoke(null, commands, null, null) as? java.lang.Process
            val exitCode = process?.waitFor() ?: -1

            tmpFile.delete()

            exitCode == 0
        }.getOrElse { e ->
            Log.w(TAG, "Shizuku CA install exception: ${e.message}")
            false
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Compute the OpenSSL-style cert hash used for ca-certificates filenames.
     * The format is an 8-char hex hash of the subject DN.
     */
    private fun computeCertHash(cert: X509Certificate): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(cert.subjectX500Principal.encoded)
        // Take first 4 bytes, reverse, convert to hex
        val hex = StringBuilder()
        for (i in 3 downTo 0) {
            hex.append(String.format("%02x", digest[i]))
        }
        return hex.toString()
    }

    private fun certToPem(cert: X509Certificate): String {
        val encoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
        val encoded = encoder.encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
    }
}
