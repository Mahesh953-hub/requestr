package com.example.data.interception

import android.content.Context
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
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
