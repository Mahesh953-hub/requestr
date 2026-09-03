package com.example.runtime.http

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.interception.http.Http2Parser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression coverage for analyzeSession's direct nullable connectionId lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class HttpStackDetectorSessionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db by lazy { RequestrDatabase.get(context) }

    @Before
    fun setUp() {
        StackSignalStore.clear()
        runBlocking { db.capturedTrafficDao().clearAll() }
    }

    @After
    fun tearDown() {
        StackSignalStore.clear()
        runBlocking { db.capturedTrafficDao().clearAll() }
    }

    @Test
    fun `analyzeSession uses connection signals when connectionId is present`() = runBlocking {
        val sessionId = 99001L
        val connectionId = "detector-test-okhttp"
        StackSignalStore.registerHttp2Settings(
            connectionId,
            listOf(Http2Parser.SETTINGS_INITIAL_WINDOW_SIZE to 16777216),
        )
        db.capturedTrafficDao().insert(row(sessionId, connectionId))

        val report = HttpStackDetector.analyzeSession(context, sessionId)

        assertEquals(1, report.totalRequests)
        assertEquals(1, report.countsByStack[HttpStack.OKHTTP])
        assertTrue(
            report.representativeVerdicts[HttpStack.OKHTTP]
                ?.signals
                ?.any { it.name == "h2-settings" } == true,
        )
    }

    @Test
    fun `analyzeSession safely classifies row with null connectionId`() = runBlocking {
        val sessionId = 99002L
        StackSignalStore.registerHttp2Settings(
            "unrelated-connection",
            listOf(Http2Parser.SETTINGS_INITIAL_WINDOW_SIZE to 16777216),
        )
        db.capturedTrafficDao().insert(
            row(
                sessionId = sessionId,
                connectionId = null,
                headers = mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 13)"),
            )
        )

        val report = HttpStackDetector.analyzeSession(context, sessionId)

        assertEquals(1, report.totalRequests)
        assertEquals(1, report.countsByStack[HttpStack.HTTP_URL_CONNECTION])
        assertFalse(report.representativeVerdicts.isEmpty())
        assertTrue(
            report.representativeVerdicts[HttpStack.HTTP_URL_CONNECTION]
                ?.signals
                ?.none { it.name == "h2-settings" } == true,
        )
    }

    private fun row(
        sessionId: Long,
        connectionId: String?,
        headers: Map<String, String> = emptyMap(),
    ) = CapturedTrafficEntity(
        sessionId = sessionId,
        method = "GET",
        url = "https://example.com/test",
        host = "example.com",
        path = "/test",
        statusCode = 200,
        protocol = "HTTP/2",
        requestHeaders = headers,
        connectionId = connectionId,
    )
}
