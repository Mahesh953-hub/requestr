package com.example.interception

import com.example.data.prefs.SecureKeyStore
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * §2.14(b) — per-URL AES request/response body encryption/decryption, driven by the
 * `BODY_ENCRYPT_AES`/`BODY_DECRYPT_AES` [com.example.interception.rules.LegacyRuleAction] values.
 *
 * Modeled directly on [SecureKeyStore] (same AndroidKeyStore-backed AES-256/GCM key material,
 * via [SecureKeyStore.getOrCreateAesGcmKey]) rather than inventing a second key-management
 * scheme, but operates on raw [ByteArray] bodies end-to-end -- consistent with the
 * [HttpProxyShared] contract established by the cleartext-HTTP proxy fix (request/response
 * bodies are `ByteArray`, never round-tripped through `String`, so binary bodies survive).
 *
 * Wire format for the ciphertext this produces (and expects on decrypt): `iv (12 bytes) ||
 * ciphertext-with-GCM-tag`. A fresh random 12-byte IV is generated per [encrypt] call -- GCM's
 * security guarantee depends entirely on never reusing an IV under the same key, so a rule that
 * encrypts many transactions with the same [keyAlias] still gets a fresh IV every single time.
 *
 * A decrypt failure -- wrong key, corrupted/truncated ciphertext, or a payload that was never
 * actually AES/GCM-encrypted by this class -- is always a [CryptoResult.Failure] with a
 * diagnosable [CryptoResult.Failure.reason], never a silent pass-through of the raw ciphertext
 * as if it were plaintext. Callers (see [RuleEnforcer]) must surface that failure visibly (e.g.
 * as a `failureReason`), exactly like [SecureKeyStore.SecretReadResult.Unreadable].
 */
object RequestCryptoManager {

    private const val DEFAULT_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    sealed interface CryptoResult {
        data class Success(val bytes: ByteArray) : CryptoResult
        data class Failure(val reason: String) : CryptoResult
    }

    /** Encrypts [plaintext] under a fresh random IV; returns `iv || ciphertext+tag`. */
    fun encrypt(
        keyAlias: String,
        plaintext: ByteArray,
        transformation: String = DEFAULT_TRANSFORMATION,
    ): CryptoResult = encryptWith(keyAlias, plaintext, transformation, SecureKeyStore::getOrCreateAesGcmKey)

    /** Decrypts a `iv || ciphertext+tag` payload produced by [encrypt] under the same [keyAlias]. */
    fun decrypt(
        keyAlias: String,
        ciphertextWithIv: ByteArray,
        transformation: String = DEFAULT_TRANSFORMATION,
    ): CryptoResult = decryptWith(keyAlias, ciphertextWithIv, transformation, SecureKeyStore::getOrCreateAesGcmKey)

    /**
     * Test seam: identical to [encrypt] but takes an injectable [keyProvider] instead of the
     * real [SecureKeyStore.getOrCreateAesGcmKey] -- `AndroidKeyStore` isn't available under
     * Robolectric/plain-JVM tests (see `SecureKeyStoreTest`'s documented boundary), so tests use
     * a plain (non-Keystore) `SecretKey` generator here to exercise the actual cipher/framing
     * logic deterministically.
     */
    internal fun encryptWith(
        keyAlias: String,
        plaintext: ByteArray,
        transformation: String,
        keyProvider: (String) -> SecretKey,
    ): CryptoResult {
        if (keyAlias.isBlank()) return CryptoResult.Failure("encrypt failed: no key alias configured for this rule")
        return try {
            val key = keyProvider(keyAlias)
            val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)
            CryptoResult.Success(iv + ciphertext)
        } catch (e: GeneralSecurityException) {
            CryptoResult.Failure("encrypt failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Test seam counterpart to [decrypt] -- see [encryptWith]'s KDoc. */
    internal fun decryptWith(
        keyAlias: String,
        ciphertextWithIv: ByteArray,
        transformation: String,
        keyProvider: (String) -> SecretKey,
    ): CryptoResult {
        if (keyAlias.isBlank()) return CryptoResult.Failure("decrypt failed: no key alias configured for this rule")
        if (ciphertextWithIv.size <= GCM_IV_LENGTH_BYTES) {
            return CryptoResult.Failure("decrypt failed: payload too short to contain an IV + GCM tag")
        }
        return try {
            val iv = ciphertextWithIv.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = ciphertextWithIv.copyOfRange(GCM_IV_LENGTH_BYTES, ciphertextWithIv.size)
            val key = keyProvider(keyAlias)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            CryptoResult.Success(cipher.doFinal(ciphertext))
        } catch (e: AEADBadTagException) {
            CryptoResult.Failure("decrypt failed: authentication tag mismatch (wrong key or corrupted data)")
        } catch (e: GeneralSecurityException) {
            CryptoResult.Failure("decrypt failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
