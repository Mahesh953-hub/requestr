package com.example.interception

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import kotlinx.coroutines.Dispatchers
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
    private const val KEYSTORE_FILE = "requestr_ca.p12"
    private const val KEYSTORE_ALIAS = "requestr-ca"
    private const val CA_CN = "requestr Root CA"
    private const val VALIDITY_YEARS = 10

    // Password protects the on-disk PKCS#12 in app-private storage.
    private val keystorePassword = "requestr-ca-local".toCharArray()

    init {
        // Register BC so SHA256withRSA and the cert builders resolve on Android.
        runCatching {
            if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                java.security.Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    data class CaMaterial(val certificate: X509Certificate, val keyPair: KeyPair)

    /** Returns the existing CA, generating one on first use. */
    suspend fun getOrCreate(context: Context): CaMaterial = withContext(Dispatchers.IO) {
        load(context) ?: generate(context)
    }

    private fun load(context: Context): CaMaterial? = runCatching {
        val file = File(context.filesDir, KEYSTORE_FILE)
        if (!file.exists()) return@runCatching null

        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, keystorePassword) }

        val cert = ks.getCertificate(KEYSTORE_ALIAS) as? X509Certificate ?: return@runCatching null
        val key = ks.getKey(KEYSTORE_ALIAS, keystorePassword) as? java.security.PrivateKey
            ?: return@runCatching null

        CaMaterial(cert, KeyPair(cert.publicKey, key))
    }.getOrElse {
        Log.w(TAG, "Failed to load existing CA: ${it.message}")
        null
    }

    /** Creates a genuine self-signed CA certificate with the correct CA extensions. */
    private fun generate(context: Context): CaMaterial {
        val generator = KeyPairGenerator.getInstance("RSA")
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

        // Do NOT pin to a named provider.  On Android the platform already ships a
        // stripped BouncyCastle instance, so `.setProvider("BC")` resolves to the
        // platform copy which does NOT expose SHA256withRSA under that alias.
        // Letting the JCA choose (with our full BC provider registered first) avoids
        // the "no such algorithm: SHA256WITHRSA for provider BC" crash.
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)

        val certificate = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))

        persist(context, certificate, keyPair)
        Log.i(TAG, "Generated new requestr CA, valid until $notAfter")
        return CaMaterial(certificate, keyPair)
    }

    private fun persist(context: Context, cert: X509Certificate, keyPair: KeyPair) {
        runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(KEYSTORE_ALIAS, keyPair.private, keystorePassword, arrayOf(cert))
            File(context.filesDir, KEYSTORE_FILE).outputStream().use {
                ks.store(it, keystorePassword)
            }
        }.onFailure { Log.e(TAG, "Failed to persist CA: ${it.message}") }
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
     * Password: "requestr-ca-local"
     */
    suspend fun exportP12(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val material = getOrCreate(context)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEYSTORE_ALIAS, material.keyPair.private, keystorePassword, arrayOf(material.certificate))
        val baos = java.io.ByteArrayOutputStream()
        ks.store(baos, keystorePassword)
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

    /** Delete the CA material and regenerate fresh. */
    suspend fun regenerate(context: Context) = withContext(Dispatchers.IO) {
        java.io.File(context.cacheDir, "requestr_ca.pem").delete()
        java.io.File(context.cacheDir, "requestr_ca_key.pkcs8").delete()
        getOrCreate(context)
    }
}
