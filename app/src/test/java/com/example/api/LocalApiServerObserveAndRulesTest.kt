package com.example.api

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
import java.util.Base64

/**
 * FIX 1 + FIX 2 regression coverage: drives real HTTP requests at a real [LocalApiServer]
 * (same technique as [LocalApiServerAuthTest]) with realistic payloads shaped exactly like
 * each real producer, then reads the persisted row / response back out to confirm headers,
 * bodies, sizes and `wasDecrypted` all survive the round trip -- not just that the endpoint
 * returns 200.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LocalApiServerObserveAndRulesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        LocalApiServer.stop()
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

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    // -- FIX 1: /api/observe -----------------------------------------------------------------

    @Test
    fun `MV3 extension payload persists separate request-response headers, bodies, sizes`() {
        val port = freePort()
        val token = startServerAndGetToken(port)

        val reqBodyText = """{"widget":"a"}"""
        val respBodyText = """{"created":true}"""
        val payload = JSONObject().put(
            "events",
            org.json.JSONArray().put(
                JSONObject()
                    .put("url", "https://example.com/v1/things")
                    .put("method", "POST")
                    .put("statusCode", 201)
                    .put("durationMs", 42)
                    .put("timestamp", 1_700_000_000_000L)
                    .put("requestHeaders", JSONObject().put("Content-Type", "application/json"))
                    .put("responseHeaders", JSONObject().put("Content-Type", "application/json"))
                    .put("requestBody", reqBodyText)
                    .put("requestBodyEncoding", "utf8")
                    .put("responseBody", respBodyText)
                    .put("responseBodyEncoding", "utf8")
            )
        )

        val (status, respBody) = sendHttpRequest(port, "POST", "/api/observe", authHeader = "Bearer $token", body = payload.toString())
        assertEquals(200, status)
        assertTrue(respBody.contains("\"received\":1"))

        val row = runBlocking {
            RequestrDatabase.get(context).capturedTrafficDao().observeAll().first()
                .first { it.url == "https://example.com/v1/things" }
        }
        assertEquals("POST", row.method)
        assertEquals(201, row.statusCode)
        assertEquals("application/json", row.requestHeaders["Content-Type"])
        assertEquals("application/json", row.responseHeaders["Content-Type"])
        assertEquals(reqBodyText, row.requestBody)
        assertEquals(respBodyText, row.responseBody)
        assertEquals(reqBodyText.toByteArray(Charsets.UTF_8).size.toLong(), row.requestSizeBytes)
        assertEquals(respBodyText.toByteArray(Charsets.UTF_8).size.toLong(), row.responseSizeBytes)
        // MV3 events never send isDecrypted -- honest default is true (browser traffic is never
        // MITM'd, see receiveObserverEvents's kdoc).
        assertTrue(row.wasDecrypted)
    }

    @Test
    fun `MV3 payload with requestBodyEncoding=none decodes to no body, not the literal string none`() {
        val port = freePort()
        val token = startServerAndGetToken(port)

        val payload = JSONObject().put(
            "events",
            org.json.JSONArray().put(
                JSONObject()
                    .put("url", "https://example.com/no-body")
                    .put("method", "GET")
                    .put("requestBodyEncoding", "none")
            )
        )

        val (status, _) = sendHttpRequest(port, "POST", "/api/observe", authHeader = "Bearer $token", body = payload.toString())
        assertEquals(200, status)

        val row = runBlocking {
            RequestrDatabase.get(context).capturedTrafficDao().observeAll().first()
                .first { it.url == "https://example.com/no-body" }
        }
        assertNull(row.requestBody)
        assertEquals(0L, row.requestSizeBytes)
    }

    @Test
    fun `SDK payload persists flat headers as request headers, base64 bodies decoded, isDecrypted honored`() {
        val port = freePort()
        val token = startServerAndGetToken(port)

        val reqBodyText = """{"name":"widget"}"""
        val respBodyText = """{"ok":true}"""
        val payload = JSONObject().put(
            "events",
            org.json.JSONArray().put(
                JSONObject()
                    .put("url", "https://api.example.com/sdk-call")
                    .put("method", "POST")
                    .put("headers", JSONObject().put("User-Agent", "my-app/1.0"))
                    .put("statusCode", 200)
                    .put("bodySize", (reqBodyText.length + respBodyText.length).toLong())
                    .put("durationMs", 10)
                    .put("source", "sdk")
                    .put("timestamp", 1_700_000_000_000L)
                    // SDK ALWAYS base64-encodes request/response bodies, with no encoding field.
                    .put("requestBody", b64(reqBodyText))
                    .put("responseBody", b64(respBodyText))
                    .put("isDecrypted", true)
            )
        )

        val (status, _) = sendHttpRequest(port, "POST", "/api/observe", authHeader = "Bearer $token", body = payload.toString())
        assertEquals(200, status)

        val row = runBlocking {
            RequestrDatabase.get(context).capturedTrafficDao().observeAll().first()
                .first { it.url == "https://api.example.com/sdk-call" }
        }
        // The SDK's flat `headers` field (request headers only) must fall back into requestHeaders.
        assertEquals("my-app/1.0", row.requestHeaders["User-Agent"])
        assertTrue(row.responseHeaders.isEmpty())
        assertEquals(reqBodyText, row.requestBody)
        assertEquals(respBodyText, row.responseBody)
        assertEquals(reqBodyText.toByteArray(Charsets.UTF_8).size.toLong(), row.requestSizeBytes)
        assertEquals(respBodyText.toByteArray(Charsets.UTF_8).size.toLong(), row.responseSizeBytes)
        assertTrue(row.wasDecrypted)
    }

    @Test
    fun `SDK payload with no body fields at all falls back to legacy bodySize on responseSizeBytes`() {
        val port = freePort()
        val token = startServerAndGetToken(port)

        val payload = JSONObject().put(
            "events",
            org.json.JSONArray().put(
                JSONObject()
                    .put("url", "https://api.example.com/legacy-minimal")
                    .put("method", "GET")
                    .put("headers", JSONObject())
                    .put("statusCode", 200)
                    .put("bodySize", 1234L)
            )
        )

        val (status, _) = sendHttpRequest(port, "POST", "/api/observe", authHeader = "Bearer $token", body = payload.toString())
        assertEquals(200, status)

        val row = runBlocking {
            RequestrDatabase.get(context).capturedTrafficDao().observeAll().first()
                .first { it.url == "https://api.example.com/legacy-minimal" }
        }
        assertEquals(0L, row.requestSizeBytes)
        assertEquals(1234L, row.responseSizeBytes)
    }

    // -- FIX 2: /api/rules --------------------------------------------------------------------

    @Test
    fun `GET api-rules without Authorization is rejected with 401`() {
        val port = freePort()
        startServerAndGetToken(port)

        val (status, body) = sendHttpRequest(port, "GET", "/api/rules", authHeader = null)

        assertEquals(401, status)
        assertTrue(body.contains("Bearer"))
    }

    @Test
    fun `GET api-rules with a valid token returns domain, replacementValue and every other field`() {
        val port = freePort()
        val token = startServerAndGetToken(port)

        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(
                    name = "block tracker",
                    pattern = "",
                    domain = "tracker.example.com",
                    action = RuleAction.BLOCK,
                    enabled = true,
                    replacementValue = null,
                )
            )
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(
                    name = "rewrite response",
                    pattern = ".*/api/.*",
                    domain = "",
                    action = RuleAction.MODIFY_RESPONSE,
                    enabled = true,
                    replacementValue = """{"statusCode":200,"bodyReplace":"{\"patched\":true}"}""",
                )
            )
        }

        val (status, body) = sendHttpRequest(port, "GET", "/api/rules", authHeader = "Bearer $token")
        assertEquals(200, status)

        // Room's on-disk DB (see RequestrDatabase.get()'s singleton) is shared across test
        // methods that run in the same Robolectric sandbox, so assert on the two rows this test
        // itself inserted rather than an exact total count.
        val arr = org.json.JSONArray(body)

        val domainRule = (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("domain") == "tracker.example.com" }
        assertEquals("BLOCK", domainRule.getString("action"))
        assertTrue(domainRule.isNull("replacementValue"))

        val modifyRule = (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("name") == "rewrite response" }
        assertEquals(".*/api/.*", modifyRule.getString("pattern"))
        assertTrue(modifyRule.getString("replacementValue").contains("patched"))
    }

    @Test
    fun `rules response round-trips through the extension's documented rule shape (domain match, replacementValue)`() {
        val port = freePort()
        val token = startServerAndGetToken(port)

        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(
                    name = "domain-only block",
                    pattern = "",
                    domain = "ads.example.com",
                    action = RuleAction.BLOCK,
                    enabled = true,
                    replacementValue = null,
                )
            )
        }

        val (status, body) = sendHttpRequest(port, "GET", "/api/rules", authHeader = "Bearer $token")
        assertEquals(200, status)

        // enforcer.mjs's checkRules() contract: domain substring match against the request URL's
        // host, case-insensitive, first enabled match wins. Simulate that matching logic here
        // against the exact JSON this server just returned, proving the shape is consumable.
        val arr = org.json.JSONArray(body)
        val rule = arr.getJSONObject(0)
        val host = "sub.ads.example.com"
        val domainMatches = rule.getString("domain").isNotEmpty() &&
            host.lowercase().contains(rule.getString("domain").lowercase())
        assertTrue("expected domain-only rule to match host via substring per enforcer.mjs contract", domainMatches)
        assertTrue(rule.isNull("replacementValue"))
    }
}
