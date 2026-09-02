package com.example.interception

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Master plan §3 LOW-ADD — `.p12` CA import/export.
 *
 * [CertificateAuthority] already implements the export half ([CertificateAuthority.exportP12]),
 * so this file only adds *import*: taking a user-supplied PKCS#12 bundle (their own CA, or a
 * previously-exported requestr CA they're restoring onto a new install) and installing it as
 * this app's active CA — **without editing `CertificateAuthority.kt`**.
 *
 * How that's possible without touching the target file: [CertificateAuthority.KEYSTORE_FILE],
 * [CertificateAuthority.KEYSTORE_ALIAS] and [CertificateAuthority.SecureKeyStoreCaPasswordProvider]
 * are all declared `internal`, i.e. visible to any file in this same Gradle module. [importP12]
 * writes the imported cert+key straight to `File(context.filesDir, KEYSTORE_FILE)`, encrypted
 * under a freshly [CertificateAuthority.SecureKeyStoreCaPasswordProvider.rotate]-d per-install
 * password — the exact same on-disk shape [CertificateAuthority] itself produces — so the next
 * [CertificateAuthority.getOrCreate] call picks it up transparently, with no cooperation needed
 * from `CertificateAuthority.kt`.
 */
object CaP12Exchange {
    private const val TAG = "CaP12Exchange"

    /** Result of parsing/validating a supplied PKCS#12 bundle, before anything is written to disk. */
    sealed interface ParseResult {
        data class Success(val certificate: X509Certificate, val keyPair: KeyPair) : ParseResult
        data class Failure(val reason: String) : ParseResult
    }

    /**
     * Pure parse step: opens [p12Bytes] as a PKCS#12 keystore under [password] and extracts the
     * first alias holding a private key + certificate chain. No filesystem/Context access, so
     * this is unit-testable with an in-memory keystore built the same way
     * [CertificateAuthorityTest] builds one.
     *
     * [requireCa] (default true) rejects a leaf certificate that isn't itself a CA
     * (`X509Certificate.getBasicConstraints() == -1`) — importing a non-CA cert here would silently
     * produce a "CA" that can never sign a trusted leaf, so this fails loudly instead.
     */
    fun parse(p12Bytes: ByteArray, password: CharArray, requireCa: Boolean = true): ParseResult {
        val ks = try {
            KeyStore.getInstance("PKCS12").apply {
                java.io.ByteArrayInputStream(p12Bytes).use { load(it, password) }
            }
        } catch (e: Exception) {
            return ParseResult.Failure("Could not open PKCS#12 bundle: ${e.message}")
        }

        val alias = ks.aliases().asSequence().firstOrNull { ks.isKeyEntry(it) }
            ?: return ParseResult.Failure("PKCS#12 bundle has no private-key entry")

        val key = try {
            ks.getKey(alias, password) as? java.security.PrivateKey
        } catch (e: Exception) {
            return ParseResult.Failure("Could not read private key for alias '$alias': ${e.message}")
        } ?: return ParseResult.Failure("Alias '$alias' key is not a usable private key")

        val cert = ks.getCertificate(alias) as? X509Certificate
            ?: return ParseResult.Failure("Alias '$alias' has no X.509 certificate")

        if (requireCa && !isCaCertificate(cert)) {
            return ParseResult.Failure("Certificate for alias '$alias' is not a CA certificate (basicConstraints CA=false)")
        }

        return ParseResult.Success(cert, KeyPair(cert.publicKey, key))
    }

    /**
     * Imports [p12Bytes] (protected by [password]) as this app's active CA. On success, the
     * *next* [CertificateAuthority.getOrCreate]/[CertificateAuthority.buildInstallIntent] call
     * transparently uses the imported CA. Never overwrites the existing on-disk keystore unless
     * parsing and the atomic write both succeed.
     */
    suspend fun importP12(context: Context, p12Bytes: ByteArray, password: CharArray, requireCa: Boolean = true): Result<Unit> =
        withContext(Dispatchers.IO) {
            when (val parsed = parse(p12Bytes, password, requireCa)) {
                is ParseResult.Failure -> Result.failure(IllegalArgumentException(parsed.reason))
                is ParseResult.Success -> {
                    val newPassword = CertificateAuthority.SecureKeyStoreCaPasswordProvider.rotate(context)
                    val target = File(context.filesDir, CertificateAuthority.KEYSTORE_FILE)
                    val ok = persistAtomic(target, parsed.certificate, parsed.keyPair, newPassword)
                    Arrays.fill(newPassword, '\u0000')
                    if (ok) {
                        Log.i(TAG, "Imported CA installed as the active requestr CA")
                        // See CaTrustGate's KDoc: a different CA identity is now active, and the
                        // device hasn't necessarily been told to trust it -- any cached
                        // CaTrustGate.isTrusted "trusted" answer from the previous CA is stale.
                        CaTrustGate.invalidateAll()
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("Failed to persist imported CA to disk"))
                    }
                }
            }
        }

    /** Thin re-export of [CertificateAuthority.exportP12] so both directions live at one call site. */
    suspend fun exportP12(context: Context): ByteArray = CertificateAuthority.exportP12(context)

    /**
     * Same atomic write shape [CertificateAuthority.persistAtomic] uses (build into a temp file,
     * verify it round-trips under [password], only then rename over [file]) — reimplemented here
     * rather than exposed from `CertificateAuthority.kt`, since that file is not to be edited.
     */
    private fun persistAtomic(file: File, cert: X509Certificate, keyPair: KeyPair, password: CharArray): Boolean =
        runCatching {
            val temp = File(file.parentFile, "${file.name}.import.tmp")
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(CertificateAuthority.KEYSTORE_ALIAS, keyPair.private, password, arrayOf(cert))
            temp.outputStream().use { ks.store(it, password) }

            val verify = try {
                KeyStore.getInstance("PKCS12").apply { temp.inputStream().use { load(it, password) } }
                    .getCertificate(CertificateAuthority.KEYSTORE_ALIAS) != null
            } catch (e: Exception) {
                false
            }
            if (!verify) {
                temp.delete()
                return@runCatching false
            }
            if (!temp.renameTo(file)) {
                temp.delete()
                return@runCatching false
            }
            true
        }.getOrElse {
            Log.e(TAG, "Failed to persist imported CA keystore: ${it.message}")
            false
        }
}

/** `X509Certificate.getBasicConstraints()` returns the CA path-length, or -1 for a non-CA leaf. */
fun isCaCertificate(cert: X509Certificate): Boolean = cert.basicConstraints != -1
