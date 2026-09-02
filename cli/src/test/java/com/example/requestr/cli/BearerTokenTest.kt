package com.example.requestr.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `--token` / `REQUESTR_TOKEN` support for the bearer-protected routes (`/api/rules`,
 * `/api/observe`, `/api/keylog`).
 *
 * [serverAccepts] is a line-for-line copy of `LocalApiServer.isAuthorized` (app module,
 * `private fun isAuthorized(authHeader: String?)`): the raw header value is what the server
 * reads after `Authorization:` and trims; it must start with `Bearer ` (scheme
 * case-insensitive) and the remainder, trimmed, must equal the pairing token exactly. Keeping
 * the check here means a change to the header the CLI sends fails this test before it fails
 * against a device.
 */
class BearerTokenTest {

    private val pairingToken = "3f9c2a7b1d4e6f80a1b2c3d4e5f60718293a4b5c6d7e8f9012345678abcdef01"

    private fun serverAccepts(authHeader: String?, expected: String?): Boolean {
        val expectedToken = expected ?: return false
        val header = authHeader ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = header.substring("Bearer ".length).trim()
        return presented == expectedToken
    }

    @Test
    fun `header value the CLI sends is accepted by the server's exact bearer check`() {
        val header = RequestrCli.authorizationHeaderValue(pairingToken)
        assertEquals("Bearer $pairingToken", header)
        assertTrue(serverAccepts(header, pairingToken))
        assertFalse(serverAccepts(header, "different-token"), "exact-match token")
        assertFalse(serverAccepts(header, null), "server without a generated token rejects everything")
    }

    @Test
    fun `--token flag is parsed as an option with its value and does not eat the command`() {
        val (options, positional) = RequestrCli.parseArgs(listOf("--token", pairingToken, "rules"))
        assertEquals(pairingToken, options["token"])
        assertEquals(listOf("rules"), positional)
    }

    @Test
    fun `--token may appear after the command and alongside --api-base`() {
        val (options, positional) = RequestrCli.parseArgs(
            listOf("rules", "--api-base", "http://localhost:9876", "--token", pairingToken)
        )
        assertEquals(pairingToken, options["token"])
        assertEquals("http://localhost:9876", options["api-base"])
        assertEquals(listOf("rules"), positional)
    }

    @Test
    fun `flag wins over the environment variable`() {
        val token = RequestrCli.resolveToken(
            options = mapOf("token" to "from-flag"),
            env = mapOf(RequestrCli.TOKEN_ENV_VAR to "from-env"),
        )
        assertEquals("from-flag", token)
    }

    @Test
    fun `REQUESTR_TOKEN is used when no flag is given`() {
        assertEquals("REQUESTR_TOKEN", RequestrCli.TOKEN_ENV_VAR)
        val token = RequestrCli.resolveToken(options = emptyMap(), env = mapOf("REQUESTR_TOKEN" to pairingToken))
        assertEquals(pairingToken, token)
    }

    @Test
    fun `no flag and no env means anonymous - no Authorization header at all`() {
        assertNull(RequestrCli.resolveToken(emptyMap(), emptyMap()))
        assertNull(RequestrCli.resolveToken(emptyMap(), mapOf("REQUESTR_TOKEN" to "   ")), "blank env is absent")
    }

    @Test
    fun `trailing --token with no value falls back to the environment and keeps the command`() {
        val (options, positional) = RequestrCli.parseArgs(listOf("rules", "--token"))
        assertEquals(listOf("rules"), positional)
        assertTrue(options.containsKey("token"))
        assertNull(RequestrCli.resolveToken(options, emptyMap()), "empty flag value is absent")
        assertEquals(pairingToken, RequestrCli.resolveToken(options, mapOf("REQUESTR_TOKEN" to pairingToken)))
    }

    @Test
    fun `whitespace around a token is trimmed so a pasted value with a trailing newline still matches`() {
        val token = RequestrCli.resolveToken(mapOf("token" to " $pairingToken\n"), emptyMap())
        assertEquals(pairingToken, token)
        assertTrue(serverAccepts(RequestrCli.authorizationHeaderValue(token!!), pairingToken))
    }

    @Test
    fun `401 guidance never contains the token and names both the flag and the env var`() {
        listOf(true, false).forEach { sent ->
            val hint = RequestrCli.unauthorizedHint(tokenWasSent = sent)
            assertFalse(hint.contains(pairingToken))
            assertTrue(hint.startsWith("HTTP 401"))
        }
        val missing = RequestrCli.unauthorizedHint(tokenWasSent = false)
        assertTrue(missing.contains("--token"))
        assertTrue(missing.contains("REQUESTR_TOKEN"))
    }

    @Test
    fun `help documents the flag, the env var and the exact header format`() {
        val help = RequestrCli.helpText()
        assertTrue(help.contains("--token <token>"))
        assertTrue(help.contains("REQUESTR_TOKEN"))
        assertTrue(help.contains("Authorization: Bearer <token>"))
    }
}
