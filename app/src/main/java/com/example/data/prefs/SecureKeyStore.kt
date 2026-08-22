package com.example.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/**
 * Task 12 — Keystore-backed storage for the chat provider's API key. Only the key ever goes
 * through here; `name`/`baseUrl`/`model`/`isActive`/`createdAt` live in the plain `chat_providers`
 * Room table (task 11) because they aren't secrets.
 *
 * **Storage mechanism — decided: raw `AndroidKeyStore`, zero new dependencies.** The plan
 * originally named `EncryptedSharedPreferences`, but `androidx.security:security-crypto` (and
 * that class) is deprecated upstream with no 1:1 official replacement, and `tink-android` is a
 * new dependency for no benefit at this app's scale. `minSdk = 24` is above the API 23 floor for
 * `KeyGenParameterSpec` + `AndroidKeyStore` AES/GCM, so this uses only `java.security.KeyStore`
 * and `javax.crypto` — nothing new in `gradle/libs.versions.toml`.
 *
 * The AES-256 key itself never leaves the `AndroidKeyStore` (hardware-backed where available) —
 * only `iv + ciphertext`, Base64-encoded, is ever persisted, and that goes into the existing
 * unified DataStore under a per-alias key so there is still only one DataStore file for the app
 * (see [UnifiedDataStore]'s own "there must only ever be ONE DataStore instance per file" note).
 *
 * Three non-negotiables, per the plan:
 *  1. A fresh random IV every encryption — [Cipher.getIV] after `ENCRYPT_MODE` init, never reused.
 *  2. A GCM auth-tag failure on decrypt ([AEADBadTagException]) surfaces to the caller as
 *     "key unreadable, re-enter it" via [SecretReadResult.Unreadable] — never a silent empty
 *     string that would make the app look configured when it isn't.
 *  3. The plaintext secret is never passed to `Log` at any level, in this file or its callers.
 */
object SecureKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    /** Result of reading back a secret — distinguishes "never written" from "unreadable". */
    sealed interface SecretReadResult {
        data class Found(val value: String) : SecretReadResult
        object Absent : SecretReadResult
        /** The GCM auth tag didn't verify — the stored ciphertext is corrupt or the Keystore
         *  key was invalidated (e.g. device credentials changed). The caller must ask the user
         *  to re-enter the secret rather than silently treating this as "not configured". */
        object Unreadable : SecretReadResult
    }

    private fun dataStoreKey(alias: String) = stringPreferencesKey("secure_key_$alias")

    /** Generates (first use) or fetches the AES-256 Keystore key for [alias]. */
    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Encrypts [value] under a fresh random IV and persists `iv + ciphertext` for [alias]. */
    suspend fun putSecret(context: Context, alias: String, value: String) {
        val secretKey = getOrCreateSecretKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv // fresh per call -- Cipher.init(ENCRYPT_MODE, key) generates a new one
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)

        context.applicationContext.unifiedDataStore.edit { prefs ->
            prefs[dataStoreKey(alias)] = encoded
        }
    }

    /** Decrypts and returns the secret for [alias], distinguishing absent from unreadable. */
    suspend fun getSecret(context: Context, alias: String): SecretReadResult {
        val encoded = context.applicationContext.unifiedDataStore.data.first()[dataStoreKey(alias)]
            ?: return SecretReadResult.Absent

        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            // GCM IV is always 12 bytes (96 bits) for the standard AndroidKeyStore-generated key.
            val ivLength = 12
            if (payload.size <= ivLength) return SecretReadResult.Unreadable
            val iv = payload.copyOfRange(0, ivLength)
            val ciphertext = payload.copyOfRange(ivLength, payload.size)

            val secretKey = getOrCreateSecretKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            SecretReadResult.Found(String(plaintext, Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            // Auth tag didn't verify -- corrupted ciphertext or an invalidated Keystore key.
            // Never surface this as an empty string; the caller must prompt for re-entry.
            SecretReadResult.Unreadable
        } catch (e: java.security.GeneralSecurityException) {
            SecretReadResult.Unreadable
        }
    }

    /** Deletes the persisted ciphertext for [alias]. Does not remove the Keystore key itself
     *  (harmless to leave behind; a fresh [putSecret] for a reused alias just re-encrypts). */
    suspend fun removeSecret(context: Context, alias: String) {
        context.applicationContext.unifiedDataStore.edit { prefs ->
            prefs.remove(dataStoreKey(alias))
        }
    }
}
