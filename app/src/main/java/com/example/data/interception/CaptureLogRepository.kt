package com.example.data.interception

import android.content.Context
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import com.example.data.models.TrafficSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The single read path for the capture log.
 *
 * v2.1 rendered the Intercept list from an in-memory list seeded with fabricated rows and topped
 * up by a `Random` generator, so the screen showed traffic that was never observed
 * (APP_v2.md Bug 3.2). The list now derives exclusively from the `captured_traffic` Room table,
 * which only the real capture engine writes to. If nothing has been captured, this emits an empty
 * list and the UI must show a genuine empty state.
 */
object CaptureLogRepository {

    /** Live traffic log, newest first, mapped to the UI model. Empty until the engine writes. */
    fun observeTraffic(context: Context): Flow<List<CapturedTrafficItem>> =
        RequestrDatabase.get(context).capturedTrafficDao().observeAll()
            .map { rows -> rows.map { it.toUiModel() } }

    /** Real row count, used for the "ALL (n)" chip so the number cannot drift from the list. */
    fun observeCount(context: Context): Flow<Int> =
        RequestrDatabase.get(context).capturedTrafficDao().observeCount()

    /** One-shot snapshot of all traffic entries, ordered newest first. Used for JSON export. */
    suspend fun getAllTraffic(context: Context): List<CapturedTrafficItem> = withContext(Dispatchers.IO) {
        RequestrDatabase.get(context).capturedTrafficDao().getAll()
            .map { it.toUiModel() }
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        RequestrDatabase.get(context).capturedTrafficDao().clearAll()
    }

    suspend fun deleteEntry(context: Context, uiId: String) = withContext(Dispatchers.IO) {
        uiId.toLongOrNull()?.let {
            RequestrDatabase.get(context).capturedTrafficDao().deleteById(it)
        }
        Unit
    }

    /**
     * Real existence check backing the "Block this domain" long-press action.
     *
     * v2.1 rendered that row greyed-out unconditionally, which looked like state but was a
     * static style (APP_v2.md Bug 3.8). This queries the rules table so the row is disabled
     * only when a block rule genuinely exists for the host.
     */
    fun observeHasBlockRule(context: Context, domain: String): Flow<Boolean> =
        RequestrDatabase.get(context).interceptRuleDao().observeHasBlockRuleForDomain(domain)

    suspend fun hasBlockRule(context: Context, domain: String): Boolean = withContext(Dispatchers.IO) {
        RequestrDatabase.get(context).interceptRuleDao().hasBlockRuleForDomain(domain)
    }

    /** Persists a real block rule for [domain]; the disabled state above then follows from it. */
    suspend fun addBlockRule(context: Context, domain: String) = withContext(Dispatchers.IO) {
        if (domain.isBlank()) return@withContext
        val dao = RequestrDatabase.get(context).interceptRuleDao()
        if (dao.hasBlockRuleForDomain(domain)) return@withContext
        dao.insert(
            InterceptRuleEntity(
                name = "Block $domain",
                pattern = Regex.escape(domain),
                domain = domain,
                action = RuleAction.BLOCK,
                enabled = true,
            )
        )
        Unit
    }

    /** Creates an intercept rule seeded from an observed transaction. */
    suspend fun addRuleFromTransaction(
        context: Context,
        item: CapturedTrafficItem,
        action: RuleAction,
    ) = withContext(Dispatchers.IO) {
        RequestrDatabase.get(context).interceptRuleDao().insert(
            InterceptRuleEntity(
                name = "${item.method.name} ${item.path}".trim(),
                pattern = Regex.escape(item.url),
                domain = item.host,
                action = action,
                enabled = true,
            )
        )
        Unit
    }

    /**
     * Seeds default intercept rules on first launch. These are practical, useful-out-of-the-box
     * rules that demonstrate the rules engine and provide real utility:
     *
     * 1. Block common ad/tracker domains
     * 2. Block analytics telemetry
     * 3. Log authentication endpoints (useful for debugging OAuth flows)
     * 4. Block known fingerprint/tracking scripts
     * 5. Log API error responses (5xx) for debugging
     *
     * Called once when the database is created (empty rules table).
     */
    suspend fun seedDefaultRules(context: Context) = withContext(Dispatchers.IO) {
        val dao = RequestrDatabase.get(context).interceptRuleDao()
        val existing = dao.getEnabledRules()
        if (existing.isNotEmpty()) return@withContext // Already seeded or user has rules

        val defaults = listOf(
            InterceptRuleEntity(
                name = "Block Ad Trackers",
                pattern = ".*(doubleclick\\.net|googlesyndication\\.com|adservice\\.google\\.com|ads\\.facebook\\.com|analyticsengine\\.s3\\.amazonaws\\.com).*",
                domain = "doubleclick.net",
                action = RuleAction.BLOCK,
                enabled = true,
            ),
            InterceptRuleEntity(
                name = "Block Analytics",
                pattern = ".*(google-analytics\\.com|analytics\\.google\\.com|segment\\.io|mixpanel\\.com|amplitude\\.com|hotjar\\.com).*",
                domain = "google-analytics.com",
                action = RuleAction.BLOCK,
                enabled = true,
            ),
            InterceptRuleEntity(
                name = "Log Auth Endpoints",
                pattern = ".*(auth|login|oauth|token|signin|signup|session).*",
                domain = "",
                action = RuleAction.LOG_ONLY,
                enabled = true,
            ),
            InterceptRuleEntity(
                name = "Block Fingerprinting",
                pattern = ".*(fingerprint|fpjs\\.io|fingerprintjs\\.com|browser-update\\.org|cdn\\.cookielaw\\.org).*",
                domain = "fpjs.io",
                action = RuleAction.BLOCK,
                enabled = true,
            ),
            InterceptRuleEntity(
                name = "Log API Errors",
                pattern = ".*/api/.*",
                domain = "",
                action = RuleAction.LOG_ONLY,
                enabled = true,
            ),
        )

        defaults.forEach { rule ->
            runCatching { dao.insert(rule) }
        }
    }
}

/**
 * Maps a persisted transaction to the UI model.
 *
 * Absent values stay visibly absent: an un-decoded response has no status code, so the UI shows
 * a real "pending/opaque" marker rather than defaulting to 200 OK.
 */
internal fun CapturedTrafficEntity.toUiModel(): CapturedTrafficItem = CapturedTrafficItem(
    id = id.toString(),
    method = runCatching { HttpMethod.valueOf(method.uppercase()) }.getOrDefault(HttpMethod.GET),
    url = url,
    host = host,
    path = path,
    statusCode = statusCode ?: 0,
    statusMessage = statusCode?.let { httpStatusMessage(it) } ?: "",
    protocol = protocol,
    latencyMs = latencyMs,
    requestSize = requestSizeBytes,
    responseSize = responseSizeBytes,
    requestHeaders = requestHeaders,
    responseHeaders = responseHeaders,
    requestBody = requestBody ?: "",
    responseBody = responseBody ?: "",
    timestamp = timestamp,
    appPackage = appPackage ?: "",
    appName = appPackage ?: "",
    isPinned = false,
    isBlocked = false,
    isModified = false,
    // T2: rows written by GatewayEngine set sourceIp to the remote LAN client's address —
    // surface that distinctly in the traffic list/detail sheet rather than as plain "Native".
    // SDK-sourced rows (LocalApiServer.receiveObserverEvents, under CaptureTier.SDK) are the
    // only ones written with protocol == "sdk" — no CaptureTier join available here, so this
    // is the stable per-row signal to distinguish them.
    trafficSource = when {
        protocol == "sdk" -> TrafficSource.SDK
        sourceIp != null -> TrafficSource.GATEWAY
        else -> TrafficSource.NATIVE
    },
    tags = if (sourceIp != null) listOf("gateway:$sourceIp") else emptyList(),
)

private fun httpStatusMessage(code: Int): String = when (code) {
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    408 -> "Request Timeout"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> ""
}
