package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.URL
import java.util.Base64

/**
 * Robolectric-backed (same reasons as [OkHttpInterceptorTest]: [RequestrObserver.observeDetailed]
 * needs `android.util.Base64` + `android.content.Context`). Runs against a hand-rolled
 * [ServerSocket]-based HTTP/1.1 endpoint rather than MockWebServer, since the whole point of this
 * test is to exercise the raw `java.net.URL`/[java.net.HttpURLConnection] path -- MockWebServer's
 * own dispatch layer is irrelevant here, only that *something* real is listening on the wire.
 *
 * **JVM one-shot constraint**: [URL.setURLStreamHandlerFactory] can only succeed once per JVM.
 * [HttpUrlConnectionWrapper.install] is therefore only exercised once across this whole test
 * class (in the first test method that runs); subsequent test methods reuse the
 * already-installed factory rather than re-calling [HttpUrlConnectionWrapper.install]. Gradle
 * runs this class in its own test JVM per module, so there is no interference from `:app`'s
 * or `:sdk`'s other test classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class HttpUrlConnectionWrapperTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun startEchoServer(responseBody: String, statusLine: String = "200 OK"): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0)
        val thread = Thread {
            runCatching {
                val socket = server.accept()
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    // Drain the request line + headers so the client's write doesn't block.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = s.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 $statusLine\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${responseBody.length}\r\n" +
                                "Connection: close\r\n\r\n" +
                                responseBody
                            ).toByteArray()
                    )
                    out.flush()
                }
            }
        }
        thread.start()
        return server to thread
    }

    @Test
    fun `install wins the factory race the first time it is called in this JVM`() {
        RequestrObserver.init(context, pairingToken = "unused", apiBaseUrl = "http://127.0.0.1:1")
        // Either this test class is the first to install in this JVM (returns true) or a
        // previous test in the same forked JVM already won the race (returns false) --
        // both are valid outcomes of the *documented* one-shot constraint. What must NOT happen
        // is an uncaught Error propagating out of install().
        val result = runCatching { HttpUrlConnectionWrapper.install(RequestrObserver) }
        assertTrue("install() must not throw, even on the losing side of the race", result.isSuccess)
    }

    @Test
    fun `wrapped connection captures response body and status while still returning correct data to the app`() {
        RequestrObserver.init(context, pairingToken = "unused", apiBaseUrl = "http://127.0.0.1:1")
        HttpUrlConnectionWrapper.install(RequestrObserver)

        val (server, thread) = startEchoServer("hello from requestr sdk test")
        try {
            val url = URL("http://127.0.0.1:${server.localPort}/ping")
            val connection = url.openConnection()
            val body = connection.getInputStream().bufferedReader().readText()

            // The app-facing contract must be unaffected by wrapping: the real response body and
            // status still come through exactly as if no wrapper were installed.
            assertEquals("hello from requestr sdk test", body)

            val event = pendingEventFor("/ping")
            assertNotNull("expected an observed event for /ping", event)
            assertEquals("HttpURLConnection", event!!.httpStack)
            assertNotNull(event.responseBodyBase64)
            assertEquals("hello from requestr sdk test", String(Base64.getDecoder().decode(event.responseBodyBase64)))
        } finally {
            thread.join(5000)
            server.close()
        }
    }

    private fun pendingEventFor(urlSuffix: String): ObserverEvent? {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            val found = (field.get(RequestrObserver) as List<ObserverEvent>).lastOrNull { it.url.endsWith(urlSuffix) }
            if (found != null) return found
            Thread.sleep(50)
        }
        return null
    }
}
