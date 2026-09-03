package com.example.interception

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Log
import com.example.data.prefs.SecureKeyStore
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Generates and installs a real X.509 CA for MITM interception.
 *
 * v2.1 had a CA chip that always claimed "installed" with no certificate behind it. This produces
 * an actual self-signed CA keypair, persists it, and hands the DER to the system installer.
 */
object CertificateAuthority {

    private const val TAG = "CertificateAuthority"
    internal const val KEYSTORE_FILE = "requestr_ca.p12"
    internal const val KEYSTORE_ALIAS = "requestr-ca"
    private const val CA_CN = "requestr Root CA"
    private const val VALIDITY_YEARS = 10
    private const val PASSWORD_ALIAS = "ca_internal_keystore_password"

    /**
     * Password for the [exportP12] PKCS#12 bundle a user deliberately downloads to import the CA
     * into a browser or a system trust store.
     *
     * This is **intentionally a fixed, public, documented constant** — the user has to type or
     * otherwise supply this exact password during import, so by definition it cannot be secret.
     * It protects nothing on its own; its only job is to satisfy PKCS#12's mandatory password
     * field for an export the user asked for. Do not confuse this with the per-install secret
     * behind [SecureKeyStoreCaPasswordProvider], which is what actually protects the CA private
     * key at rest in `filesDir`.
     */
    private val exportKeystorePassword = "requestr-ca-local".toCharArray()

    /**
     * LEGACY password used to encrypt every install's on-disk keystore before this fix
     * (v5.9-backup and earlier). It happened to be the exact same literal as
     * [exportKeystorePassword], which was the vulnerability: the internal keystore inherited the
     * export path's necessarily-public password, so on-disk possession of the `.p12` file alone
     * was sufficient to extract the CA private key.
     *
     * [load] tries the current per-install password first and falls back to this literal only to
     * recognize and migrate a pre-fix keystore ([migrateToPerInstallPassword]); nothing is ever
     * written under this password again once migrated. Safe to delete once no install predating
     * this fix is expected to still exist in the field — not before, since removing it early would
     * strand such an install's already-trusted CA on next load (it would look unreadable and get
     * silently replaced with a brand-new, not-yet-trusted CA).
     */
    private val legacyInternalKeystorePassword = "requestr-ca-local".toCharArray()

    init {
        // Install the full BouncyCastle provider at position 1 so it shadows the platform's
        // stripped-down BC copy. The platform copy exposes SHA256withRSA inconsistently and
        // produces signatures that cannot be verified by standard TLS clients, breaking MITM.
        runCatching {
            val existing = java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (existing == null || existing !is org.bouncycastle.jce.provider.BouncyCastleProvider) {
                existing?.let { java.security.Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME) }
                java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            }
        }
    }

    data class CaMaterial(val certificate: X509Certificate, val keyPair: KeyPair)

    /**
     * Abstracts where the per-install internal-keystore password lives, so the migration logic in
     * [load] is unit-testable without a real `AndroidKeyStore` provider. Robolectric ships no
     * `AndroidKeyStore` (`SecureKeyStoreTest` documents `KeyStore.getInstance("AndroidKeyStore")`
     * throwing `NoSuchProviderException`), so tests inject a fake in-memory implementation via
     * [passwordProviderForTest] instead of exercising [SecureKeyStoreCaPasswordProvider] itself —
     * the real end-to-end `AndroidKeyStore` round-trip is verified on a physical device.
     */
    internal interface CaKeystorePasswordProvider {
        /** The currently stored per-install password, or null if none has been generated yet. */
        suspend fun read(context: Context): CharArray?

        /** Generates a fresh random password, persists it (replacing any previous one), and returns it. */
        suspend fun rotate(context: Context): CharArray
    }

    /**
     * Production [CaKeystorePasswordProvider]: a 32-byte [SecureRandom] value, Base64-encoded and
     * held via [SecureKeyStore] — i.e. AES-256-GCM-encrypted under an `AndroidKeyStore` key that
     * never leaves hardware/TEE-backed storage where available. This is the per-install secret
     * that actually protects the CA private key at rest in `filesDir`; unlike
     * [exportKeystorePassword] it is never shown to the user and never needs to be.
     *
     * If the `AndroidKeyStore`-held key is ever invalidated (e.g. the device's credentials/
     * biometrics change) while the `.p12` file survives, [read] and [rotate] both surface that as
     * "no usable password" (`SecretReadResult.Unreadable`/`Absent` map to `null`/a fresh value
     * respectively) rather than throwing — see [load]'s kdoc for how that flows into an honest
     * "regenerate a new CA" outcome instead of a silent failure to intercept.
     */
    internal object SecureKeyStoreCaPasswordProvider : CaKeystorePasswordProvider {
        override suspend fun read(context: Context): CharArray? {
            val result = SecureKeyStore.getSecret(context, PASSWORD_ALIAS)
            return (result as? SecureKeyStore.SecretReadResult.Found)?.value?.toCharArray()
        }

        override suspend fun rotate(context: Context): CharArray {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            Arrays.fill(bytes, 0)
            SecureKeyStore.putSecret(context, PASSWORD_ALIAS, encoded)
            return encoded.toCharArray()
        }
    }

    /** Test seam for [CaKeystorePasswordProvider] — production code must never set this. */
    internal var passwordProviderForTest: CaKeystorePasswordProvider? = null

    private val passwordProvider: CaKeystorePasswordProvider
        get() = passwordProviderForTest ?: SecureKeyStoreCaPasswordProvider

    /**
     * Serialises [getOrCreate]: the S1 task 7 pre-warm in `MitmProxyEngine.start` and the first
     * MITM'd connection (or a caller building a trust store) race for the CA on a fresh install.
     * Without this lock both see "no keystore yet", both generate a CA and both rotate the
     * per-install password, so the loser holds a CA that never reaches disk while the file on
     * disk may be sealed under a password nobody remembers -- every later load then regenerates
     * yet another CA and no leaf chains to the one the user installed.
     */
    private val materialLock = Mutex()

    /** Returns the existing CA, generating one on first use. Concurrent callers serialise. */
    suspend fun getOrCreate(context: Context): CaMaterial = withContext(Dispatchers.IO) {
        materialLock.withLock { load(context) ?: generate(context) }
    }

    /**
     * Loads the CA from `filesDir` if present.
     *
     * Tries the current per-install password (from [passwordProvider]) first. If that is absent
     * or doesn't open the file, falls back to [legacyInternalKeystorePassword] to recognize a
     * pre-fix keystore; a successful legacy read triggers an immediate re-encrypt under a fresh
     * per-install password ([migrateToPerInstallPassword]). If neither password opens the file —
     * e.g. the `AndroidKeyStore`-held password was invalidated by an OS credential change while
     * the file survived, or the file is corrupt — this returns null so [getOrCreate] regenerates a
     * brand-new CA rather than silently failing to intercept. That regeneration is user-visible on
     * the *next* trust-store probe (`CapabilityProbe.probeCaInstalled` / the existing "Trust
     * store:" line in Settings): the freshly generated CA won't yet be in the OS trust store, so
     * MITM will start failing until the user re-installs it — the same recovery flow the existing
     * "Regenerate CA" button already documents ("re-export and re-install"), not a new one.
     */
    private suspend fun load(context: Context): CaMaterial? {
        val file = File(context.filesDir, KEYSTORE_FILE)
        if (!file.exists()) return null

        val perInstall = runCatching { passwordProvider.read(context) }.getOrNull()
        if (perInstall != null) {
            val material = tryLoadWithPassword(file, perInstall)
            Arrays.fill(perInstall, '\u0000')
            if (material != null) return material
            Log.w(TAG, "Per-install CA password did not open the existing keystore; trying legacy fallback")
        }

        val legacy = tryLoadWithPassword(file, legacyInternalKeystorePassword)
        if (legacy != null) {
            migrateToPerInstallPassword(context, file, legacy)
            return legacy
        }

        Log.w(TAG, "Existing CA keystore is unreadable with any known password; getOrCreate() will regenerate a new CA")
        return null
    }

    /** Pure read: opens [file] with [password] and returns the CA material, or null on any failure. */
    private fun tryLoadWithPassword(file: File, password: CharArray): CaMaterial? = try {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, password) }
        val cert = ks.getCertificate(KEYSTORE_ALIAS) as? X509Certificate
        val key = cert?.let { ks.getKey(KEYSTORE_ALIAS, password) as? java.security.PrivateKey }
        if (cert != null && key != null) CaMaterial(cert, KeyPair(cert.publicKey, key)) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Re-encrypts an already-loaded [material] under a freshly generated per-install password.
     * Crash-safe: [persistAtomic] writes to a temp file and only renames it over [file] after
     * verifying the temp file round-trips under the new password, so a kill mid-migration leaves
     * the original (legacy-encrypted) file exactly as it was. A failure here is non-fatal to this
     * call — [load]'s caller already has [material] to return — and is simply retried on the next
     * load.
     */
    private suspend fun migrateToPerInstallPassword(context: Context, file: File, material: CaMaterial) {
        val newPassword = passwordProvider.rotate(context)
        val migrated = persistAtomic(file, material.certificate, material.keyPair, newPassword)
        Arrays.fill(newPassword, '\u0000')
        if (migrated) {
            Log.i(TAG, "Migrated CA keystore from the legacy shared password to a per-install secret")
        } else {
            Log.w(TAG, "Failed to migrate CA keystore to a per-install password; will retry on next load")
        }
    }

    /** Creates a genuine self-signed CA certificate with the correct CA extensions. */
    private suspend fun generate(context: Context): CaMaterial {
        val bcProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val generator = KeyPairGenerator.getInstance("RSA", bcProvider)
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=$CA_CN, O=requestr, OU=Interception")
        val serial = BigInteger(64, SecureRandom())

        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )

        // Must be a real CA cert, or Android will refuse to trust issued leaves.
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.public),
        )
        // S1 task 8: AKI on the root for symmetry with the leaves CertificateEngine mints (whose
        // AKI is derived from this same public key) -- makes chain building unambiguous for
        // validators that key on AKI/SKI matching.
        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            JcaX509ExtensionUtils().createAuthorityKeyIdentifier(keyPair.public),
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        val certificate = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))

        val password = passwordProvider.rotate(context)
        val persisted = persistAtomic(File(context.filesDir, KEYSTORE_FILE), certificate, keyPair, password)
        Arrays.fill(password, '\u0000')
        if (!persisted) Log.e(TAG, "Failed to persist newly generated CA -- it will not survive a process restart")
        Log.i(TAG, "Generated new requestr CA, valid until $notAfter")
        return CaMaterial(certificate, keyPair)
    }

    /**
     * Writes [cert]/[keyPair] as a PKCS#12 encrypted under [password] to [file], atomically: the
     * new keystore is built and stored into a sibling temp file, verified to actually round-trip
     * under [password], and only then renamed over [file]. [file] is never touched before that
     * rename succeeds, so an interrupted/failed write (process killed, disk full, permission
     * denied, etc.) can never leave a truncated or unreadable keystore in its place — worst case
     * is an orphaned temp file, and the real file (old contents, or none) is exactly as it was
     * before the call.
     */
    private fun persistAtomic(file: File, cert: X509Certificate, keyPair: KeyPair, password: CharArray): Boolean =
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(KEYSTORE_ALIAS, keyPair.private, password, arrayOf(cert))
            temp.outputStream().use { ks.store(it, password) }

            if (tryLoadWithPassword(temp, password) == null) {
                temp.delete()
                return@runCatching false
            }
            if (!temp.renameTo(file)) {
                temp.delete()
                return@runCatching false
            }
            true
        }.getOrElse {
            Log.e(TAG, "Failed to persist CA keystore: ${it.message}")
            false
        }

    /**
     * Builds the system CA-install intent. The caller must re-probe the trust store afterwards
     * rather than assuming success (APP_v2.md Bug 3.4).
     */
    suspend fun buildInstallIntent(context: Context): Intent {
        val material = getOrCreate(context)
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, material.certificate.encoded)
            putExtra(KeyChain.EXTRA_NAME, CA_CN)
        }
    }

    /** Exports the CA as PEM, for manual/adb installation into the system store on rooted devices. */
    suspend fun exportPem(context: Context): String = withContext(Dispatchers.IO) {
        val material = getOrCreate(context)
        val encoded = android.util.Base64.encodeToString(
            material.certificate.encoded,
            android.util.Base64.NO_WRAP,
        )
        buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            encoded.chunked(64).forEach { append(it).append('\n') }
            append("-----END CERTIFICATE-----\n")
        }
    }

    /** Exports the CA certificate as raw DER bytes (.cert/.cer format). */
    suspend fun exportDer(context: Context): ByteArray = withContext(Dispatchers.IO) {
        getOrCreate(context).certificate.encoded
    }

    /**
     * Exports the full CA keypair as PKCS#12 (.p12) bytes.
     * Password: "requestr-ca-local" — see [exportKeystorePassword]'s kdoc for why this is
     * intentionally public rather than the per-install secret that protects the internal copy.
     */
    suspend fun exportP12(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val material = getOrCreate(context)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEYSTORE_ALIAS, material.keyPair.private, exportKeystorePassword, arrayOf(material.certificate))
        val baos = java.io.ByteArrayOutputStream()
        ks.store(baos, exportKeystorePassword)
        baos.toByteArray()
    }

    /**
     * Returns the OpenSSL subject hash for the CA certificate.
     * This is the filename format used by Android system trust store:
     * /system/etc/security/cacerts/<hash>.0
     */
    suspend fun getSubjectHash(context: Context): String = withContext(Dispatchers.IO) {
        val material = getOrCreate(context)
        // Android uses OpenSSL's X509_NAME_hash which is:
        // first 4 bytes of MD5(DER-encoded subject) as little-endian unsigned int, hex formatted
        val subjectDer = material.certificate.subjectX500Principal.encoded
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(subjectDer)
        // First 4 bytes as little-endian unsigned long
        val hash = (digest[0].toLong() and 0xFF) or
                ((digest[1].toLong() and 0xFF) shl 8) or
                ((digest[2].toLong() and 0xFF) shl 16) or
                ((digest[3].toLong() and 0xFF) shl 24)
        String.format("%08x", hash)
    }

    // ── System CA installation (root only) ─────────────────────────

    sealed class SystemInstallResult {
        data class Success(val path: String) : SystemInstallResult()
        data class Failure(val reason: String) : SystemInstallResult()
    }

    suspend fun installToSystem(context: Context): SystemInstallResult = withContext(Dispatchers.IO) {
        try {
            val pem = exportPem(context)
            val certHash = getSubjectHash(context)
            val apexDir = "/apex/com.android.conscrypt/cacerts"
            val legacyDir = "/system/etc/security/cacerts"
            val targetDir = if (java.io.File(apexDir).exists()) apexDir else legacyDir
            val targetFile = "$targetDir/${certHash}.0"

            val script = """
                cd /data/local/tmp
                cat > requestr_ca.pem << 'REOF'
$pem
REOF
                if [ -d "$apexDir" ]; then
                    mount -t tmpfs tmpfs "$apexDir" 2>/dev/null
                    cp /system/etc/security/cacerts/* "$apexDir/" 2>/dev/null
                fi
                mount -o rw,remount "$targetDir" 2>/dev/null || mount -o rw,remount /system 2>/dev/null
                cp -f /data/local/tmp/requestr_ca.pem "$targetFile"
                chown root:root "$targetFile" 2>/dev/null
                chmod 644 "$targetFile"
                chcon u:object_r:system_file:s0 "$targetFile" 2>/dev/null
                mount -o ro,remount "$targetDir" 2>/dev/null || mount -o ro,remount /system 2>/dev/null
            """.trimIndent()

            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val exit = proc.waitFor()
            val err = proc.errorStream.bufferedReader().use { it.readText() }
            if (exit == 0) SystemInstallResult.Success(targetFile)
            else SystemInstallResult.Failure("exit=$exit: $err")
        } catch (e: Exception) {
            SystemInstallResult.Failure(e.message ?: "unknown")
        }
    }

    /**
     * Delete the CA material and regenerate fresh.
     *
     * See [CaTrustGate]'s KDoc: the new CA identity is definitely not yet trusted by the
     * device (the user hasn't re-installed it), so any already-cached [CaTrustGate.isTrusted]
     * "trusted" answer from the *old* CA is now stale -- [CaTrustGate.invalidateAll] forces
     * every live gate (including one already in use by a running capture session) to re-probe
     * on its next check instead of serving that stale answer for up to the cache TTL.
     */
    suspend fun regenerate(context: Context) = withContext(Dispatchers.IO) {
        java.io.File(context.cacheDir, "requestr_ca.pem").delete()
        java.io.File(context.cacheDir, "requestr_ca_key.pkcs8").delete()
        getOrCreate(context).also { CaTrustGate.invalidateAll() }
    }

    // Remote-certificate mirroring / per-host leaf issuance lives in
    // com.example.interception.tls.CertificateEngine (S1 task 8) -- this object only owns the CA.
}
