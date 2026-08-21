package com.example.runtime.http

import android.content.Context
import com.example.data.local.RequestrDatabase

/**
 * S2: best-effort auto-detection of which HTTP client library produced a given captured
 * request, plus which of the well-known Android HTTP stacks are present on this device's
 * classpath.
 *
 * Two genuinely different signals, both honestly labelled as heuristics rather than certainty:
 *
 *  1. [detectFromHeaders] classifies a single already-captured request by its `User-Agent`
 *     header, using each library's real *default* UA format (none of these are guaranteed --
 *     any app can override `User-Agent` on a per-request or per-client basis, which makes the
 *     underlying stack unrecoverable from headers alone; that ambiguity always falls to
 *     [HttpStack.UNKNOWN] rather than a guess):
 *       - OkHttp:            `okhttp/<version>` (e.g. `okhttp/4.12.0`)
 *       - Cronet:            contains `Cronet/<version>` inside a longer
 *                             `<pkg>/<appVersion> (Linux; U; Android ...; Build/...; Cronet/x)`
 *                             string
 *       - Ktor (JVM/Android client engines): default UA plugin value is the literal string
 *                             `Ktor client` unless the app supplies its own `agent`
 *       - HttpURLConnection:  Android's JDK default (no library override) sends the system
 *                             `http.agent` property, which starts with `Dalvik/<version>`
 *       - Volley:             ships NO distinct default User-Agent of its own -- its default
 *                             `HurlStack` path runs on top of `HttpURLConnection` and inherits
 *                             the same `Dalvik/...` string unless the app explicitly sets one.
 *                             Volley traffic is therefore indistinguishable from raw
 *                             HttpURLConnection traffic by header alone; this detector does NOT
 *                             pretend otherwise -- it reports such rows as
 *                             [HttpStack.HTTP_URL_CONNECTION], the honest lower bound, and this
 *                             ambiguity is documented in [StackDetectionReport] rather than
 *                             silently guessed at.
 *
 *  2. [detectAvailableOnClasspath] answers a different, unambiguous question -- "which of
 *     these libraries does THIS app (requestr itself) have loaded?" -- via `Class.forName`
 *     presence checks, the same technique already used by [CronetAdapter]/[WebViewAdapter] in
 *     this file's sibling object. It cannot say anything about a third-party app's classpath;
 *     requestr cannot introspect another app's process.
 */
enum class HttpStack(val label: String) {
    OKHTTP("OkHttp"),
    CRONET("Cronet"),
    KTOR("Ktor"),
    HTTP_URL_CONNECTION("HttpURLConnection"),
    VOLLEY("Volley"),
    UNKNOWN("Unknown"),
}

object HttpStackDetector {

    /**
     * Classifies one request's client stack from its captured `User-Agent` header. Returns
     * [HttpStack.UNKNOWN] when the header is missing/blank or matches none of the known default
     * formats -- most commonly because the app set a custom User-Agent, which is common and
     * expected, not a detector bug.
     */
    fun detectFromHeaders(requestHeaders: Map<String, String>): HttpStack {
        val ua = (requestHeaders["User-Agent"] ?: requestHeaders["user-agent"] ?: "").trim()
        if (ua.isEmpty()) return HttpStack.UNKNOWN
        return when {
            ua.startsWith("okhttp/", ignoreCase = true) -> HttpStack.OKHTTP
            ua.contains("Cronet/", ignoreCase = true) -> HttpStack.CRONET
            ua.equals("Ktor client", ignoreCase = true) || ua.startsWith("Ktor client/", ignoreCase = true) -> HttpStack.KTOR
            // Volley's default HurlStack sends the exact same Dalvik/... string as raw
            // HttpURLConnection (see class doc) -- deliberately NOT distinguished here.
            ua.startsWith("Dalvik/", ignoreCase = true) -> HttpStack.HTTP_URL_CONNECTION
            else -> HttpStack.UNKNOWN
        }
    }

    /**
     * Which of the known HTTP client libraries requestr itself has on its classpath, via
     * presence checks. `HttpURLConnection` is always present -- it ships in the Android/JDK
     * standard library, no third-party dependency required.
     */
    fun detectAvailableOnClasspath(): Set<HttpStack> {
        val found = mutableSetOf(HttpStack.HTTP_URL_CONNECTION)
        if (classExists("okhttp3.OkHttpClient")) found.add(HttpStack.OKHTTP)
        if (classExists("org.chromium.net.CronetEngine")) found.add(HttpStack.CRONET)
        if (classExists("io.ktor.client.HttpClient")) found.add(HttpStack.KTOR)
        if (classExists("com.android.volley.toolbox.Volley")) found.add(HttpStack.VOLLEY)
        return found
    }

    private fun classExists(name: String): Boolean =
        try {
            Class.forName(name)
            true
        } catch (_: ClassNotFoundException) {
            false
        }

    /**
     * Real, session-scoped stack breakdown: classifies every [com.example.data.local.CapturedTrafficEntity]
     * row already persisted for [sessionId] by its User-Agent header (via [detectFromHeaders]),
     * rather than only reporting classpath presence. A session with zero captured requests
     * legitimately returns a report with [StackDetectionReport.totalRequests] == 0 and an empty
     * [StackDetectionReport.countsByStack] -- not a fabricated baseline.
     */
    suspend fun analyzeSession(context: Context, sessionId: Long): StackDetectionReport {
        val db = RequestrDatabase.get(context)
        val rows = db.capturedTrafficDao().getBySession(sessionId)
        val counts = linkedMapOf<HttpStack, Int>()
        for (row in rows) {
            val stack = detectFromHeaders(row.requestHeaders)
            counts[stack] = (counts[stack] ?: 0) + 1
        }
        return StackDetectionReport(
            sessionId = sessionId,
            totalRequests = rows.size,
            countsByStack = counts,
            classpathAvailable = detectAvailableOnClasspath(),
        )
    }
}

/** Real, computed-from-Room stack breakdown for one capture session. */
data class StackDetectionReport(
    val sessionId: Long,
    val totalRequests: Int,
    val countsByStack: Map<HttpStack, Int>,
    val classpathAvailable: Set<HttpStack>,
)
