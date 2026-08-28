package com.example.ui.screens

import com.example.data.models.CapturedTrafficItem
import org.json.JSONArray
import org.json.JSONObject

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

// ── aggregate stats ─────────────────────────────────────────────────────

data class CaptureStats(
    val total: Int,
    val errorCount: Int,
    val byMethod: Map<String, Int>,
    val averageLatencyMs: Double,
    val totalBytes: Long,
)

/** Aggregate counters for a captures list -- mirrors `LanDashboardServer.dashStats`'s shape. */
fun computeCaptureStats(items: List<CapturedTrafficItem>): CaptureStats {
    if (items.isEmpty()) return CaptureStats(0, 0, emptyMap(), 0.0, 0L)
    val errorCount = items.count { it.statusCode >= 400 }
    val byMethod = items.groupingBy { it.method.name }.eachCount()
    val avgLatency = items.map { it.latencyMs }.average()
    val totalBytes = items.sumOf { it.requestSize + it.responseSize }
    return CaptureStats(items.size, errorCount, byMethod, avgLatency, totalBytes)
}

// ── image gallery ────────────────────────────────────────────────────────

/** Whether a response content-type indicates an image body worth showing in a gallery view. */
fun isImageResponse(contentType: String?): Boolean = contentType?.lowercase()?.startsWith("image/") == true

/** Filters to captures whose response content-type is an image, preserving original order. */
fun extractImageItems(items: List<CapturedTrafficItem>): List<CapturedTrafficItem> =
    items.filter { isImageResponse(contentTypeOf(it.responseHeaders)) }
