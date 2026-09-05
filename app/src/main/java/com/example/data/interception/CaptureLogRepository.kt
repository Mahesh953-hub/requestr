package com.example.data.interception

import android.content.Context
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptorRuleDao
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TransactionTimingEntity
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import com.example.data.models.TrafficSource
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.MatchCombine
import com.example.interception.rules.ObserveConfig
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleStage
import com.example.interception.rules.RuleValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    /**
     * Live traffic log, newest first, mapped to the UI model. Empty until the engine writes.
     * S2 task 18: joined with `transaction_timings` so the timing bars show measured phases; a
     * row without a timing row (or a phase that was not observed) keeps the `-1` "not measured"
     * sentinel of [CapturedTrafficItem].
     */
    fun observeTraffic(context: Context): Flow<List<CapturedTrafficItem>> {
        val db = RequestrDatabase.get(context)
        return combine(
            db.capturedTrafficDao().observeAll(),
            db.transactionTimingDao().observeAll(),
        ) { rows, timings ->
            val byId = timings.associateBy { it.trafficId }
            rows.map { it.toUiModel(byId[it.id]) }
        }
    }

    /**
     * S3 task 22: database-side search in the [com.example.data.search.TrafficSearchCompiler]
     * grammar (`status:5xx host:api OR body:"error"`, `NOT`, parentheses). Free text goes through
     * the FTS5 index when this runtime has it ([com.example.data.search.FtsSchema.available]) and
     * through `LIKE` otherwise, so the result set is the same either way. Newest first, capped at
     * [limit]. Blank queries return the newest [limit] rows.
     */
    suspend fun searchTraffic(context: Context, raw: String, sessionId: Long? = null, limit: Int = 500): List<CapturedTrafficItem> =
        withContext(Dispatchers.IO) {
            val db = RequestrDatabase.get(context)
            val compiled = com.example.data.search.TrafficSearchCompiler(useFts = com.example.data.search.FtsSchema.available.get())
                .compile(raw, sessionId, limit)
            val rows = db.trafficFtsDao().search(androidx.sqlite.db.SimpleSQLiteQuery(compiled.sql, compiled.args.toTypedArray()))
            if (rows.isEmpty()) return@withContext emptyList()
            val byId = db.transactionTimingDao().getAll().associateBy { it.trafficId }
            rows.map { it.toUiModel(byId[it.id]) }
        }

    /** Real row count, used for the "ALL (n)" chip so the number cannot drift from the list. */
    fun observeCount(context: Context): Flow<Int> =
        RequestrDatabase.get(context).capturedTrafficDao().observeCount()

    /** One-shot snapshot of all traffic entries, ordered newest first. Used for JSON export. */
    suspend fun getAllTraffic(context: Context): List<CapturedTrafficItem> = withContext(Dispatchers.IO) {
        val db = RequestrDatabase.get(context)
        val byId = db.transactionTimingDao().getAll().associateBy { it.trafficId }
        db.capturedTrafficDao().getAll().map { it.toUiModel(byId[it.id]) }
    }

    /**
     * Clears every captured row and (S2 task 16) every spilled body file. Rows go first so a
     * crash in between leaves orphan files (swept at next start), never rows pointing at nothing.
     */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        RequestrDatabase.get(context).capturedTrafficDao().clearAll()
        com.example.data.persistence.BodyStore.get(context).deleteAll()
        Unit
    }

    suspend fun deleteEntry(context: Context, uiId: String) = withContext(Dispatchers.IO) {
        uiId.toLongOrNull()?.let { id ->
            val dao = RequestrDatabase.get(context).capturedTrafficDao()
            val refs = dao.getSpilledBodyRef(id)
            dao.deleteById(id)
            if (refs != null) {
                val store = com.example.data.persistence.BodyStore.get(context)
                store.deleteBody(refs.requestBodyFilePath)
                store.deleteBody(refs.responseBodyFilePath)
            }
        }
        Unit
    }

    /**
     * Real existence check backing the "Block this domain" long-press action.
     *
     * v2.1 rendered that row greyed-out unconditionally, which looked like state but was a
     * static style (APP_v2.md Bug 3.8). This queries the rules table so the row is disabled
     * only when a block rule genuinely exists for the host. S4 task 34: the query runs against
     * the typed `interceptor_rules` (an enabled GATEWAY/BLOCK rule whose host glob is
     * `*<domain>*`, the exact shape [addBlockRule] and the v23 backfill write).
     */
    fun observeHasBlockRule(context: Context, domain: String): Flow<Boolean> =
        RequestrDatabase.get(context).interceptorRuleDao().observeHasBlockRuleForDomain(domain)

    suspend fun hasBlockRule(context: Context, domain: String): Boolean = withContext(Dispatchers.IO) {
        RequestrDatabase.get(context).interceptorRuleDao().hasBlockRuleForDomain(domain)
    }

    /** Persists a real block rule for [domain]; the disabled state above then follows from it. */
    suspend fun addBlockRule(context: Context, domain: String) = withContext(Dispatchers.IO) {
        if (domain.isBlank()) return@withContext
        val dao = RequestrDatabase.get(context).interceptorRuleDao()
        if (dao.hasBlockRuleForDomain(domain)) return@withContext
        insertTyped(
            dao,
            name = "Block $domain",
            matcher = RuleMatcherSpec(hostGlobs = listOf("*$domain*"), urlRegex = Regex.escape(domain), combine = MatchCombine.ANY),
            config = GatewayConfig(mode = GatewayMode.BLOCK, showBlockedInList = false),
        )
        Unit
    }

    /**
     * Creates a typed intercept rule seeded from an observed transaction: an exact-URL matcher
     * (plus the host) with the given [stage]'s empty config -- a REWRITE rule with no steps by
     * default, ready to be filled in from the rule editor. Anything but a rewrite/observe seed
     * is a caller bug; the quick action only ever offers those two.
     */
    suspend fun addRuleFromTransaction(
        context: Context,
        item: CapturedTrafficItem,
        stage: RuleStage = RuleStage.REWRITE,
    ) = withContext(Dispatchers.IO) {
        val config: RuleConfig = when (stage) {
            RuleStage.REWRITE -> RewriteConfig()
            RuleStage.OBSERVE -> ObserveConfig
            else -> throw IllegalArgumentException("addRuleFromTransaction seeds REWRITE or OBSERVE rules only, not $stage")
        }
        insertTyped(
            RequestrDatabase.get(context).interceptorRuleDao(),
            name = "${item.method.name} ${item.path}".trim(),
            matcher = RuleMatcherSpec(hostGlobs = listOf("*${item.host}*"), urlRegex = Regex.escape(item.url), combine = MatchCombine.ANY),
            config = config,
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
     * Called once when the database is created (empty rules table). The seeds are the typed
     * equivalents of the v1 defaults (GATEWAY/BLOCK with `*<domain>*` + URL regex, OBSERVE with a
     * URL regex) so a fresh v24 install and a migrated v22 install hold the same rules.
     */
    suspend fun seedDefaultRules(context: Context) = withContext(Dispatchers.IO) {
        val dao = RequestrDatabase.get(context).interceptorRuleDao()
        if (dao.count() > 0) return@withContext // Already seeded or user has rules

        data class Seed(val name: String, val pattern: String, val domain: String, val config: RuleConfig)
        val block = GatewayConfig(mode = GatewayMode.BLOCK, showBlockedInList = false)
        val defaults = listOf(
            Seed(
                "Block Ad Trackers",
                ".*(doubleclick\\.net|googlesyndication\\.com|adservice\\.google\\.com|ads\\.facebook\\.com|analyticsengine\\.s3\\.amazonaws\\.com).*",
                "doubleclick.net", block,
            ),
            Seed(
                "Block Analytics",
                ".*(google-analytics\\.com|analytics\\.google\\.com|segment\\.io|mixpanel\\.com|amplitude\\.com|hotjar\\.com).*",
                "google-analytics.com", block,
            ),
            Seed("Log Auth Endpoints", ".*(auth|login|oauth|token|signin|signup|session).*", "", ObserveConfig),
            Seed(
                "Block Fingerprinting",
                ".*(fingerprint|fpjs\\.io|fingerprintjs\\.com|browser-update\\.org|cdn\\.cookielaw\\.org).*",
                "fpjs.io", block,
            ),
            Seed("Log API Errors", ".*/api/.*", "", ObserveConfig),
        )

        defaults.forEach { seed ->
            runCatching {
                insertTyped(
                    dao,
                    name = seed.name,
                    matcher = RuleMatcherSpec(
                        hostGlobs = if (seed.domain.isNotEmpty()) listOf("*${seed.domain}*") else emptyList(),
                        urlRegex = seed.pattern,
                        combine = MatchCombine.ANY,
                    ),
                    config = seed.config,
                )
            }
        }
    }

    /** Appends a validated typed rule after the current tail; returns its id, or null when validation refuses it. */
    private suspend fun insertTyped(dao: InterceptorRuleDao, name: String, matcher: RuleMatcherSpec, config: RuleConfig): Long? {
        val now = System.currentTimeMillis()
        val rule = InterceptorRule(
            name = name,
            stage = config.stage,
            enabled = true,
            sortOrder = dao.maxSortOrder() + 1,
            matcher = matcher,
            config = config,
            createdAt = now,
            updatedAt = now,
        )
        if (RuleValidation.validate(rule, enabledRules = dao.countEnabled()).isNotEmpty()) return null
        return dao.insert(InterceptorRuleEntity.fromDomain(rule))
    }
}

/**
 * Maps a persisted transaction to the UI model.
 *
 * Absent values stay visibly absent: an un-decoded response has no status code, so the UI shows
 * a real "pending/opaque" marker rather than defaulting to 200 OK.
 */
/**
 * @param timings S2 task 18: the row's measured phases, when a `transaction_timings` row exists.
 *   NULL phases (not observed) map to the UI model's `-1` sentinel, which every consumer already
 *   renders as "not measured"; `downloadMs` is the `receive` phase.
 */
internal fun CapturedTrafficEntity.toUiModel(timings: TransactionTimingEntity? = null): CapturedTrafficItem = CapturedTrafficItem(
    id = id.toString(),
    method = runCatching { HttpMethod.valueOf(method.uppercase()) }.getOrDefault(HttpMethod.GET),
    url = url,
    host = host,
    path = path,
    statusCode = statusCode ?: 0,
    statusMessage = statusCode?.let { httpStatusMessage(it) } ?: "",
    protocol = protocol,
    latencyMs = latencyMs,
    dnsMs = timings?.dnsMs ?: -1,
    connectMs = timings?.connectMs ?: -1,
    tlsMs = timings?.tlsMs ?: -1,
    waitMs = timings?.waitMs ?: -1,
    downloadMs = timings?.receiveMs ?: -1,
    requestSize = requestSizeBytes,
    responseSize = responseSizeBytes,
    requestHeaders = requestHeaders,
    responseHeaders = responseHeaders,
    // S2 task 16: a spilled text body leaves only its UTF-8 preview on the row; surface that so
    // the list/detail views are not blank for it (the full body lives in the BodyStore file).
    requestBody = requestBody ?: requestBodyPreview ?: "",
    responseBody = responseBody ?: responseBodyPreview ?: "",
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
    // Item 1: derive the UI model's plain boolean from this row's own captureMethod, never
    // from the current global CaptureRecordMode setting -- a single database can contain rows
    // captured under different tiers across sessions/restarts.
    routedViaVpnRelay = captureMethod == com.example.data.local.CaptureRowMethod.VPN_TUN,
    // Audit fix (StatusCodeBadge "0" defect): derive truthfully from the entity's own nullable
    // statusCode -- the only point where that real nullability is collapsed to the `0`
    // placeholder above for legacy numeric call sites. Deriving this anywhere else (e.g. from
    // the already-collapsed `statusCode ?: 0` value via `statusCode == 0`) would just be the
    // same magic-number check moved one file over.
    hasKnownStatus = statusCode != null,
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
