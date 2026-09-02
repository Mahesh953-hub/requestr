package com.example.interception

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [RequestCryptoManager]'s encrypt/decrypt framing and failure modes via the injectable
 * [RequestCryptoManager.encryptWith]/[decryptWith] test seams -- `AndroidKeyStore` isn't
 * available under plain-JVM/Robolectric tests (see `SecureKeyStoreTest`'s documented boundary),
 * so these use plain (non-Keystore) `SecretKey`s keyed by alias, exercising the real cipher and
 * wire-framing logic [encrypt]/[decrypt] delegate to.
 */
class RequestCryptoManagerTest {

    private fun keyFor(alias: String): SecretKey {
        // Deterministic per-alias key (seeded from the alias string) so "same alias" always
        // reuses the same key within a test and "different alias" is guaranteed to differ --
        // standing in for AndroidKeyStore's real per-alias key persistence.
        val seed = alias.hashCode()
        val random = java.util.Random(seed.toLong())
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }

    @Test
    fun `round-trips plaintext through encrypt then decrypt with the same alias`() {
        val plaintext = "hello requestr".toByteArray(Charsets.UTF_8)
        val encrypted = RequestCryptoManager.encryptWith("alias-a", plaintext, "AES/GCM/NoPadding", ::keyFor)
        val ciphertext = (encrypted as RequestCryptoManager.CryptoResult.Success).bytes

        val decrypted = RequestCryptoManager.decryptWith("alias-a", ciphertext, "AES/GCM/NoPadding", ::keyFor)
        assertTrue(decrypted is RequestCryptoManager.CryptoResult.Success)
        assertArrayEquals(plaintext, (decrypted as RequestCryptoManager.CryptoResult.Success).bytes)
    }

    @Test
    fun `round-trips binary (non-UTF8) bodies losslessly`() {
        val binary = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x7F, 0x80.toByte(), 0x10, 0x00)
        val encrypted = RequestCryptoManager.encryptWith("alias-bin", binary, "AES/GCM/NoPadding", ::keyFor)
        val ciphertext = (encrypted as RequestCryptoManager.CryptoResult.Success).bytes

        val decrypted = RequestCryptoManager.decryptWith("alias-bin", ciphertext, "AES/GCM/NoPadding", ::keyFor)
        assertArrayEquals(binary, (decrypted as RequestCryptoManager.CryptoResult.Success).bytes)
    }

    @Test
    fun `two encryptions of the same plaintext under the same alias use different IVs`() {
        val plaintext = "same plaintext every time".toByteArray(Charsets.UTF_8)
        val first = (RequestCryptoManager.encryptWith("alias-iv", plaintext, "AES/GCM/NoPadding", ::keyFor)
            as RequestCryptoManager.CryptoResult.Success).bytes
        val second = (RequestCryptoManager.encryptWith("alias-iv", plaintext, "AES/GCM/NoPadding", ::keyFor)
            as RequestCryptoManager.CryptoResult.Success).bytes

        val firstIv = first.copyOfRange(0, 12)
        val secondIv = second.copyOfRange(0, 12)
        assertTrue("IVs must differ across encryptions", !firstIv.contentEquals(secondIv))
        // Different IV -> different ciphertext bytes even for identical plaintext + key.
        assertTrue(!first.contentEquals(second))
    }

    @Test
    fun `decrypting with the wrong key alias fails loudly rather than returning garbage as success`() {
        val plaintext = "secret payload".toByteArray(Charsets.UTF_8)
        val ciphertext = (RequestCryptoManager.encryptWith("alias-right", plaintext, "AES/GCM/NoPadding", ::keyFor)
            as RequestCryptoManager.CryptoResult.Success).bytes

        val result = RequestCryptoManager.decryptWith("alias-wrong", ciphertext, "AES/GCM/NoPadding", ::keyFor)
        assertTrue(result is RequestCryptoManager.CryptoResult.Failure)
        val reason = (result as RequestCryptoManager.CryptoResult.Failure).reason
        assertTrue(reason.contains("authentication tag mismatch"))
    }

    @Test
    fun `decrypting corrupted ciphertext fails loudly`() {
        val plaintext = "another secret".toByteArray(Charsets.UTF_8)
        val ciphertext = (RequestCryptoManager.encryptWith("alias-corrupt", plaintext, "AES/GCM/NoPadding", ::keyFor)
            as RequestCryptoManager.CryptoResult.Success).bytes
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()

        val result = RequestCryptoManager.decryptWith("alias-corrupt", ciphertext, "AES/GCM/NoPadding", ::keyFor)
        assertTrue(result is RequestCryptoManager.CryptoResult.Failure)
    }

    @Test
    fun `decrypting a too-short payload fails loudly instead of throwing`() {
        val result = RequestCryptoManager.decryptWith("alias-short", byteArrayOf(1, 2, 3), "AES/GCM/NoPadding", ::keyFor)
        assertTrue(result is RequestCryptoManager.CryptoResult.Failure)
    }

    @Test
    fun `blank key alias fails loudly on both encrypt and decrypt`() {
        val encryptResult = RequestCryptoManager.encryptWith("", "x".toByteArray(), "AES/GCM/NoPadding", ::keyFor)
        assertTrue(encryptResult is RequestCryptoManager.CryptoResult.Failure)

        val decryptResult = RequestCryptoManager.decryptWith("", ByteArray(20), "AES/GCM/NoPadding", ::keyFor)
        assertTrue(decryptResult is RequestCryptoManager.CryptoResult.Failure)
    }

    @Test
    fun `unavailable key fails loudly on encrypt rather than falling back to plaintext`() {
        // Stands in for an AndroidKeyStore alias that cannot be resolved/created (locked
        // keystore, hardware-backed key deleted out from under the app, etc.) -- the keyProvider
        // itself throws instead of returning a SecretKey.
        val unavailableKey: (String) -> SecretKey = { throw java.security.KeyStoreException("key unavailable") }
        val result = RequestCryptoManager.encryptWith("alias-missing", "payload".toByteArray(), "AES/GCM/NoPadding", unavailableKey)
        assertTrue(result is RequestCryptoManager.CryptoResult.Failure)
    }

    @Test
    fun `unavailable key fails loudly on decrypt rather than returning garbage or the ciphertext untouched`() {
        val unavailableKey: (String) -> SecretKey = { throw java.security.KeyStoreException("key unavailable") }
        val result = RequestCryptoManager.decryptWith("alias-missing", ByteArray(28), "AES/GCM/NoPadding", unavailableKey)
        assertTrue(result is RequestCryptoManager.CryptoResult.Failure)
    }

    @Test
    fun `public encrypt-decrypt API delegates to the real SecureKeyStore-backed key provider`() {
        // Not a round-trip (AndroidKeyStore isn't available in this test JVM -- see class kdoc),
        // just confirms the public entry points exist with the documented signature and fail
        // the same way encryptWith/decryptWith do for a blank alias, without needing Keystore.
        val result = RequestCryptoManager.encrypt("", "x".toByteArray())
        assertTrue(result is RequestCryptoManager.CryptoResult.Failure)
    }
}
