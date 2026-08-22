package com.requestr.sdk

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust of requestr's local MITM CA in a third-party embedding app (task 9).
 *
 * requestr intercepts the *embedding app's own* outbound HTTPS traffic by presenting
 * leaf certificates signed by a locally-generated CA (see `CertificateAuthority.kt` in
 * the requestr app module). For the embedding app's own OkHttp/HttpURLConnection calls to
 * succeed against that CA (rather than fail with `SSLHandshakeException`), the app must
 * trust it one of two ways:
 *
 * ### Option A — Network Security Config (recommended, no code changes to trust logic)
 *
 * 1. Install requestr's CA as a user CA on the test device (Settings > Security > Install
 *    from storage, using the PEM exported from requestr Settings > "Export CA PEM" --
 *    see `CertificateAuthority.exportPem()`).
 * 2. Point the embedding app's manifest at a network security config that trusts user CAs
 *    in debug builds only:
 *
 *    ```xml
 *    <!-- res/xml/network_security_config.xml -->
 *    <network-security-config>
 *        <debug-overrides>
 *            <trust-anchors>
 *                <certificates src="user" />
 *            </trust-anchors>
 *        </debug-overrides>
 *    </network-security-config>
 *    ```
 *
 *    `<debug-overrides>` only takes effect when `android:debuggable="true"` (i.e. debug
 *    builds), so this never weakens a release build's trust story.
 *
 * A ready-to-copy sample lives at `sdk/src/main/res/xml/network_security_config.xml` in
 * this module (shipped as a documentation reference, not auto-merged into the host app's
 * manifest -- the host app must opt in explicitly by pointing its own
 * `android:networkSecurityConfig` attribute at it, or copying it into its own `res/xml`).
 *
 * ### Option B — Bundle requestr's CA cert directly (works without a debug build / user CA)
 *
 * Copy requestr's exported CA PEM into the embedding app's `res/raw/requestr_ca.pem`, then
 * reference it from a base config so it applies to release builds too:
 *
 * ```xml
 * <network-security-config>
 *     <base-config>
 *         <trust-anchors>
 *             <certificates src="system" />
 *             <certificates src="@raw/requestr_ca" />
 *         </trust-anchors>
 *     </base-config>
 * </network-security-config>
 * ```
 *
 * ### Option C — Programmatic trust manager (for apps that can't ship a manifest change)
 *
 * [createTrustManagerWithCa] builds an [X509TrustManager] that trusts requestr's CA (plus
 * the system trust store) so the host app can wire it into its own [SSLContext] /
 * `OkHttpClient.Builder.sslSocketFactory(...)` without touching `AndroidManifest.xml` at
 * all. This is the right call when the embedding app builds its OkHttpClient/HttpsURLConnection
 * pipeline programmatically and doesn't want a network security config at all.
 *
 * [allTrustingTrustManager] is provided as an explicit escape hatch for apps that truly
 * cannot modify their manifest or trust manager wiring (e.g. a third-party library that
 * constructs its own hidden `OkHttpClient`) -- it accepts every certificate, which is
 * intentionally **less secure** and should only be used in debug/QA builds, never shipped
 * to production.
 */
object TrustHelper {

    /**
     * Builds an [X509TrustManager] that trusts requestr's locally-generated CA (as PEM,
     * e.g. from requestr Settings > "Export CA PEM") *in addition to* the platform's
     * default system trust store, so it can be installed into an [SSLContext] the host
     * app controls without any manifest changes.
     *
     * @param caPem the CA certificate in PEM format, exactly as returned by
     *   `CertificateAuthority.exportPem()` on the requestr side (`-----BEGIN
     *   CERTIFICATE-----` ... `-----END CERTIFICATE-----`).
     * @throws IllegalArgumentException if [caPem] does not parse as a valid X.509
     *   certificate.
     */
    fun createTrustManagerWithCa(caPem: String): X509TrustManager {
        val caCert = parsePemCertificate(caPem)

        // Start from a KeyStore preloaded with the platform's default trust anchors (an
        // empty-password KeyStore load pulls in AndroidCAStore's system certs on Android),
        // then layer requestr's CA on top under its own alias so both are trusted.
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry(REQUESTR_CA_ALIAS, caCert)

        val trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)

        val trustManagers = trustManagerFactory.trustManagers
        return trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("No X509TrustManager produced by TrustManagerFactory")
    }

    /**
     * Returns an [X509TrustManager] that accepts every certificate chain unconditionally.
     *
     * Intentionally less secure than [createTrustManagerWithCa] -- use only when the host
     * app genuinely cannot install a scoped trust manager (e.g. no control over the
     * manifest or the `OkHttpClient`/`HttpsURLConnection` construction site) and only in
     * debug/QA builds. Never ship this in a production release.
     */
    fun allTrustingTrustManager(): X509TrustManager = AllTrustManager

    /**
     * Convenience: builds an [SSLContext] initialized with [createTrustManagerWithCa]'s
     * trust manager, ready to hand to `OkHttpClient.Builder.sslSocketFactory(...)` or
     * `HttpsURLConnection.setDefaultSSLSocketFactory(...)`.
     */
    fun createSslContextWithCa(caPem: String): SSLContext {
        val trustManager = createTrustManagerWithCa(caPem)
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
    }

    private fun parsePemCertificate(pem: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        val bytes = pem.trim().toByteArray(Charsets.US_ASCII)
        return factory.generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
            ?: throw IllegalArgumentException("PEM did not decode to an X509Certificate")
    }

    private const val REQUESTR_CA_ALIAS = "requestr-ca"

    /** See [allTrustingTrustManager] -- deliberately permissive, debug/QA use only. */
    private object AllTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
