package com.example.api

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.ToolsRepository
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * CAT-1 regression (byte-exact). [LocalApiServer.handleClient] used to declare `Content-Length`
 * as the response `String`'s UTF-16 *code-unit* count (`resp.length`) but then write its UTF-8
 * encoding, which is longer for any non-ASCII character -- e.g. `"Hex ↔ String Converter"`'s
 * U+2194 is 1 UTF-16 code unit but 3 UTF-8 bytes. A spec-compliant client that reads exactly
 * `Content-Length` bytes off the wire (as this test, and the real CLI's OkHttp client, both do --
 * see [okhttp3.internal.http1] body framing) received a body truncated mid-character, which
 * `org.json.JSONArray` then failed to parse. Reproduced pre-fix: declared `Content-Length: 2489`
 * against an actual 2491 UTF-8 body bytes, truncating the last tool's `description` and throwing
 * `JSONException: Unterminated object`.
 *
 * [listTools] also used to hand-roll its JSON via `.replace("\"","\\\"")`, which only escapes
 * double quotes -- a custom tool description containing a raw backslash (e.g. a `\d`-style regex
 * pattern, see [ToolsRepository.customToolToItem]) produced an illegal `\d` JSON escape. Both
 * fixes are exercised here against a real [LocalApiServer] over a real loopback [Socket], the
 * same technique [LocalApiServerAuthTest]/[LocalApiServerObserveAndRulesTest] use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LocalApiServerToolsEncodingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        LocalApiServer.stop()
    }

    /** Binds ephemeral port 0 momentarily just to grab a free port number, then releases it --
     * [LocalApiServer.start] needs an explicit port (same helper as [LocalApiServerAuthTest]). */
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

    private fun startServer(): Int {
        val port = freePort()
        LocalApiServer.init(context)
        LocalApiServer.start(port)
        awaitServerUp(port)
        return port
    }

    /**
     * Issues a raw `GET` and reads the response the way a spec-compliant HTTP/1.1 client does:
     * parse the header block byte-by-byte up to the blank line (never through a char `Reader`,
     * which would decode -- and potentially mask -- exactly the kind of framing bug under test),
     * then read EXACTLY the declared `Content-Length` bytes for the body. This is deliberately
     * NOT "read whatever arrived on the socket" (which would hide the pre-fix truncation), and
     * NOT "read until the peer closes" (masks a short body by silently accepting less).
     */
    private fun getRaw(port: Int, path: String): Pair<Int, ByteArray> {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            flush()
        }

        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        val tail = IntArray(4) { -1 }
        while (true) {
            val b = input.read()
            if (b == -1) throw AssertionError("socket closed before header block ended")
            headerBytes.write(b)
            tail[0] = tail[1]; tail[1] = tail[2]; tail[2] = tail[3]; tail[3] = b
            if (tail[0] == '\r'.code && tail[1] == '\n'.code && tail[2] == '\r'.code && tail[3] == '\n'.code) break
        }
        // Header bytes are always plain ASCII per RFC 7230 -- ISO-8859-1 here is just a safe
        // 1-byte-per-char decode for parsing status/Content-Length, unrelated to the UTF-8 body
        // charset bug under test.
        val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val statusLine = headerText.lineSequence().first()
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val contentLength = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyBytes, read, contentLength - read)
            if (n == -1) throw AssertionError("socket closed after $read/$contentLength declared body bytes")
            read += n
        }
        socket.close()
        return statusCode to bodyBytes
    }

    @Test
    fun `GET api-tools Content-Length is the exact UTF-8 byte count, not the UTF-16 length, and parses`() {
        val port = startServer()

        val (status, bodyBytes) = getRaw(port, "/api/tools")
        assertEquals(200, status)

        val bodyText = String(bodyBytes, Charsets.UTF_8)

        // Proves this fixture actually exercises the multibyte path (not just an all-ASCII
        // response that would pass even with the pre-fix bug): the built-in catalog always
        // contains "Hex ↔ String Converter" (U+2194 = 3 UTF-8 bytes, 1 UTF-16 code unit), so the
        // UTF-8 byte count must exceed the UTF-16 `.length` of the same text.
        assertTrue(
            "expected UTF-8 byte length (${bodyBytes.size}) \u003e UTF-16 code-unit length " +
                "(${bodyText.length}) for a body containing a multibyte character",
            bodyBytes.size > bodyText.length
        )

        // The real regression: parses cleanly because exactly Content-Length bytes were read,
        // and Content-Length was computed from the same UTF-8 bytes actually written on the wire.
        val arr = JSONArray(bodyText)
        assertTrue(arr.length() > 0)
        val hexTool = (0 until arr.length()).map { arr.getJSONObject(it) }
            .first { it.getString("id") == "hex-converter" }
        assertEquals("Hex ↔ String Converter", hexTool.getString("title"))
    }

    @Test
    fun `a custom tool whose description contains a backslash still serializes as valid JSON`() {
        val port = startServer()
        ToolsRepository.init(context)
        ToolsRepository.addCustomTool(
            name = "Backslash Regex Encoding Test",
            operation = "regex-replace",
            pattern = "\\d+",
            replacement = "#",
        )

        val (status, bodyBytes) = getRaw(port, "/api/tools")
        assertEquals(200, status)
        val bodyText = String(bodyBytes, Charsets.UTF_8)

        // Pre-fix, listTools() only escaped `"` via .replace("\"","\\\""), so the pattern's raw
        // backslash (from a `\d`-style regex) leaked into the response as the illegal JSON
        // escape `\d`, and org.json.JSONArray(...) threw. Now built on org.json throughout, so
        // the backslash is correctly escaped as `\\d` on the wire and decodes back to `\d`.
        val arr = JSONArray(bodyText)
        val customTool = (0 until arr.length()).map { arr.getJSONObject(it) }
            .first { it.getString("id") == "custom-backslash-regex-encoding-test" }
        assertEquals("Custom regex: \\d+ → #", customTool.getString("description"))
    }
}
