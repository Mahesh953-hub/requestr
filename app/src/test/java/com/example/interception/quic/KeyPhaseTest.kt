package com.example.interception.quic

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies [QuicCrypto.KeyPhaseManager] — the RFC 9001 §6 key-phase rotation with prev/current/next
 * generations, auth-before-promotion, and bounded retirement of previous generations. Covers the
 * three behaviours the gap requires: a key rotation leaves the old phase still able to decrypt
 * delayed packets while the new phase encrypts + decrypts; two rotations wrap the KEY_PHASE bit
 * back to its starting value while using distinct keys per generation; and a spoofed key-phase flip
 * (a bit that does not authenticate against the speculative next generation) does NOT promote and
 * does NOT corrupt the current generation.
 */
class KeyPhaseTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    /** RFC 9001 Appendix A.1's published client_initial_secret — a reproducible 32-byte traffic secret. */
    private val initialSecret = hex("c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
    private val suite = QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256

    /** nonce = iv XOR packet number, right-aligned over the 12-byte IV (RFC 9001 §5.3). */
    private fun nonceFor(keys: QuicCrypto.PacketProtectionKeys, pn: Long): ByteArray {
        val nonce = keys.iv.copyOf()
        for (i in 0 until 8) {
            nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor ((pn ushr (i * 8)) and 0xFF).toInt()).toByte()
        }
        return nonce
    }

    /** AES-128-GCM encrypt (plaintext -> ciphertext + 16-byte tag), the protect side of a round-trip. */
    private fun protect(keys: QuicCrypto.PacketProtectionKeys, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    @Test
    fun `initial key phase is generation 0 with KEY_PHASE bit 0 and round-trips`() {
        val mgr = QuicCrypto.KeyPhaseManager(QuicCrypto.VERSION_1, suite, initialSecret)
        assertEquals(0, mgr.generation)
        assertEquals(0, mgr.currentKeyPhaseBit)

        val keys = mgr.currentProtectKeys()
        val pn = 0L
        val nonce = nonceFor(keys, pn)
        val plaintext = "phase-zero".toByteArray(Charsets.US_ASCII)
        val ciphertext = protect(keys, nonce, plaintext)

        val result = mgr.attemptUnprotect(keyPhaseBit = 0, nonce = nonce, aad = ByteArray(0), ciphertext = ciphertext)
        assertNotNull(result)
        assertArrayEquals(plaintext, result!!.plaintext)
        assertEquals(0, result.generation)
        assertFalse("current-bit packet must not promote", result.promoted)
        assertEquals("no promotion occurred", 0, mgr.generation)
    }

    @Test
    fun `key rotation makes the new phase encrypt and decrypt while the old phase still decrypts`() {
        val mgr = QuicCrypto.KeyPhaseManager(QuicCrypto.VERSION_1, suite, initialSecret)
        val phase0Protect = mgr.currentProtectKeys()

        // Rotate (we initiate the key update): generation becomes 1, KEY_PHASE bit becomes 1.
        val phase1Protect = mgr.initiateKeyUpdate()
        assertEquals(1, mgr.generation)
        assertEquals(1, mgr.currentKeyPhaseBit)
        assertNotEquals("the new generation uses distinct protect keys", phase0Protect.key, phase1Protect.key)

        // New phase (gen 1, bit 1) encrypts + decrypts.
        val pn = 5L
        val nonce1 = nonceFor(phase1Protect, pn)
        val plaintext1 = "phase-one".toByteArray(Charsets.US_ASCII)
        val result1 = mgr.attemptUnprotect(keyPhaseBit = 1, nonce = nonce1, aad = ByteArray(0), ciphertext = protect(phase1Protect, nonce1, plaintext1))
        assertNotNull("new-phase packet should decrypt", result1)
        assertArrayEquals(plaintext1, result1!!.plaintext)
        assertEquals(1, result1.generation)

        // Old phase (gen 0, bit 0) still decrypts — a delayed packet encrypted under the retired keys.
        val nonce0 = nonceFor(phase0Protect, pn)
        val plaintext0 = "phase-zero-delayed".toByteArray(Charsets.US_ASCII)
        val result0 = mgr.attemptUnprotect(keyPhaseBit = 0, nonce = nonce0, aad = ByteArray(0), ciphertext = protect(phase0Protect, nonce0, plaintext0))
        assertNotNull("a delayed old-phase packet must still decrypt after rotation", result0)
        assertArrayEquals(plaintext0, result0!!.plaintext)
        assertEquals(0, result0.generation)
        assertFalse("an old-phase packet must not re-promote", result0.promoted)
        assertEquals("generation unchanged after old-phase decrypt", 1, mgr.generation)
    }

    @Test
    fun `two rotations wrap the KEY_PHASE bit back to 0 with distinct keys per generation`() {
        val mgr = QuicCrypto.KeyPhaseManager(QuicCrypto.VERSION_1, suite, initialSecret)
        val phase0 = mgr.currentProtectKeys()
        val phase1 = mgr.initiateKeyUpdate() // gen 1, bit 1
        val phase2 = mgr.initiateKeyUpdate() // gen 2, bit 0 (wrapped)

        assertEquals(2, mgr.generation)
        assertEquals("the KEY_PHASE bit wraps back to 0 after two updates", 0, mgr.currentKeyPhaseBit)
        // Three distinct key sets — the wrap is not a reuse of generation 0's keys.
        assertNotEquals(phase0.key, phase1.key)
        assertNotEquals(phase1.key, phase2.key)
        assertNotEquals(phase0.key, phase2.key)

        // Current generation (gen 2, bit 0) round-trips even though its bit equals generation 0's.
        val pn = 9L
        val nonce2 = nonceFor(phase2, pn)
        val plaintext2 = "phase-two".toByteArray(Charsets.US_ASCII)
        val result2 = mgr.attemptUnprotect(keyPhaseBit = 0, nonce = nonce2, aad = ByteArray(0), ciphertext = protect(phase2, nonce2, plaintext2))
        assertNotNull(result2)
        assertArrayEquals(plaintext2, result2!!.plaintext)
        assertEquals(2, result2.generation)

        // The immediately previous generation (gen 1, bit 1) is retained and still decrypts.
        val nonce1 = nonceFor(phase1, pn)
        val plaintext1 = "phase-one-delayed".toByteArray(Charsets.US_ASCII)
        val result1 = mgr.attemptUnprotect(keyPhaseBit = 1, nonce = nonce1, aad = ByteArray(0), ciphertext = protect(phase1, nonce1, plaintext1))
        assertNotNull("the retained previous generation still decrypts after a second rotation", result1)
        assertEquals(1, result1!!.generation)

        // Generation 0 has been retired (maxRetainedPrevious = 1): it is no longer retained and no
        // longer decrypts — bounded retirement, so a flood of updates can't grow the key set unboundedly.
        assertEquals(listOf(1, 2), mgr.retainedPhases().map { it.key })
        val nonce0 = nonceFor(phase0, pn)
        val result0 = mgr.attemptUnprotect(keyPhaseBit = 0, nonce = nonce0, aad = ByteArray(0), ciphertext = protect(phase0, nonce0, "retired".toByteArray()))
        assertNull("a retired (out-of-window) generation no longer decrypts", result0)
    }

    @Test
    fun `a spoofed key-phase flip does not promote and does not corrupt the current generation`() {
        // Auth-before-promotion (RFC 9001 §6): a short header whose KEY_PHASE bit signals a peer
        // update, but whose ciphertext does NOT authenticate against the speculative next generation,
        // must be rejected without promoting and without disturbing the current generation.
        val mgr = QuicCrypto.KeyPhaseManager(QuicCrypto.VERSION_1, suite, initialSecret)
        assertEquals(0, mgr.generation)
        val currentKeys = mgr.currentProtectKeys()

        // A ciphertext encrypted with UNRELATED keys (not gen 0, not the real speculative gen 1),
        // presented with the flipped bit (1) -- mimics a spoofed/corrupt key-phase flip.
        val bogusSecret = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val bogusKeys = QuicCrypto.deriveKeysFromTrafficSecret(QuicCrypto.VERSION_1, suite, bogusSecret)!!
        val pn = 1L
        val bogusNonce = nonceFor(bogusKeys, pn)
        val spoofed = protect(bogusKeys, bogusNonce, "spoofed-flip".toByteArray())

        val result = mgr.attemptUnprotect(keyPhaseBit = 1, nonce = bogusNonce, aad = ByteArray(0), ciphertext = spoofed)
        assertNull("a non-authenticating flip must not decrypt", result)
        assertEquals("no promotion on a failed speculative update", 0, mgr.generation)
        assertArrayEquals("current generation's protect keys are untouched", currentKeys.key, mgr.currentProtectKeys().key)

        // The current generation still works after the rejected flip — the spoof did not corrupt it.
        val nonce0 = nonceFor(currentKeys, pn)
        val plaintext = "still-works".toByteArray(Charsets.US_ASCII)
        val ok = mgr.attemptUnprotect(keyPhaseBit = 0, nonce = nonce0, aad = ByteArray(0), ciphertext = protect(currentKeys, nonce0, plaintext))
        assertNotNull("current generation must still decrypt after a rejected flip", ok)
        assertArrayEquals(plaintext, ok!!.plaintext)
    }

    @Test
    fun `a genuine peer-signaled key update authenticates and promotes`() {
        // The positive counterpart to the spoof test: a packet encrypted with the real next-generation
        // keys, presented with the flipped bit, authenticates and promotes the manager to generation 1.
        val mgr = QuicCrypto.KeyPhaseManager(QuicCrypto.VERSION_1, suite, initialSecret)
        val phase0 = mgr.currentProtectKeys()

        // Independently derive the real generation-1 keys (the peer's update) using the same RFC 9001
        // §6.1 next-secret derivation the manager uses internally, so the ciphertext authenticates
        // against the speculative next generation it stages.
        val secret1 = deriveNextSecretForTest(initialSecret)
        val realNext = QuicCrypto.deriveKeysFromTrafficSecret(QuicCrypto.VERSION_1, suite, secret1)!!

        val pn = 2L
        val nonce1 = nonceFor(realNext, pn)
        val plaintext = "peer-update".toByteArray(Charsets.US_ASCII)
        val result = mgr.attemptUnprotect(keyPhaseBit = 1, nonce = nonce1, aad = ByteArray(0), ciphertext = protect(realNext, nonce1, plaintext))
        assertNotNull("a genuine peer update should authenticate", result)
        assertTrue("a genuine peer update should promote", result!!.promoted)
        assertEquals(1, result.generation)
        assertEquals("manager advanced to generation 1", 1, mgr.generation)
        assertNotEquals(phase0.key, mgr.currentProtectKeys().key)
    }

    /**
     * Reproduces the manager's RFC 9001 §6.1 next-secret derivation (`HKDF-Expand-Label(secret,
     * "quic ku", "", 32)` over the suite's hash) so the "genuine peer update" test can build a
     * ciphertext that authenticates against the speculative next generation. Kept here rather than
     * exposed on [QuicCrypto.KeyPhaseManager] so the manager's promotion logic stays the unit under
     * test, not its key-derivation helper.
     */
    private fun deriveNextSecretForTest(secret: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val fullLabel = "tls13 quic ku".toByteArray(Charsets.US_ASCII)
        val info = ByteArray(2 + 1 + fullLabel.size + 1)
        info[0] = 0; info[1] = 32 // length = 32
        info[2] = fullLabel.size.toByte()
        System.arraycopy(fullLabel, 0, info, 3, fullLabel.size)
        info[3 + fullLabel.size] = 0 // empty context
        // HKDF-Expand (RFC 5869) over a single block (32 <= hash length 32).
        val input = ByteArray(0) + info + byteArrayOf(1)
        return mac.doFinal(input)
    }
}
