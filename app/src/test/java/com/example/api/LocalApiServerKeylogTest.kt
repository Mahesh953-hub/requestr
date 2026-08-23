package com.example.api

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.SettingsDataStore
import com.example.interception.tls.KeyLogLabel
import com.example.interception.tls.TlsKeyLogRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Task 13 / CAT-2d regression coverage: drives real HTTP requests at a real [LocalApiServer]
 * (same technique as [LocalApiServerAuthTest]/[LocalApiServerObserveAndRulesTest]) against the
 * authenticated `POST /api/keylog` endpoint, proving end-to-end that:
 *  - a valid pairing Bearer token + well-formed NSS keylog lines are accepted and become
 *    genuinely `lookup`-able in [TlsKeyLogRegistry] (not just that the endpoint returns 200);
 *  - a missing or invalid token is rejected with 401 and nothing is recorded, mirroring
 *    `/api/observe`'s and `/api/rules`' exact auth semantics;
 *  - malformed lines are skipped individually (never fatal to the whole request), reflected in
 *    a partial `accepted` count;
 *  - the raw newline-delimited NSS text body shape (no JSON wrapper) is also accepted, so a
 *    user-supplied `SSLKEYLOGFILE` can be piped straight through.
 *
 * This is the authenticated cross-process HTTP -> app-registry half of the end-to-end proof the
 * plan requires; the SDK-side POST itself is covered by `:sdk`'s `KeyLogProducerHttpTest`
 * (MockWebServer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LocalApiServerKeylogTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        LocalApiServer.stop()
        TlsKeyLogRegistry.clear()
    }

    private fun freePort(): Int {
        val probe = ServerSocket(0)
        val port = probe.localPort
        probe.close()
        return port
    }

    private fun awaitServerUp(port: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).close()
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        throw AssertionError("LocalApiServer never started listening on port $port")
    }

    private fun sendHttpRequest(port: Int, method: String, path: String, authHeader: String?, body: String = ""): Pair<Int, String> {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        val out = socket.getOutputStream()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val request = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            if (authHeader != null) append("Authorization: $authHeader\r\n")
            if (bodyBytes.isNotEmpty()) append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(request.toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
        out.flush()

        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val statusLine = reader.readLine() ?: ""
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val responseBody = StringBuilder()
        var inBody = false
        var line: String?
        while (true) {
            line = reader.readLine()
            if (line == null) break
            if (!inBody && line.isEmpty()) { inBody = true; continue }
            if (inBody) responseBody.append(line)
        }
        socket.close()
        return statusCode to responseBody.toString()
    }

    private fun startServerAndGetToken(port: Int): String = runBlocking {
        LocalApiServer.init(context)
        LocalApiServer.start(port)
        awaitServerUp(port)
        val ds = SettingsDataStore.get(context)
        val deadline = System.currentTimeMillis() + 5000
        var token: String? = null
        while (token == null && System.currentTimeMillis() < deadline) {
            token = ds.pairingToken.first()
            if (token == null) kotlinx.coroutines.delay(50)
        }
        token ?: throw AssertionError("pairing token was never generated")
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // -- auth -----------------------------------------------------------------------------

    @Test
    fun `POST api-keylog without an Authorization header is rejected with 401 and nothing is recorded`() {
        val port = freePort()
        startServerAndGetToken(port)
        val cr = "11".repeat(32)
        val payload = JSONObject().put("lines", org.json.JSONArray().put("CLIENT_TRAFFIC_SECRET_0 $cr aabbcc"))

        val (status, body) = sendHttpRequest(port, "POST", "/api/keylog", authHeader = null, body = payload.toString())

        assertEquals(401, status)
        assertTrue(body.contains("Bearer"))
        assertNull(TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, hex(cr)))
    }

    @Test
    fun `POST api-keylog with the wrong token is rejected with 401 and nothing is recorded`() {
        val port = freePort()
        startServerAndGetToken(port)
        val cr = "22".repeat(32)
        val payload = JSONObject().put("lines", org.json.JSONArray().put("CLIENT_TRAFFIC_SECRET_0 $cr aabbcc"))

        val (status, body) = sendHttpRequest(port, "POST", "/api/keylog", authHeader = "Bearer not-the-real-token", body = payload.toString())

        assertEquals(401, status)
        assertTrue(body.contains("Bearer"))
        assertNull(TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, hex(cr)))
    }

    // -- happy path: JSON {"lines":[...]} shape --------------------------------------------

    @Test
    fun `POST api-keylog with a valid token and well-formed lines returns accepted count and becomes lookup-able`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val cr1 = "33".repeat(32)
        val cr2 = "44".repeat(32)
        val payload = JSONObject().put(
            "lines",
            org.json.JSONArray()
                .put("CLIENT_HANDSHAKE_TRAFFIC_SECRET $cr1 aabbccdd")
                .put("SERVER_TRAFFIC_SECRET_0 $cr2 11223344"),
        )

        val (status, body) = sendHttpRequest(port, "POST", "/api/keylog", authHeader = "Bearer $token", body = payload.toString())

        assertEquals(200, status)
        assertTrue(body.contains("\"accepted\":2"))
        assertArrayEquals(hex("aabbccdd"), TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_HANDSHAKE_TRAFFIC_SECRET, hex(cr1)))
        assertArrayEquals(hex("11223344"), TlsKeyLogRegistry.lookup(KeyLogLabel.SERVER_TRAFFIC_SECRET_0, hex(cr2)))
    }

    // -- happy path: raw newline-delimited NSS text body (no JSON wrapper) -----------------

    @Test
    fun `POST api-keylog accepts a raw NSS keylog text body with no JSON wrapping`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val cr = "55".repeat(32)
        val rawBody = "CLIENT_TRAFFIC_SECRET_0 $cr deadbeef\n"

        val (status, body) = sendHttpRequest(port, "POST", "/api/keylog", authHeader = "Bearer $token", body = rawBody)

        assertEquals(200, status)
        assertTrue(body.contains("\"accepted\":1"))
        assertArrayEquals(hex("deadbeef"), TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, hex(cr)))
    }

    // -- malformed lines are skipped individually, never fatal to the request --------------

    @Test
    fun `POST api-keylog skips malformed lines and reports a partial accepted count`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val goodCr = "66".repeat(32)
        val payload = JSONObject().put(
            "lines",
            org.json.JSONArray()
                .put("not a valid line at all")
                .put("CLIENT_TRAFFIC_SECRET_0 tooshort aabb")
                .put("UNKNOWN_LABEL $goodCr aabbcc")
                .put("CLIENT_TRAFFIC_SECRET_0 $goodCr aabbcc"),
        )

        val (status, body) = sendHttpRequest(port, "POST", "/api/keylog", authHeader = "Bearer $token", body = payload.toString())

        assertEquals(200, status)
        assertTrue("expected only the single well-formed line to be accepted, got: $body", body.contains("\"accepted\":1"))
        assertArrayEquals(hex("aabbcc"), TlsKeyLogRegistry.lookup(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, hex(goodCr)))
    }
}
