package com.example.report

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * S5 task 62 gate G30: the report server's one-click repeat endpoint issues exactly one upstream
 * request per click, enforces rate limiting, and still requires the bearer token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ReportServerRepeatTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var mockServer: MockWebServer
    private val httpClient = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        ReportServer.stop()
        mockServer.shutdown()
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
        throw AssertionError("ReportServer never started on port $port")
    }

    private fun startServerAndGetToken(port: Int): String = runBlocking {
        ReportServer.init(context)
        ReportServer.repeatRateLimitMs = 500
        ReportServer.repeatExecutor = { id ->
            val response = withContext(kotlinx.coroutines.Dispatchers.IO) {
                httpClient.newCall(Request.Builder().url(mockServer.url("/repeat/$id")).build()).execute()
            }
            ReportServer.RepeatAttempt(
                success = response.isSuccessful,
                statusCode = response.code,
                latencyMs = response.receivedResponseAtMillis - response.sentRequestAtMillis,
                error = null,
            )
        }
        ReportServer.start(context, port)
        awaitServerUp(port)
        val ds = SettingsDataStore.get(context)
        val deadline = System.currentTimeMillis() + 5000
        var token: String? = null
        while (token == null && System.currentTimeMillis() < deadline) {
            token = ds.pairingToken.first()
            if (token == null) delay(50)
        }
        token ?: throw AssertionError("pairing token was never generated")
    }

    private fun seedTransaction(): Long = runBlocking {
        val db = RequestrDatabase.get(context)
        val sessionId = db.captureSessionDao().insert(
            com.example.data.local.CaptureSessionEntity(
                startedAt = System.currentTimeMillis() - 60_000,
                tier = com.example.data.local.CaptureTier.NONE,
            ),
        )
        db.capturedTrafficDao().insert(
            CapturedTrafficEntity(
                sessionId = sessionId,
                method = "GET",
                url = "https://api.example.com/items",
                host = "api.example.com",
                path = "/items",
                statusCode = 200,
                latencyMs = 12,
                timestamp = System.currentTimeMillis() - 5000,
                responseBody = "{\"items\":[]}",
            ),
        ).also { id ->
            return@runBlocking id
        }
    }

    private fun sendHttpRequest(
        port: Int,
        method: String,
        path: String,
        authHeader: String? = null,
        body: String = "",
    ): Pair<Int, String> {
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

    @Test
    fun `POST repeat without token is rejected with 401`() {
        val port = freePort()
        startServerAndGetToken(port)
        val txId = seedTransaction()
        val (status, body) = sendHttpRequest(port, "POST", "/repeat?id=$txId")
        assertEquals(401, status)
        assertTrue(body.contains("Bearer"))
    }

    @Test
    fun `POST repeat executes exactly one upstream request per click`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val txId = seedTransaction()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val (status, body) = sendHttpRequest(port, "POST", "/repeat?id=$txId", authHeader = "Bearer $token")
        assertEquals(200, status)
        assertTrue(body.contains(""""success":true"""))
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun `second click within rate limit is rejected with 429`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val txId = seedTransaction()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val (firstStatus, _) = sendHttpRequest(port, "POST", "/repeat?id=$txId", authHeader = "Bearer $token")
        assertEquals(200, firstStatus)
        assertEquals(1, mockServer.requestCount)

        val (secondStatus, body) = sendHttpRequest(port, "POST", "/repeat?id=$txId", authHeader = "Bearer $token")
        assertEquals(429, secondStatus)
        assertTrue(body.contains("rate limit"))
        assertEquals("executor must not be called while rate limited", 1, mockServer.requestCount)
    }

    @Test
    fun `click after rate limit window executes a second upstream request`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val txId = seedTransaction()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val (firstStatus, _) = sendHttpRequest(port, "POST", "/repeat?id=$txId", authHeader = "Bearer $token")
        assertEquals(200, firstStatus)
        assertEquals(1, mockServer.requestCount)

        runBlocking { delay(ReportServer.repeatRateLimitMs + 100) }

        val (secondStatus, body) = sendHttpRequest(port, "POST", "/repeat?id=$txId", authHeader = "Bearer $token")
        assertEquals(200, secondStatus)
        assertTrue(body.contains(""""success":true"""))
        assertEquals(2, mockServer.requestCount)
    }
}
