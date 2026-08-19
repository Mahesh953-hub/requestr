package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Real MITM proxy with per-host TLS certificate generation.
 *
 * Uses the existing [CertificateAuthority] root CA to dynamically issue
 * certificates for each host the client visits. TLS is terminated on the
 * client side (so requestr sees plaintext) and re-established upstream
 * (so the real server accepts the connection).
 *
 * Architecture:
 *   Client → TLS(per-host-cert) → requestr inspects plaintext → TLS(real) → Server
 */
class MitmProxyEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val packetCount = AtomicLong(0)
    private val sessionId = AtomicLong(System.currentTimeMillis())
    private val certCache = ConcurrentHashMap<String, Pair<X509Certificate, KeyPair>>()

    val port: Int get() = serverSocket?.localPort ?: 0

    companion object {
        private const val TAG = "MitmProxy"
        private const val BUFFER_SIZE = 8192
    }

    fun start(context: Context, port: Int = 0): Int {
        if (running.get()) return this.port

        // Pre-warm cert cache with CA material
        kotlinx.coroutines.runBlocking {
            CertificateAuthority.getOrCreate(context)
        }

        serverSocket = ServerSocket(port)
        running.set(true)

        InterceptionStateRepository.markActive(
            sessionId = sessionId.get(), tier = ActiveTier.VPN,
            port = serverSocket!!.localPort, method = CaptureMethod.MITM_PROXY,
        )

        acceptJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(context, client) }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Accept: ${e.message}")
                }
            }
        }
        return serverSocket!!.localPort
    }

    fun stop() {
        running.set(false)
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "MITM proxy stopped")
    }

    private suspend fun handleClient(context: Context, client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                return
            }

            val target = parts[1]
            val (host, port) = parseHostPort(target, 443)

            // Send 200 to client — we're about to MITM
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            // Get or create per-host certificate
            val (cert, keyPair) = certCache.computeIfAbsent(host) {
                generateHostCert(context, host)
            }

            // Upgrade client side to TLS with our per-host cert
            val sslContext = createSslContext(keyPair, cert)
            val sslSocket = sslContext.socketFactory
                .createSocket(client, host, port, true) as SSLSocket
            sslSocket.useClientMode = false
            sslSocket.startHandshake()

            val decryptedInput = sslSocket.getInputStream()
            val decryptedOutput = sslSocket.getOutputStream()

            // Connect to real server
            val upstream = Socket(host, port)
            val upstreamSsl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(TrustAllCerts), SecureRandom())
            }.socketFactory.createSocket(upstream, host, port, true) as SSLSocket
            upstreamSsl.startHandshake()

            val upstreamInput = upstreamSsl.getInputStream()
            val upstreamOutput = upstreamSsl.getOutputStream()

            // Bidirectional relay
            val t1 = Thread { relay(decryptedInput, upstreamOutput, "→") }
            val t2 = Thread { relay(upstreamInput, decryptedOutput, "←") }
            t1.start(); t2.start()
            t1.join(60_000); t2.join(60_000)

            packetCount.addAndGet(1)
            InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Client handler: ${e.message}")
        }
    }

    // ── Per-host cert generation ────────────────────────────────────────

    private fun generateHostCert(context: Context, host: String): Pair<X509Certificate, KeyPair> {
        val ca = kotlinx.coroutines.runBlocking { CertificateAuthority.getOrCreate(context) }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            .genKeyPair()
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val now = Date()
        val expiry = Date(now.time + 365L * 24 * 60 * 60 * 1000)

        val issuer = X500Name("CN=requestr Root CA")
        val subject = X500Name("CN=$host")

        val builder = JcaX509v3CertificateBuilder(
            issuer, serial, now, expiry, subject, keyPair.public
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, host))))
        }

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(ca.keyPair.private)

        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))

        return cert to keyPair
    }

    // ── SSL context ─────────────────────────────────────────────────────

    private fun createSslContext(keyPair: KeyPair, cert: X509Certificate): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("mitm", keyPair.private, "".toCharArray(),
                arrayOf(cert, /* chain root not needed for server */ cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "".toCharArray())
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    // ── I/O helpers ─────────────────────────────────────────────────────

    private fun relay(input: InputStream, output: OutputStream, dir: String) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) { /* connection closed */ }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return sb.toString().ifEmpty { null }
            if (b == '\n'.code && prev == '\r'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colon = target.lastIndexOf(':')
        return if (colon > 0)
            target.substring(0, colon) to (target.substring(colon + 1).toIntOrNull() ?: defaultPort)
        else target to defaultPort
    }

    private object TrustAllCerts : X509TrustManager {
        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
