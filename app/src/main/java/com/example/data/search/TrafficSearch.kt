package com.example.data.search

import com.example.data.models.CapturedTrafficItem
import com.example.data.models.ProtocolHint
import com.example.data.models.TrafficSource

/**
 * Semantic traffic search engine. Parses queries like:
 *   status:500
 *   method:POST
 *   host:api.example.com
 *   duration:>1000
 *   protocol:h2
 *   source:webview
 * Combined: status:429 AND host:api.example.com
 */
object TrafficSearch {

    data class Query(
        val status: Int? = null,
        val method: String? = null,
        val host: String? = null,
        val path: String? = null,
        val durationGt: Long? = null,
        val durationLt: Long? = null,
        val contentType: String? = null,
        val protocol: String? = null,
        val source: String? = null,
        val tag: String? = null,
        val freeText: String? = null,
    )

    fun parse(raw: String): Query {
        if (raw.isBlank()) return Query()
        val terms = smartSplit(raw)
        var status: Int? = null
        var method: String? = null
        var host: String? = null
        var path: String? = null
        var durationGt: Long? = null
        var durationLt: Long? = null
        var contentType: String? = null
        var protocol: String? = null
        var source: String? = null
        var tag: String? = null
        val freeTextParts = mutableListOf<String>()

        for (term in terms) {
            val colonIdx = term.indexOf(':')
            if (colonIdx > 0 && colonIdx < term.length - 1) {
                val key = term.substring(0, colonIdx).lowercase()
                val value = term.substring(colonIdx + 1).trim()
                when (key) {
                    "status" -> status = value.toIntOrNull()
                    "method" -> method = value.uppercase()
                    "host" -> host = value.lowercase()
                    "path" -> path = value
                    "duration" -> {
                        when {
                            value.startsWith(">") -> durationGt = value.drop(1).toLongOrNull()
                            value.startsWith("<") -> durationLt = value.drop(1).toLongOrNull()
                            else -> durationGt = value.toLongOrNull()
                        }
                    }
                    "content-type", "ct" -> contentType = value.lowercase()
                    "protocol", "proto" -> protocol = value.lowercase()
                    "source", "src" -> source = value.lowercase()
                    "tag" -> tag = value.lowercase()
                    else -> freeTextParts.add(term)
                }
            } else {
                freeTextParts.add(term)
            }
        }

        return Query(
            status = status, method = method, host = host, path = path,
            durationGt = durationGt, durationLt = durationLt,
            contentType = contentType, protocol = protocol,
            source = source, tag = tag,
            freeText = freeTextParts.joinToString(" ").ifBlank { null },
        )
    }

    fun matches(item: CapturedTrafficItem, query: Query): Boolean {
        if (query.status != null && item.statusCode != query.status) return false
        if (query.method != null && !item.method.name.equals(query.method, ignoreCase = true)) return false
        if (query.host != null && !item.host.lowercase().contains(query.host)) return false
        if (query.path != null && !item.path.contains(query.path, ignoreCase = true)) return false
        if (query.durationGt != null && item.latencyMs <= query.durationGt) return false
        if (query.durationLt != null && item.latencyMs >= query.durationLt) return false
        if (query.contentType != null) {
            val ct = item.responseHeaders["content-type"] ?: ""
            if (!ct.lowercase().contains(query.contentType)) return false
        }
        if (query.protocol != null) {
            if (!item.protocolHint.shortLabel.equals(query.protocol, ignoreCase = true) &&
                !item.protocolHint.label.equals(query.protocol, ignoreCase = true) &&
                !item.protocol.lowercase().contains(query.protocol)) return false
        }
        if (query.source != null) {
            if (!item.trafficSource.label.equals(query.source, ignoreCase = true)) return false
        }
        if (query.tag != null) {
            if (item.tags.none { it.equals(query.tag, ignoreCase = true) }) return false
        }
        if (query.freeText != null) {
            val ft = query.freeText.lowercase()
            val matchable = listOfNotNull(
                item.url, item.host, item.path, item.requestBody, item.responseBody,
                item.appName, item.appPackage,
            ).joinToString(" ").lowercase()
            if (!matchable.contains(ft)) return false
        }
        return true
    }

    /**
     * In-memory filter over an already-loaded list. S3 task 22: parsed by
     * [TrafficSearchCompiler] so `OR`, `NOT`/`-term` and parentheses work here exactly as in the
     * SQL path; each term keeps the per-field semantics of [matches].
     */
    fun filter(items: List<CapturedTrafficItem>, raw: String): List<CapturedTrafficItem> {
        if (raw.isBlank()) return items
        val compiler = TrafficSearchCompiler(useFts = false)
        val node = compiler.parse(raw)
        return items.filter { compiler.matches(node, it) }
    }

    /** Split on AND/and, then on spaces, preserving quoted terms. */
    private fun smartSplit(raw: String): List<String> {
        return raw.split(Regex("\\s+(?i:AND)\\s+"))
            .flatMap { splitQuoted(it) }
            .filter { it.isNotBlank() }
    }

    private fun splitQuoted(s: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (ch in s) {
            if (ch == '"') {
                inQuote = !inQuote
                if (!inQuote && sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
            } else if (ch == ' ' && !inQuote) {
                if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }
}
