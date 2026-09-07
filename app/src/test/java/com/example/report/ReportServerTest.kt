package com.example.report

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * S5 task 41 gate G22: drives real HTTP requests at a real [ReportServer] over a loopback
 * socket to prove authentication, loopback binding, and session report rendering work end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ReportServerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        ReportServer.stop()
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

    private fun sendHttpRequest(
        port: Int,
        method: String,
        path: String,
        authHeader: String? = null,
        origin: String? = null,
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
            if (origin != null) append("Origin: $origin\r\n")
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
        ReportServer.init(context)
        ReportServer.start(context, port)
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

    private fun seedSessionWithRows(): Long = runBlocking {
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
                url = "https://api.example.com/users",
                host = "api.example.com",
                path = "/users",
                statusCode = 200,
                latencyMs = 42,
                timestamp = System.currentTimeMillis() - 30_000,
                requestBody = "",
                responseBody = "{\"users\":[]}",
            ),
        )
        db.capturedTrafficDao().insert(
            CapturedTrafficEntity(
                sessionId = sessionId,
                method = "POST",
                url = "https://api.example.com/users",
                host = "api.example.com",
                path = "/users",
                statusCode = 201,
                latencyMs = 88,
                timestamp = System.currentTimeMillis() - 10_000,
                requestBody = "{\"name\":\"Ada\"}",
                responseBody = "{\"id\":1}",
            ),
        )
        sessionId
    }

    @Test
    fun `GET root without bearer token is rejected with 401`() {
        val port = freePort()
        startServerAndGetToken(port)
        val (status, body) = sendHttpRequest(port, "GET", "/")
        assertEquals(401, status)
        assertTrue(body.contains("Bearer"))
    }

    @Test
    fun `GET root with valid token renders seeded session rows`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        seedSessionWithRows()

        val (status, body) = sendHttpRequest(port, "GET", "/?token=$token")
        assertEquals(200, status)
        assertTrue("report should contain GET /users", body.contains("GET /users"))
        assertTrue("report should contain POST /users", body.contains("POST /users"))
        assertTrue("report should contain 201", body.contains("201"))
    }

    @Test
    fun `GET root with Authorization header is accepted`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        seedSessionWithRows()

        val (status, body) = sendHttpRequest(port, "GET", "/", authHeader = "Bearer $token")
        assertEquals(200, status)
        assertTrue(body.contains("Session"))
    }

    @Test
    fun `cross-origin request is rejected when origin does not match loopback host`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val (status, body) = sendHttpRequest(
            port, "GET", "/?token=$token",
            origin = "https://evil.example.com",
        )
        assertEquals(403, status)
        assertTrue(body.contains("cross-origin"))
    }

    @Test
    fun `server binds loopback by default`() {
        val port = freePort()
        startServerAndGetToken(port)
        assertTrue("expected loopback binding", !ReportServer.bindToLan)
    }

    @Test
    fun `HAR download with valid token returns 200`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        val sessionId = seedSessionWithRows()

        val (status, body) = sendHttpRequest(port, "GET", "/har?token=$token&sessionId=$sessionId")
        assertEquals(200, status)
        assertTrue(body.contains(""""version"""))
        assertTrue(body.contains(""""log"""))
    }

    @Test
    fun `detail page renders request and response body`() {
        val port = freePort()
        val token = startServerAndGetToken(port)
        seedSessionWithRows()
        val row = runBlocking {
            RequestrDatabase.get(context).capturedTrafficDao().getBySession(
                RequestrDatabase.get(context).captureSessionDao().observeAll().first().first().id,
            ).first { it.method == "POST" }
        }

        val (status, body) = sendHttpRequest(port, "GET", "/detail?token=$token&id=${row.id}")
        assertEquals(200, status)
        assertTrue(body.contains("Ada"))
        assertTrue(body.contains("""\"id\":1"""))
        assertTrue("repeat button present", body.contains("repeat-btn"))
    }
}
