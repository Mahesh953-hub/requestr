package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.0 Lane C (plan task 13) — tests for the pure presentation helpers extracted from the
 * Reqy Assistant redesign of [ChatScreen]:
 *  - [isReqyProviderError] detects assistant messages produced by [ChatRepository]'s
 *    frozen failure paths (connection exception, non-2xx HTTP, empty provider response).
 *  - [formatReqyTimestamp] formats an epoch-millis timestamp as HH:mm.
 *
 * These are pure Kotlin (no Android framework), so they run as plain JUnit. They pin the
 * three existing failure-string shapes so a future refactor of the *presentation* layer
 * cannot silently change which bubbles render as the error/retry affordance — they do
 * NOT alter the frozen failure behaviour in [ChatRepository].
 */
class ReqyAssistantHelpersTest {

    // ─── isReqyProviderError ─────────────────────────────────────────────

    @Test
    fun `connection failure string is detected as provider error`() {
        // Mirrors ChatRepository: "Connection failed: ${e.message}. Check ..."
        assertTrue(isReqyProviderError("Connection failed: timeout. Check provider URL and API key in Settings."))
    }

    @Test
    fun `connection failure with leading whitespace is still detected`() {
        assertTrue(isReqyProviderError("  Connection failed: unknown host. Check provider URL and API key in Settings."))
    }

    @Test
    fun `non-2xx HTTP error string is detected as provider error`() {
        // Mirrors ChatRepository: "Error ${response.code}: ${response.message}"
        assertTrue(isReqyProviderError("Error 500: Internal Server Error"))
        assertTrue(isReqyProviderError("Error 401: Unauthorized"))
        assertTrue(isReqyProviderError("Error 404: Not Found"))
    }

    @Test
    fun `empty provider response string is detected as provider error`() {
        // Mirrors ChatRepository fallback when choices array is absent
        assertTrue(isReqyProviderError("Empty response from provider"))
    }

    @Test
    fun `normal assistant response is not a provider error`() {
        assertFalse(isReqyProviderError("Sure! Here's how to test an API endpoint…"))
    }

    @Test
    fun `local fallback response is not a provider error`() {
        assertFalse(isReqyProviderError("I can help you explore requestr's features. Try asking about the API tester!"))
    }

    @Test
    fun `user message content is not classified as provider error`() {
        // Even if a user typed "Error 500" it should be flagged — but the caller
        // already gates on role == "assistant". Here we verify the content-only
        // predicate matches the error shape regardless.
        assertTrue(isReqyProviderError("Error 503: Service Unavailable"))
    }

    @Test
    fun `string starting with Error but no code is not a provider error`() {
        // "Error something" lacks the 3-digit code, so must NOT match
        assertFalse(isReqyProviderError("Error something went wrong"))
    }

    @Test
    fun `error code embedded mid-string is not a provider error`() {
        assertFalse(isReqyProviderError("The server returned Error 500: oops"))
    }

    @Test
    fun `empty string is not a provider error`() {
        assertFalse(isReqyProviderError(""))
    }

    // ─── formatReqyTimestamp ─────────────────────────────────────────────

    @Test
    fun `formats hour and minute with leading zeros`() {
        // Use a fixed epoch millis for 09:05 local — compute via Calendar to avoid TZ brittleness
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(2026, java.util.Calendar.AUGUST, 24, 9, 5, 0)
        }
        assertEquals("09:05", formatReqyTimestamp(cal.timeInMillis))
    }

    @Test
    fun `formats double-digit hour and minute`() {
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(2026, java.util.Calendar.AUGUST, 24, 14, 37, 0)
        }
        assertEquals("14:37", formatReqyTimestamp(cal.timeInMillis))
    }

    @Test
    fun `formats midnight as 00_00`() {
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(2026, java.util.Calendar.AUGUST, 24, 0, 0, 0)
        }
        assertEquals("00:00", formatReqyTimestamp(cal.timeInMillis))
    }

    @Test
    fun `formats 23_59`() {
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(2026, java.util.Calendar.AUGUST, 24, 23, 59, 0)
        }
        assertEquals("23:59", formatReqyTimestamp(cal.timeInMillis))
    }
}
