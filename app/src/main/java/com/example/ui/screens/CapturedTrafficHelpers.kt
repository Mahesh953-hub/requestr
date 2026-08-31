package com.example.ui.screens

import com.example.data.local.RuleAction
import com.example.data.models.CapturedTrafficItem
import com.example.ui.components.formatByteCount
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Master plan §3 LOW-ADD — pure helpers for [CapturedDetailBottomSheet]'s Body tab and the
 * captures list: content-type-gated rendering, JSON pretty-print with fallback, a >100KB render
 * gate, multi-key sort, aggregate stats, and image-response detection for a gallery view.
 *
 * Kept out of `ResponseInspectorView.kt` deliberately -- that file's `RichResponseInspector` is
 * ApiTestr-scoped (`Assertions`/`onRunTests`/`onBeautifyBody` params, currently unused anywhere
 * in the codebase) and out of scope for capture-log rendering. These are plain functions so they
 * follow the existing `ResponseInspectorHelpersTest`-style "pure logic -> plain JUnit" convention.
 */

/** How [CapturedDetailBottomSheet]'s Body tab should render a given body. */
enum class BodyRenderKind {
    JSON,
    XML_OR_HTML,
    IMAGE,
    PLAIN_TEXT,
    /** Binary/unknown content-type with no safe text rendering (e.g. octet-stream, video, audio). */
    UNRENDERABLE_BINARY,
}

/** Case-insensitive `content-type` header lookup, stripping any `; charset=...` parameter. */
fun contentTypeOf(headers: Map<String, String>): String? {
    val raw = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        ?: return null
    return raw.substringBefore(';').trim().lowercase()
}

/**
 * Classifies how a body should render, purely from its declared content-type (falling back to a
 * best-effort sniff of [bodySample] when [contentType] is missing/generic, e.g. a bare
 * `application/octet-stream` proxy that still carries a JSON body).
 */
fun detectBodyRenderKind(contentType: String?, bodySample: String): BodyRenderKind {
    val ct = contentType?.lowercase()
    return when {
        ct == null -> sniffBodyRenderKind(bodySample)
        ct.contains("json") -> BodyRenderKind.JSON
        ct.contains("xml") || ct.contains("html") -> BodyRenderKind.XML_OR_HTML
        ct.startsWith("image/") -> BodyRenderKind.IMAGE
        ct.startsWith("text/") -> BodyRenderKind.PLAIN_TEXT
        ct.startsWith("audio/") || ct.startsWith("video/") || ct.contains("octet-stream") ||
            ct.contains("pdf") || ct.contains("zip") -> sniffBodyRenderKind(bodySample, defaultBinary = true)
        else -> BodyRenderKind.PLAIN_TEXT
    }
}

private fun sniffBodyRenderKind(bodySample: String, defaultBinary: Boolean = false): BodyRenderKind {
    val trimmed = bodySample.trim()
    return when {
        trimmed.isEmpty() -> BodyRenderKind.PLAIN_TEXT
        (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]")) -> BodyRenderKind.JSON
        trimmed.startsWith("<") -> BodyRenderKind.XML_OR_HTML
        defaultBinary -> BodyRenderKind.UNRENDERABLE_BINARY
        else -> BodyRenderKind.PLAIN_TEXT
    }
}

/**
 * Pretty-prints [body] as JSON with 2-space indentation. Returns [body] unchanged (never throws)
 * if it isn't valid JSON -- e.g. a response whose content-type merely claims `application/json`
 * but whose body is truncated, empty, or actually something else.
 */
fun prettyPrintJsonOrFallback(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return body
    return runCatching {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> return body
        }
    }.getOrDefault(body)
}

/** Body render gate: bodies over this size render a "too large" affordance instead of raw text. */
const val MAX_RENDERABLE_BODY_BYTES = 100 * 1024

/** Whether a body of [bodySizeBytes] should be gated behind a "show anyway" affordance. */
fun shouldGateBodyRendering(bodySizeBytes: Int): Boolean = bodySizeBytes > MAX_RENDERABLE_BODY_BYTES

// ── multi-key sort ──────────────────────────────────────────────────────

enum class CaptureSortKey { TIMESTAMP, LATENCY, STATUS, METHOD, HOST }

data class CaptureSortSpec(val key: CaptureSortKey, val descending: Boolean = true)

/**
 * Sorts [items] by the ordered list of [specs], each subsequent spec breaking ties left by the
 * previous one -- e.g. `[STATUS desc, LATENCY desc]` groups by status code, slowest-first within
 * each status. An empty [specs] list returns [items] unchanged (in its original order).
 */
fun sortCaptures(items: List<CapturedTrafficItem>, specs: List<CaptureSortSpec>): List<CapturedTrafficItem> {
    if (specs.isEmpty()) return items
    var comparator: Comparator<CapturedTrafficItem>? = null
    for (spec in specs) {
        val base: Comparator<CapturedTrafficItem> = when (spec.key) {
            CaptureSortKey.TIMESTAMP -> Comparator.comparingLong { it.timestamp }
            CaptureSortKey.LATENCY -> Comparator.comparingLong { it.latencyMs }
            CaptureSortKey.STATUS -> Comparator.comparingInt { it.statusCode }
            CaptureSortKey.METHOD -> Comparator.comparing { it.method.name }
            CaptureSortKey.HOST -> Comparator.comparing { it.host }
        }
        val oriented = if (spec.descending) base.reversed() else base
        comparator = comparator?.thenComparing(oriented) ?: oriented
    }
    return items.sortedWith(comparator!!)
}

/**
 * C6: short human-readable summary of the active sort, e.g. "Time↓" or "Status↓, Latency↑" —
 * used as the always-visible label on the sort entry-point chip so the effect of the current
 * sort is never a silent, invisible change. Returns "Newest" for the empty-list case (matches
 * [sortCaptures]'s "unchanged order" behavior, which for CaptureLogRepository's real query is
 * newest-first).
 */
fun describeSortSpecs(specs: List<CaptureSortSpec>): String {
    if (specs.isEmpty()) return "Newest"
    val labels = mapOf(
        CaptureSortKey.TIMESTAMP to "Time",
        CaptureSortKey.LATENCY to "Latency",
        CaptureSortKey.STATUS to "Status",
        CaptureSortKey.METHOD to "Method",
        CaptureSortKey.HOST to "Host"
    )
    return specs.joinToString(", ") { spec ->
        "${labels[spec.key]}${if (spec.descending) "↓" else "↑"}"
    }
}

// ── aggregate stats ─────────────────────────────────────────────────────

data class CaptureStats(
    val total: Int,
    val errorCount: Int,
    val byMethod: Map<String, Int>,
    val averageLatencyMs: Double,
    val totalBytes: Long,
    /** Status codes bucketed into "2xx"/"3xx"/"4xx"/"5xx"; a genuinely-unset `0` bucket as "0xx". */
    val byStatusClass: Map<String, Int> = emptyMap(),
    /** Hosts ordered by request count, descending, capped to [STATS_TOP_HOSTS_LIMIT] entries. */
    val topHosts: List<Pair<String, Int>> = emptyList(),
    val averageBytesPerRequest: Double = 0.0,
    val minLatencyMs: Long = 0L,
    val maxLatencyMs: Long = 0L,
)

/** How many entries [computeCaptureStats]'s `topHosts` keeps -- a dashboard, not an audit log. */
const val STATS_TOP_HOSTS_LIMIT = 5

/**
 * Aggregate counters for a captures list -- mirrors `LanDashboardServer.dashStats`'s shape, plus
 * the status-code distribution, top hosts, and latency spread the C5 stats dashboard needs.
 * A pure function so it's unit-testable without a composable or a live Room database.
 */
fun computeCaptureStats(items: List<CapturedTrafficItem>): CaptureStats {
    if (items.isEmpty()) return CaptureStats(0, 0, emptyMap(), 0.0, 0L)
    val errorCount = items.count { it.statusCode >= 400 }
    val byMethod = items.groupingBy { it.method.name }.eachCount()
    val latencies = items.map { it.latencyMs }
    val avgLatency = latencies.average()
    val totalBytes = items.sumOf { it.requestSize + it.responseSize }
    val byStatusClass = items
        .groupingBy { statusClassOf(it.statusCode) }
        .eachCount()
    val topHosts = items
        .groupingBy { it.host }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(STATS_TOP_HOSTS_LIMIT)
        .map { it.key to it.value }
    return CaptureStats(
        total = items.size,
        errorCount = errorCount,
        byMethod = byMethod,
        averageLatencyMs = avgLatency,
        totalBytes = totalBytes,
        byStatusClass = byStatusClass,
        topHosts = topHosts,
        averageBytesPerRequest = totalBytes.toDouble() / items.size,
        minLatencyMs = latencies.min(),
        maxLatencyMs = latencies.max(),
    )
}

/** `0` (never decoded) maps to "0xx" rather than the misleading "0xx"-looking "-xx". */
private fun statusClassOf(statusCode: Int): String = "${statusCode / 100}xx"

// ── image gallery ────────────────────────────────────────────────────────

/** Whether a response content-type indicates an image body worth showing in a gallery view. */
fun isImageResponse(contentType: String?): Boolean = contentType?.lowercase()?.startsWith("image/") == true

/** Filters to captures whose response content-type is an image, preserving original order. */
fun extractImageItems(items: List<CapturedTrafficItem>): List<CapturedTrafficItem> =
    items.filter { isImageResponse(contentTypeOf(it.responseHeaders)) }

/**
 * C1: strips a `data:<mime>;base64,` URI prefix if present, leaving just the raw base64 payload
 * -- some APIs return image bodies this way as real text (`Content-Type: image/png` but a
 * `data:image/png;base64,...` string body), which is genuinely renderable. Pure string handling
 * only; the actual base64 decode + bitmap decode happens in the UI layer (android.util.Base64 /
 * BitmapFactory aren't meaningfully unit-testable under this project's plain-JVM unit tests --
 * see build.gradle.kts's `isReturnDefaultValues` note). Returns [body] unchanged when there's no
 * recognizable prefix.
 */
fun stripDataUriPrefix(body: String): String {
    if (!body.startsWith("data:")) return body
    val markerIndex = body.indexOf("base64,")
    return if (markerIndex >= 0) body.substring(markerIndex + "base64,".length) else body
}

// ── repeat/replay schedule (C7) ─────────────────────────────────────────

/**
 * Computes the delay (ms) to wait *before* sending each of [count] repeat sends of a
 * [RepeatReplayDialog]-driven replay, so the dialog/coroutine loop that actually performs the
 * network sends never has to touch [Random] or timing math itself -- that logic is pure and
 * lives entirely here, so it's unit-testable with a seeded [random] and no real `delay()`/sleep.
 *
 * The first send is always immediate (index 0's delay is `0`); every subsequent send waits
 * [intervalMs] plus a uniform jitter in `[-jitterMs, +jitterMs]`, clamped to never go negative
 * (a negative delay would mean "send early", which no caller can act on).
 */
fun computeReplaySchedule(
    count: Int,
    intervalMs: Long,
    jitterMs: Long,
    random: Random = Random.Default,
): List<Long> {
    require(count >= 0) { "count must be >= 0, was $count" }
    require(intervalMs >= 0) { "intervalMs must be >= 0, was $intervalMs" }
    require(jitterMs >= 0) { "jitterMs must be >= 0, was $jitterMs" }
    if (count == 0) return emptyList()
    return List(count) { index ->
        if (index == 0) return@List 0L
        val jitterDelta = if (jitterMs == 0L) 0L else random.nextLong(-jitterMs, jitterMs + 1)
        (intervalMs + jitterDelta).coerceAtLeast(0L)
    }
}

// ── rule action display (§2.14(b) BODY_ENCRYPT_AES/BODY_DECRYPT_AES follow-up) ──────────
//
// [RulesConfigBottomSheet] previously rendered every non-BLOCK/LOG_ONLY action through a single
// `else` branch -- harmless for the original 7-value enum (they all got the same generic
// "secondary" color), but [RuleAction] has since grown two more values
// ([RuleAction.BODY_ENCRYPT_AES]/[RuleAction.BODY_DECRYPT_AES]) that are meaningfully different
// from a plain modifier and deserve their own visual identity, not a silent fallthrough. These
// two functions are the single source of truth for every `RuleAction` label/color in the rules
// UI -- exhaustive `when`s (no `else`), so adding a future `RuleAction` value is a compile error
// here until it's given a real label and color, rather than quietly falling back to something
// generic.

/** Human-readable label for a persisted [RuleAction], e.g. `"Body Encrypt (AES)"`. */
fun ruleActionLabel(action: RuleAction): String = when (action) {
    RuleAction.BLOCK -> "Block"
    RuleAction.REDIRECT -> "Redirect"
    RuleAction.MODIFY_REQUEST -> "Modify Request"
    RuleAction.MODIFY_RESPONSE -> "Modify Response"
    RuleAction.LOG_ONLY -> "Log Only"
    RuleAction.FORCE_MITM -> "Force MITM"
    RuleAction.MOCK_RESPONSE -> "Mock Response"
    RuleAction.SCRIPT_REWRITE -> "Script Rewrite"
    RuleAction.CHAOS -> "Chaos"
    RuleAction.BODY_ENCRYPT_AES -> "Body Encrypt (AES)"
    RuleAction.BODY_DECRYPT_AES -> "Body Decrypt (AES)"
}

/** ARGB color for [action]'s row/badge -- same `0xFF...` `Long` convention as [BadgePill]. */
fun ruleActionColorHex(action: RuleAction): Long = when (action) {
    RuleAction.BLOCK -> 0xFFEF4444
    RuleAction.REDIRECT -> 0xFF8B5CF6
    RuleAction.MODIFY_REQUEST -> 0xFF3B82F6
    RuleAction.MODIFY_RESPONSE -> 0xFF6366F1
    RuleAction.LOG_ONLY -> 0xFF64748B
    RuleAction.FORCE_MITM -> 0xFFF59E0B
    RuleAction.MOCK_RESPONSE -> 0xFF10B981
    RuleAction.SCRIPT_REWRITE -> 0xFF14B8A6
    RuleAction.CHAOS -> 0xFFEC4899
    RuleAction.BODY_ENCRYPT_AES -> 0xFF06B6D4
    RuleAction.BODY_DECRYPT_AES -> 0xFF0EA5E9
}

/** Whether [action] is one of the two AES body-crypto actions that need a key alias/scope. */
fun isBodyCryptoAction(action: RuleAction): Boolean =
    action == RuleAction.BODY_ENCRYPT_AES || action == RuleAction.BODY_DECRYPT_AES

/**
 * The `InterceptRuleEntity.cryptoDirection` value implied by [action]. `RuleEnforcer.applyBodyCrypto`'s
 * KDoc documents `cryptoDirection` as informational metadata only -- the actual encrypt-vs-decrypt
 * operation is driven by the `action` value itself -- so the rule editor derives (and keeps in
 * sync) this field from the selected action rather than exposing it as an independently
 * editable control that could silently drift out of sync with what the rule actually does.
 */
fun impliedCryptoDirection(action: RuleAction): String =
    if (action == RuleAction.BODY_DECRYPT_AES) "DECRYPT" else "ENCRYPT"

/** Whether [action]'s `replacementValue` is used at all -- the three body-replacement actions. */
fun supportsReplacementValue(action: RuleAction): Boolean = when (action) {
    RuleAction.MOCK_RESPONSE, RuleAction.MODIFY_REQUEST, RuleAction.MODIFY_RESPONSE -> true
    else -> false
}

/**
 * Inline validation for a crypto rule's key alias -- returns a user-facing error string, or
 * `null` when [keyAlias] is fine. A blank alias on a [RuleAction.BODY_ENCRYPT_AES]/
 * [RuleAction.BODY_DECRYPT_AES] rule is a hard configuration error per
 * [com.example.interception.RequestCryptoManager]'s fail-closed contract (it returns a
 * `CryptoResult.Failure` for a blank alias, never a silent pass-through) -- the rule editor must
 * block saving such a rule rather than let the user discover the failure only once the rule
 * actually fires in production traffic.
 */
fun cryptoKeyAliasError(action: RuleAction, keyAlias: String): String? =
    if (isBodyCryptoAction(action) && keyAlias.isBlank()) {
        "Key alias is required for ${ruleActionLabel(action)} -- a blank alias will fail at runtime."
    } else {
        null
    }

// ── CA-untrusted MITM degrade visibility (C12) ──────────────────────────────────────────
//
// MitmProxyEngine.shouldDegradeForUntrustedCa degrades a FORCE_MITM CONNECT to a raw
// ("ALLOWED_OPAQUE") relay when requestr's own CA isn't trusted client-side, and records a
// specific, non-null `failureReason` on that ConnectAttemptEntity row explaining exactly that
// (see MitmProxyEngine.kt's `persistConnectAttempt(..., "ALLOWED_OPAQUE", failureReason = ...)`
// call in that branch). Every OTHER site that persists an "ALLOWED_OPAQUE" row (a plain
// AllowOpaque disposition with no FORCE_MITM rule involved at all) passes no `failureReason`, so
// "ALLOWED_OPAQUE" + a non-null `failureReason` is a real, engine-recorded signal for this exact
// case -- not a heuristic invented in the UI layer.

/**
 * Whether a persisted `ConnectAttemptEntity` row represents a connection that a `FORCE_MITM`
 * rule wanted decrypted, but which requestr degraded to an undecrypted raw relay because its own
 * CA isn't trusted client-side (C12). See this section's header comment for why this exact
 * `disposition`+`failureReason` combination is the real signal, not a guess.
 */
fun isCaUntrustedDegrade(disposition: String, failureReason: String?): Boolean =
    disposition == "ALLOWED_OPAQUE" && failureReason != null

// ── binary response/request body storage state (§2.14 binary-body persistence) ──────────
//
// [CapturedTrafficEntity] now stores real bytes for a binary body ([HttpBodyDecoder]'s
// isRequestBodyBinary/isResponseBodyBinary + requestBodyBytes/responseBodyBytes), capped at
// [HttpBodyDecoder.MAX_STORED_BODY_BYTES]. The UI must render three visually distinct states,
// never conflate "no body" with "body exists but wasn't stored", and never claim bytes exist
// when they don't -- see [BodyStorageState]'s doc.

/** How a captured request/response body's bytes are actually available for rendering. */
enum class BodyStorageState {
    /** Not flagged binary -- render the (possibly empty) text body as today. */
    TEXT,

    /** Flagged binary and within the storage cap -- the real bytes are available to render. */
    BINARY_STORED,

    /** Flagged binary but the true size exceeded the storage cap -- no bytes were kept, only
     *  the true size is known. Must never be shown as an empty/missing body. */
    BINARY_EXCEEDED_CAP,
}

/** Classifies a body's storage state from the exact fields [CapturedTrafficEntity] persists. */
fun classifyBodyStorageState(isBinary: Boolean, truncated: Boolean): BodyStorageState = when {
    !isBinary -> BodyStorageState.TEXT
    truncated -> BodyStorageState.BINARY_EXCEEDED_CAP
    else -> BodyStorageState.BINARY_STORED
}

/**
 * User-facing explanation for [BodyStorageState.BINARY_EXCEEDED_CAP] -- names the real observed
 * size ([trueSizeBytes], always accurate per [CapturedTrafficEntity.requestSizeBytes]/
 * `responseSizeBytes`'s "always the true byte length" contract) and the real cap, rather than a
 * generic "binary not stored" placeholder that hides why.
 */
fun exceededCapMessage(trueSizeBytes: Long, capBytes: Long): String =
    "This body is ${formatByteCount(trueSizeBytes)} -- larger than the " +
        "${formatByteCount(capBytes)} capture cap, so its bytes weren't stored. Only the size is known."

/**
 * Item 2 (audit §7.2): the fixed set of header names whose VALUE must be masked by default in
 * [CapturedDetailBottomSheet]'s request/response header views. Case-insensitive by construction
 * (see [isSensitiveHeaderName]) -- HTTP header names are case-insensitive per RFC 7230 §3.2, and
 * a naive case-sensitive set here would be a silent leak the moment a server/client capitalizes
 * a header differently (e.g. `set-cookie` vs `Set-Cookie`).
 */
private val SENSITIVE_HEADER_NAMES = setOf(
    "set-cookie",
    "cookie",
    "authorization",
    "proxy-authorization",
)

/**
 * Item 2: pure predicate -- is [headerName] one of [SENSITIVE_HEADER_NAMES]? Case-insensitive,
 * matches on the trimmed name so incidental leading/trailing whitespace from a raw header map
 * doesn't defeat the check. Unknown/other header names (including anything not in the fixed
 * set, e.g. `Content-Type`) are correctly left unaffected -- this function has no "fuzzy" or
 * prefix matching, only exact (case-insensitive) name equality, so it can't over-mask.
 */
fun isSensitiveHeaderName(headerName: String): Boolean =
    headerName.trim().lowercase() in SENSITIVE_HEADER_NAMES

/**
 * Item 2: pure mask-formatting for a sensitive header's value. Deliberately NOT
 * one-asterisk-per-character -- rendering a mask whose glyph-count scales with the real value's
 * length would leak exactly the kind of length signal (e.g. "this bearer token is 812 characters
 * long") that defeats the point of masking a session token or API key in the first place. Instead
 * this renders a fixed-width placeholder plus the real byte length as separate, explicit text,
 * so the user gets a useful signal ("there's a value here, and it's this big") without the
 * masked glyphs themselves encoding the value's shape.
 *
 * An empty value masks to just the placeholder with no byte-count suffix (there is nothing to
 * report a length for, and "0 bytes" would be redundant with an empty value that isn't secret
 * anyway -- but [isSensitiveHeaderName] is checked by the caller, not here, so an empty value
 * still reaching this function is masked like any other for consistency).
 */
fun maskHeaderValue(value: String): String {
    val byteLength = value.toByteArray(Charsets.UTF_8).size
    return if (byteLength == 0) HEADER_MASK_PLACEHOLDER else "$HEADER_MASK_PLACEHOLDER ($byteLength bytes)"
}

private const val HEADER_MASK_PLACEHOLDER = "•••••••••"

/**
 * Audit fix (StatusCodeBadge "0" defect): the text [com.example.ui.components.StatusCodeBadge]
 * shows for a row with [CapturedTrafficItem.hasKnownStatus] false. Reuses the row's own
 * already-computed [com.example.data.models.Http3RowState] label ("OBSERVED"/"DETECTED") so the
 * status pill agrees with the [com.example.ui.components.Http3RowIndicator] pill beside it
 * instead of inventing separate wording -- matches the `http3-row-indicators-*` mockups, which
 * show the exact same label in both places. Falls back to an em dash for a statusless row this
 * app can't attribute to HTTP/3/QUIC at all (e.g. a fully opaque undecoded-TLS row), which is
 * `StatusCodeBadge`'s own default anyway. Pure function, no Android/Compose dependency.
 */
fun unknownStatusLabelFor(h3State: com.example.data.models.Http3RowState?): String =
    when (h3State) {
        com.example.data.models.Http3RowState.OBSERVED -> "OBSERVED"
        com.example.data.models.Http3RowState.DETECTED -> "DETECTED"
        else -> "—"
    }

/**
 * Lowercase-hex + printable-ASCII dump of the first [maxBytes] of [bytes], 16 bytes per row --
 * a plain-text summary view for a stored binary body that isn't an image (or whose image decode
 * failed). Pure and small enough to unit test without touching Android's `BitmapFactory`.
 */
fun hexDump(bytes: ByteArray, maxBytes: Int = 512): String {
    if (bytes.isEmpty()) return ""
    val shown = bytes.take(maxBytes)
    val lines = shown.chunked(16).mapIndexed { rowIndex, row ->
        val offset = (rowIndex * 16).toString(16).padStart(6, '0')
        val hex = row.joinToString(" ") { "%02x".format(it) }.padEnd(16 * 3 - 1)
        val ascii = row.joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7e) c.toChar().toString() else "."
        }
        "$offset  $hex  $ascii"
    }
    val body = lines.joinToString("\n")
    return if (bytes.size > maxBytes) {
        "$body\n… (${bytes.size - maxBytes} more byte(s), ${formatByteCount(bytes.size.toLong())} total)"
    } else {
        body
    }
}
