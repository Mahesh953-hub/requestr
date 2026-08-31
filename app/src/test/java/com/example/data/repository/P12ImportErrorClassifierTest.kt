package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CaP12Exchange][com.example.interception.CaP12Exchange]'s real failure message shapes,
 * classified — see `CaP12Exchange.parse()`'s `ParseResult.Failure` branches for the exact
 * strings these tests mirror.
 */
class P12ImportErrorClassifierTest {

    @Test
    fun `keystore open failure with a password hint classifies as wrong password`() {
        assertEquals(
            P12ImportFailureKind.WRONG_PASSWORD,
            P12ImportErrorClassifier.classify("Could not open PKCS#12 bundle: keystore password was incorrect")
        )
    }

    @Test
    fun `mac verify failure classifies as wrong password`() {
        assertEquals(
            P12ImportFailureKind.WRONG_PASSWORD,
            P12ImportErrorClassifier.classify("Could not open PKCS#12 bundle: MAC verify error")
        )
    }

    @Test
    fun `private key read failure mentioning password classifies as wrong password`() {
        assertEquals(
            P12ImportFailureKind.WRONG_PASSWORD,
            P12ImportErrorClassifier.classify("Could not read private key for alias 'ca': wrong password")
        )
    }

    @Test
    fun `missing private key entry classifies as unsupported content`() {
        assertEquals(
            P12ImportFailureKind.UNSUPPORTED_CONTENT,
            P12ImportErrorClassifier.classify("PKCS#12 bundle has no private-key entry")
        )
    }

    @Test
    fun `missing certificate classifies as unsupported content`() {
        assertEquals(
            P12ImportFailureKind.UNSUPPORTED_CONTENT,
            P12ImportErrorClassifier.classify("Alias 'ca' has no X.509 certificate")
        )
    }

    @Test
    fun `non-CA certificate classifies as unsupported content`() {
        assertEquals(
            P12ImportFailureKind.UNSUPPORTED_CONTENT,
            P12ImportErrorClassifier.classify(
                "Certificate for alias 'ca' is not a CA certificate (basicConstraints CA=false)"
            )
        )
    }

    @Test
    fun `generic open failure without a password hint classifies as corrupt file`() {
        assertEquals(
            P12ImportFailureKind.CORRUPT_FILE,
            P12ImportErrorClassifier.classify("Could not open PKCS#12 bundle: DER input, Integer tag error")
        )
    }

    @Test
    fun `persist failure classifies distinctly from parse failures`() {
        assertEquals(
            P12ImportFailureKind.PERSIST_FAILED,
            P12ImportErrorClassifier.classify("Failed to persist imported CA to disk")
        )
    }

    @Test
    fun `blank or null message classifies as unknown`() {
        assertEquals(P12ImportFailureKind.UNKNOWN, P12ImportErrorClassifier.classify(null))
        assertEquals(P12ImportFailureKind.UNKNOWN, P12ImportErrorClassifier.classify("   "))
    }

    @Test
    fun `unrecognised message classifies as unknown but keeps the raw text visible`() {
        val kind = P12ImportErrorClassifier.classify("some never-before-seen failure shape")
        assertEquals(P12ImportFailureKind.UNKNOWN, kind)
        val shown = P12ImportErrorClassifier.userMessage(kind, "some never-before-seen failure shape")
        assertTrue(shown.contains("some never-before-seen failure shape"))
    }

    @Test
    fun `each kind produces a distinct, non-blank user message`() {
        val messages = P12ImportFailureKind.values().map {
            P12ImportErrorClassifier.userMessage(it, "raw")
        }
        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { assertTrue(it.isNotBlank()) }
    }
}
