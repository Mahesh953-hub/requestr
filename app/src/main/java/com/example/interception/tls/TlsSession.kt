package com.example.interception.tls

/**
 * TLS session model for MITM-decrypted connections.
 * Tracks the full TLS lifecycle including handshake, cipher negotiation, and certificate chain.
 */
data class TlsSession(
    val sessionId: String,
    val connectionId: String,
    val sniHost: String?,
    val clientRandom: String,
    val serverRandom: String,
    val version: String,
    val cipherSuite: String,
    val negotiatedProtocol: String?,   // h2, http/1.1, none
    val resumed: Boolean = false,
    val establishedAt: Long = System.currentTimeMillis(),
    val certificates: List<TlsCertificate> = emptyList(),
    val handshakeDurationMs: Long = 0,
) {
    val isComplete: Boolean get() = state == TlsHandshakeState.ESTABLISHED
    var state: TlsHandshakeState = TlsHandshakeState.CLIENT_HELLO
}

enum class TlsHandshakeState {
    CLIENT_HELLO, SERVER_HELLO, CERTIFICATE, KEY_EXCHANGE,
    CHANGE_CIPHER_SPEC, FINISHED, ESTABLISHED, FAILED
}

/** TLS certificate chain element */
data class TlsCertificate(
    val subject: String,
    val issuer: String,
    val serial: String,
    val notBefore: Long,
    val notAfter: Long,
    val keyType: String,       // RSA 2048, ECDSA P-256, etc.
    val signatureAlgorithm: String,
    val isSelfSigned: Boolean = false,
    val isCA: Boolean = false,
    val isTrusted: Boolean = false,
)

/** Raw TLS record observed on the wire */
data class TlsRecord(
    val contentType: Int,      // 20=change_cipher, 21=alert, 22=handshake, 23=application
    val versionMajor: Int,
    val versionMinor: Int,
    val length: Int,
    val fragment: ByteArray,
) {
    val contentTypeName get() = when (contentType) {
        20 -> "change_cipher_spec"; 21 -> "alert"; 22 -> "handshake"; 23 -> "application_data"
        else -> "unknown($contentType)"
    }
}

/**
 * A parsed TLS alert record (RFC 8446 §6 / RFC 5246 §7.2). [isFatal] mirrors [level] == 2
 * (fatal); a fatal alert always ends the connection. TLS 1.3 encrypts alerts once handshake
 * keys are established, so this can only ever be parsed from the pre-key, plaintext wire —
 * post-key failures surface through the MITM leg's [javax.net.ssl.SSLHandshakeException] path
 * or an observed RST/close instead ([TlsDecoder]'s class doc covers this constraint).
 */
data class TlsAlert(
    val level: Int,
    val description: Int,
    val levelName: String,
    val descriptionName: String,
    val isFatal: Boolean,
)

/** ClientHello metadata for fingerprinting */
data class TlsClientHello(
    val version: String,
    val cipherSuites: List<Int>,
    val compressionMethods: List<Int>,
    val extensions: List<Int>,
    val supportedGroups: List<Int>,
    val signatureAlgorithms: List<Int>,
    val alpn: String?,
    val sni: String?,
    /**
     * The ClientHello's 32-byte `random` (RFC 8446 §4.1.2), retained because it is the key a TLS key
     * log is indexed by: `CLIENT_HANDSHAKE_TRAFFIC_SECRET <client_random> <secret>` and friends. It
     * is what lets [TlsKeyLogRegistry.lookup] resolve the traffic secrets for the connection this
     * ClientHello opened — without it, a registry full of real secrets cannot be matched to the
     * connection observing them. Null only when the buffer was too short to contain it.
     *
     * Deliberately excluded from [ja3Hash]: JA3 fingerprints the *offered capabilities*, which are
     * stable per client build, while the random is per-connection by definition.
     */
    val clientRandom: ByteArray? = null,
) {
    /** Compute JA3 fingerprint hash */
    fun ja3Hash(): String {
        val ciphers = cipherSuites.joinToString("-")
        val exts = extensions.joinToString("-")
        val groups = supportedGroups.joinToString("-")
        val sigAlgs = signatureAlgorithms.joinToString("-")
        return "$version,$ciphers,$exts,$groups,$sigAlgs".md5()
    }

    private fun String.md5(): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(this.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
