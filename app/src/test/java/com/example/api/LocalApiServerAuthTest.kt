package com.example.api

import android.os.Build
import androidx.test.core.app.ApplicationProvider
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
 * Robolectric-backed: starts a real [LocalApiServer] on a real loopback [ServerSocket] and drives
 * real HTTP requests at it through [Socket] -- proving the `Authorization: Bearer <pairing
 * token>` enforcement added for Task 6 actually gates `/api/observe` end-to-end (not just that the
 * `isAuthorized` helper compiles), while `/api/ping` remains reachable with no auth at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LocalApiServerAuthTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        LocalApiServer.stop()
    }

    /** Binds ephemeral port 0 momentarily just to grab a free port number, then releases it --
     * [LocalApiServer.start] needs an explicit port (it doesn't support "0 = pick one" like
     * [com.example.interception.HttpProxyEngine] does). */
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
        val request = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            if (authHeader != null) append("Authorization: $authHeader\r\n")
            if (body.isNotEmpty()) append("Content-Length: ${body.length}\r\n")
            append("Connection: close\r\n\r\n")
            if (body.isNotEmpty()) append(body)
        }
        out.write(request.toByteArray())
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

    @Test
    fun `POST api-observe without an Authorization header is rejected with 401`() {
        val port = freePort()
        startServerAndGetToken(port)

        val (status, body) = sendHttpRequest(port, "POST", "/api/observe", authHeader = null, body = """{"events":[]}""")

        assertEquals(401, status)
        assertTrue(body.contains("Bearer"))
    }

    @Test
    fun `POST api-observe with the correct pairing token is accepted with 200`() {
        val port = freePort()
        val token = startServerAndGetToken(port)

        val (status, _) = sendHttpRequest(port, "POST", "/api/observe", authHeader = "Bearer $token", body = """{"events":[]}""")

        assertEquals(200, status)
    }

    @Test
    fun `POST api-observe with the wrong token is rejected with 401`() {
        val port = freePort()
        startServerAndGetToken(port)

        val (status, body) = sendHttpRequest(port, "POST", "/api/observe", authHeader = "Bearer not-the-real-token", body = """{"events":[]}""")

        assertEquals(401, status)
        assertTrue(body.contains("Bearer"))
    }

    @Test
    fun `api-ping still works with no Authorization header at all`() {
        val port = freePort()
        startServerAndGetToken(port)

        val (status, body) = sendHttpRequest(port, "GET", "/api/ping", authHeader = null)

        assertEquals(200, status)
        assertTrue(body.contains("\"status\":\"ok\""))
    }
}
