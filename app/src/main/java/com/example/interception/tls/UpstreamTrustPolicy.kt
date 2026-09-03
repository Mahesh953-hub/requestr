package com.example.interception.tls

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * S1 task 8: how the upstream (real-server) leg of a MITM'd connection treats the server's
 * certificate chain. Replaces the fixed, silent `AcceptAllTrustManager`.
 *
 * - [AcceptAllRecording] (default): the engine, not the original client, decides trust -- so the
 *   chain is accepted, but the outcome of validating it against the platform trust store is
 *   *recorded* and handed to [onOutcome], so diagnostics can tell the user "the real server's
 *   certificate would not have validated" instead of hiding it.
 * - [Validate] (opt-in): validate against the platform trust store and fail the upstream
 *   handshake with a real [CertificateException] when it does not validate.
 */
sealed class UpstreamTrustPolicy {
    /** Result of validating the upstream chain against the platform trust store. */
    data class Outcome(val host: String, val trusted: Boolean, val reason: String?)

    abstract fun trustManager(host: String): X509TrustManager

    class AcceptAllRecording(private val onOutcome: (Outcome) -> Unit = { lastOutcome.set(it) }) : UpstreamTrustPolicy() {
        override fun trustManager(host: String): X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {
                val real = chain?.filterNotNull()?.toTypedArray() ?: emptyArray()
                onOutcome(validateAgainstPlatform(host, real, authType))
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    class Validate(private val onOutcome: (Outcome) -> Unit = { lastOutcome.set(it) }) : UpstreamTrustPolicy() {
        override fun trustManager(host: String): X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {
                val real = chain?.filterNotNull()?.toTypedArray() ?: emptyArray()
                val outcome = validateAgainstPlatform(host, real, authType)
                onOutcome(outcome)
                if (!outcome.trusted) throw CertificateException("upstream $host: ${outcome.reason}")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = platformTrustManager()?.acceptedIssuers ?: arrayOf()
        }
    }

    companion object {
        /** Most recent recorded outcome (any host) -- a cheap hook for diagnostics surfaces. */
        val lastOutcome = AtomicReference<Outcome?>(null)

        private val platform: X509TrustManager? by lazy {
            runCatching {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            }.getOrNull()
        }

        fun platformTrustManager(): X509TrustManager? = platform

        internal fun validateAgainstPlatform(host: String, chain: Array<X509Certificate>, authType: String?): Outcome {
            if (chain.isEmpty()) return Outcome(host, false, "server presented no certificate")
            val tm = platform ?: return Outcome(host, false, "platform trust store unavailable")
            return try {
                tm.checkServerTrusted(chain, authType ?: "RSA")
                Outcome(host, true, null)
            } catch (e: CertificateException) {
                Outcome(host, false, e.message ?: e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                Outcome(host, false, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
