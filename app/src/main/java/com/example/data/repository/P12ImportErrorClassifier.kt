package com.example.data.repository

/**
 * Master plan §3 LOW-ADD — `.p12` import UI support.
 *
 * [com.example.interception.CaP12Exchange.importP12] is complete and unit-tested, but it
 * surfaces failure as a single [Result.failure] with a free-text exception message — there is
 * no structured error type to switch on. Rather than showing one generic "Import failed" toast
 * for every distinct failure mode, this classifies the *known* message shapes
 * [com.example.interception.CaP12Exchange.parse] actually produces (see its `ParseResult.Failure`
 * branches) into a small closed set of honest, user-facing categories.
 *
 * This is intentionally conservative: an exception message this classifier has never seen still
 * gets a real (if generic) [P12ImportFailureKind.UNKNOWN] bucket that shows the raw message
 * rather than being silently mis-labelled as one of the specific categories.
 */
enum class P12ImportFailureKind {
    /** The PKCS#12 bundle could not be decrypted with the supplied password. */
    WRONG_PASSWORD,

    /** The bundle opened fine but doesn't contain something importable as a CA (no private
     *  key, no certificate, or a certificate that isn't itself a CA). */
    UNSUPPORTED_CONTENT,

    /** The bundle could not even be opened as a PKCS#12 keystore — not a `.p12`/`.pfx` file, or
     *  its container is damaged. */
    CORRUPT_FILE,

    /** Parsing succeeded, but writing the imported CA to this app's storage failed. */
    PERSIST_FAILED,

    /** A failure message this classifier doesn't recognise; the raw message is still shown. */
    UNKNOWN
}

object P12ImportErrorClassifier {

    private val passwordHints = listOf(
        "password", "mac ", "mac check", "integrity check", "bad padding", "baddecrypt", "decrypt"
    )
    private val unsupportedContentHints = listOf(
        "no private-key entry",
        "is not a usable private key",
        "no x.509 certificate",
        "is not a ca certificate",
    )
    private val persistHints = listOf("failed to persist")

    /** Maps a [com.example.interception.CaP12Exchange.importP12] failure message to a [P12ImportFailureKind]. */
    fun classify(message: String?): P12ImportFailureKind {
        val lower = message?.lowercase().orEmpty()
        if (lower.isBlank()) return P12ImportFailureKind.UNKNOWN
        return when {
            unsupportedContentHints.any { lower.contains(it) } -> P12ImportFailureKind.UNSUPPORTED_CONTENT
            persistHints.any { lower.contains(it) } -> P12ImportFailureKind.PERSIST_FAILED
            passwordHints.any { lower.contains(it) } -> P12ImportFailureKind.WRONG_PASSWORD
            lower.contains("could not open pkcs#12 bundle") || lower.contains("keystore") ->
                P12ImportFailureKind.CORRUPT_FILE
            else -> P12ImportFailureKind.UNKNOWN
        }
    }

    /** A short, honest, user-facing sentence for [kind]; never hides [rawMessage] for [UNKNOWN]. */
    fun userMessage(kind: P12ImportFailureKind, rawMessage: String?): String = when (kind) {
        P12ImportFailureKind.WRONG_PASSWORD ->
            "Wrong password — that .p12 file couldn't be decrypted with the password you entered."
        P12ImportFailureKind.UNSUPPORTED_CONTENT ->
            "Unsupported content — this file doesn't contain a CA certificate + private key requestr can import."
        P12ImportFailureKind.CORRUPT_FILE ->
            "Corrupt or unrecognised file — this doesn't look like a valid PKCS#12 (.p12) bundle."
        P12ImportFailureKind.PERSIST_FAILED ->
            "The file parsed correctly, but saving it as this app's CA failed. Try again."
        P12ImportFailureKind.UNKNOWN ->
            "Import failed: ${rawMessage?.takeIf { it.isNotBlank() } ?: "unknown error"}"
    }
}
