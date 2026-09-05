package com.example.interception.rules.stages

import android.util.Log
import com.example.data.prefs.SecureKeyStore
import com.example.interception.RequestCryptoManager
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BodyCryptoConfig
import com.example.interception.rules.CryptoDirection
import com.example.interception.rules.CryptoScope
import com.example.interception.rules.RuleMetadataHeaders
import javax.crypto.SecretKey

/**
 * S4 task 26/29: the BODY_CRYPTO slot -- AES-GCM encrypt/decrypt of a message body in place,
 * **fail-closed**: on any failure the caller must not forward the untransformed message and
 * instead answers with [failureResponse] (a 502 stamped `x-requestr-crypto-failed: true`).
 *
 * Byte-level parity with the legacy `RuleEnforcer.applyBodyCryptoWith` (G16): the cipher runs
 * over the *decoded* body, output is sent identity-encoded, a body whose declared
 * Content-Encoding could not be undone on this device is refused, and on success
 * `replacedBodyBytes`/`bodyReplaced`/`isBodyBinary`/`body` describe the bytes actually sent.
 */
object BodyCryptoStage {
    private const val TAG = "BodyCryptoStage"

    sealed interface Result {
        data object NotApplicable : Result
        data object Applied : Result
        data class Failed(val reason: String) : Result
    }

    fun apply(message: ParsedHttpMessage, config: BodyCryptoConfig, ruleName: String): Result =
        applyWith(message, config, ruleName, SecureKeyStore::getOrCreateAesGcmKey)

    /** Test seam: injectable key provider, because `AndroidKeyStore` is unavailable under Robolectric. */
    fun applyWith(
        message: ParsedHttpMessage,
        config: BodyCryptoConfig,
        ruleName: String,
        keyProvider: (String) -> SecretKey,
    ): Result {
        val appliesToThisMessage = when (config.scope) {
            CryptoScope.REQUEST -> message.isRequest
            CryptoScope.RESPONSE -> !message.isRequest
            CryptoScope.BOTH -> true
        }
        if (!appliesToThisMessage) return Result.NotApplicable

        val encrypt = config.direction == CryptoDirection.ENCRYPT
        val op = if (encrypt) "BODY_ENCRYPT_AES" else "BODY_DECRYPT_AES"
        val residualEncoding = HttpBodyDecoder.residualContentEncoding(message)
        if (residualEncoding != null) {
            val reason = "$op refused: body is Content-Encoding: $residualEncoding, which this device cannot decode; " +
                "the transformed body would carry no honest record of that encoding"
            Log.w(TAG, "$op rule '$ruleName' $reason")
            return Result.Failed(reason)
        }
        val inputBytes = message.decodedBodyBytes ?: message.rawBodyBytes ?: message.body.toByteArray(Charsets.UTF_8)
        // Fail closed on *anything* the key provider or cipher throws (a locked/absent
        // AndroidKeyStore surfaces as runtime exceptions, not just GeneralSecurityException):
        // a crypto rule must never let the untransformed body through by accident.
        val result = try {
            if (encrypt) {
                RequestCryptoManager.encryptWith(config.keyAlias, inputBytes, config.algorithm, keyProvider)
            } else {
                RequestCryptoManager.decryptWith(config.keyAlias, inputBytes, config.algorithm, keyProvider)
            }
        } catch (e: Exception) {
            RequestCryptoManager.CryptoResult.Failure("$op failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return when (result) {
            is RequestCryptoManager.CryptoResult.Success -> {
                message.replacedBodyBytes = result.bytes
                message.bodyReplaced = true
                val isBinary = !HttpBodyDecoder.isProbablyUtf8(result.bytes)
                message.isBodyBinary = isBinary
                message.body = if (isBinary) HttpBodyDecoder.BINARY_BODY_PLACEHOLDER else String(result.bytes, Charsets.UTF_8)
                Result.Applied
            }
            is RequestCryptoManager.CryptoResult.Failure -> {
                Log.w(TAG, "$op rule '$ruleName' failed, blocking rather than forwarding untransformed: ${result.reason}")
                Result.Failed(result.reason)
            }
        }
    }

    /** The synthetic 502 that replaces a message whose crypto rule failed. Parity with legacy. */
    fun failureResponse(ruleName: String, direction: CryptoDirection, reason: String): ParsedHttpMessage {
        val op = if (direction == CryptoDirection.ENCRYPT) "encrypt" else "decrypt"
        val body = "requestr crypto rule '$ruleName' failed to $op this body and was blocked: $reason"
        val headers = linkedMapOf(
            "content-type" to "text/plain; charset=utf-8",
            "content-length" to body.toByteArray(Charsets.UTF_8).size.toString(),
            RuleMetadataHeaders.CRYPTO_FAILED to "true",
        )
        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            statusCode = 502,
            statusMessage = "Crypto rule failed (requestr)",
            headers = headers,
            body = body,
            bodyReplaced = true,
        )
    }
}
