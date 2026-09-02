package com.example.ui.screens

import com.example.data.models.ApiResponseData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure, JVM-testable state behind API Testr's "Compare runs" sheet (the [ApiTestrScreen] call
 * site of `DiffView`).
 *
 * Scope, stated honestly: requestr persists request *configs* (`saved_api_requests`) but no
 * response bodies, so there is no cross-session run history to diff against. What the app does
 * genuinely hold is the response of every send made in this process. The screen therefore keeps
 * the *last run per request id* for the current session ([recordRun]) and offers
 * "what changed since the previous send of this request?" -- real bodies as received, in-memory,
 * nothing fabricated and nothing persisted.
 */

/** One completed send: which request produced it plus the response exactly as received. */
data class ApiRunSnapshot(
    val requestId: String,
    val method: String,
    val url: String,
    val response: ApiResponseData,
)

/**
 * Whether the compare sheet has two runs to work with.
 *
 * `NO_PREVIOUS_RUN` is the "only one response so far" state -- the request has been sent once in
 * this session. `READY` means both bodies exist and `buildDiffModel` decides the rest
 * (identical / additions / deletions / mixed / binary / too large / both empty).
 */
enum class ResponseCompareAvailability { NO_PREVIOUS_RUN, READY }

fun responseCompareAvailability(previous: ApiRunSnapshot?): ResponseCompareAvailability =
    if (previous == null) ResponseCompareAvailability.NO_PREVIOUS_RUN else ResponseCompareAvailability.READY

/** Explanatory copy for the non-READY state; `null` when a diff can be shown. */
fun responseCompareMessage(availability: ResponseCompareAvailability): String? = when (availability) {
    ResponseCompareAvailability.NO_PREVIOUS_RUN ->
        "Only one response so far for this request in this session. Send it again and the two " +
            "bodies will be compared here, exactly as received."
    ResponseCompareAvailability.READY -> null
}

/**
 * Advances the per-request "last run" map after a send completes. Returns the run that the new
 * one replaces (the comparison baseline) and the updated map. Runs of *other* requests are left
 * alone, so sending A, then B, then A again still compares A's two runs.
 */
fun recordRun(
    lastRunByRequest: Map<String, ApiRunSnapshot>,
    run: ApiRunSnapshot,
): Pair<ApiRunSnapshot?, Map<String, ApiRunSnapshot>> {
    val previous = lastRunByRequest[run.requestId]
    return previous to (lastRunByRequest + (run.requestId to run))
}

/** "200 OK · 142 ms · 1.0 KB · 14:03:22" -- one line summarising a run for the sheet header. */
fun runSideLabel(run: ApiRunSnapshot, nowMillisForTime: Long = run.response.timestamp): String {
    val r = run.response
    val status = when {
        r.statusCode == 0 -> r.error?.takeIf { it.isNotBlank() }?.let { "Failed: $it" } ?: "Failed"
        r.statusMessage.isBlank() -> "${r.statusCode}"
        else -> "${r.statusCode} ${r.statusMessage}"
    }
    return listOf(
        status,
        "${r.latencyMs} ms",
        runFormatBytes(r.sizeBytes),
        runTimeOfDay(nowMillisForTime),
    ).joinToString(" · ")
}

/** True when the two runs were sent to a different URL or with a different method. */
fun runTargetsDiffer(previous: ApiRunSnapshot, current: ApiRunSnapshot): Boolean =
    previous.method != current.method || previous.url != current.url

fun runFormatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    return if (kb < 1024) String.format(Locale.US, "%.1f KB", kb)
    else String.format(Locale.US, "%.1f MB", kb / 1024.0)
}

private fun runTimeOfDay(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMillis))
