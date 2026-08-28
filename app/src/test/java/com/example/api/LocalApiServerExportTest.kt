package com.example.api

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
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

/**
 * `GET /api/captures/export` regression coverage: [LocalApiServer.exportCaptures]'s
 * session-scoped path used to page via [com.example.data.local.CapturedTrafficDao.getSessionPageBeforeCursor]
 * and then filter the *page* by time range in memory, so `nextCursor` was computed from the
 * filtered list rather than the raw keyset scan -- a session with a run of out-of-range rows
 * longer than one page never made forward progress. These drive a real server over a real
 * loopback socket (same technique as [LocalApiServerObserveAndRulesTest]) to exercise the fix
 * end to end, plus the HAR format's discoverable `_nextCursor` extension field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LocalApiServerExportTest {

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

    private fun sendHttpRequest(port: Int, method: String, path: String): Pair<Int, String> {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        val out = socket.getOutputStream()
        out.write("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
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

    private fun row(sessionId: Long, timestamp: Long, url: String) = CapturedTrafficEntity(
        sessionId = sessionId,
        method = "GET",
        url = url,
        host = "example.com",
        timestamp = timestamp,
    )

    private fun seedRows(rows: List<CapturedTrafficEntity>) = runBlocking {
        val dao = RequestrDatabase.get(context).capturedTrafficDao()
        rows.forEach { dao.insert(it) }
    }

    @Test
    fun `session-scoped export walks past a long out-of-range run to reach in-range rows further back`() {
        val port = freePort()
        // A 5-row out-of-range run (all newer than the [100, 200] range, longer than limit=2)
        // sits ahead of every in-range row. The old in-memory filter would discard that run two
        // rows at a time, compute its next cursor from the empty filtered list, and never reach
        // the in-range rows; the SQL-time `BETWEEN` filter skips it and pages straight through.
        // A per-test-unique sessionId keeps this assertion exact despite the shared file-backed
        // `requestr.db` singleton (see RequestrDatabase.get()) written by other tests.
        seedRows(
            listOf(250, 240, 230, 220, 210, 190, 180, 130, 120).map {
                row(sessionId = 7000001, timestamp = it.toLong(), url = "https://example.com/$it")
            },
        )
        LocalApiServer.init(context)
        LocalApiServer.start(port)
        awaitServerUp(port)

        val collectedUrls = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        while (pages < 20) {
            val cursorParam = if (cursor != null) "&cursor=$cursor" else ""
            val (status, body) = sendHttpRequest(
                port, "GET",
                "/api/captures/export?sessionId=7000001&from=100&to=200&limit=2$cursorParam",
            )
            assertEquals(200, status)
            val json = JSONObject(body)
            val items = json.getJSONArray("items")
            for (i in 0 until items.length()) collectedUrls += items.getJSONObject(i).getString("url")
            pages++
            val next = json.opt("nextCursor")
            if (next == null || next == JSONObject.NULL) break
            cursor = next as String
        }

        assertEquals(
            setOf("https://example.com/190", "https://example.com/180", "https://example.com/130", "https://example.com/120"),
            collectedUrls,
        )
        assertTrue("must not need an unbounded number of pages", pages <= 10)
    }

    @Test
    fun `har export embeds a discoverable next cursor as a HAR extension field`() {
        val port = freePort()
        seedRows((1..3).map { row(sessionId = 7000002, timestamp = it * 100L, url = "https://example.com/$it") })
        LocalApiServer.init(context)
        LocalApiServer.start(port)
        awaitServerUp(port)

        val (status, body) = sendHttpRequest(port, "GET", "/api/captures/export?sessionId=7000002&from=0&to=1000&limit=2&format=har")
        assertEquals(200, status)
        val json = JSONObject(body)
        val log = json.getJSONObject("log")
        assertTrue("HAR entries must still be a real multi-entry array", log.getJSONArray("entries").length() == 2)
        assertTrue("must expose a non-null _nextCursor when more rows remain", log.get("_nextCursor") != JSONObject.NULL)
    }

    @Test
    fun `har export's next cursor is null once the range is exhausted`() {
        val port = freePort()
        seedRows(listOf(row(sessionId = 7000003, timestamp = 100L, url = "https://example.com/only")))
        LocalApiServer.init(context)
        LocalApiServer.start(port)
        awaitServerUp(port)

        val (status, body) = sendHttpRequest(port, "GET", "/api/captures/export?sessionId=7000003&from=0&to=1000&limit=50&format=har")
        assertEquals(200, status)
        val log = JSONObject(body).getJSONObject("log")
        assertEquals(JSONObject.NULL, log.get("_nextCursor"))
    }
}
