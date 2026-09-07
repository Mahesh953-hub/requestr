package com.example.report

import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.TransactionTimingEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * S5 task 41: server-side HTML renderer for the capture-session report.
 *
 * No JS frameworks; the only inline JavaScript is the one-click repeat button guard, which
 * disables itself on click and shows the attempt result. All output is correctly HTML-escaped so
 * a captured request/response body cannot inject markup into the report.
 */

object ReportRenderer {

    fun renderSession(sessionId: Long, rows: List<CapturedTrafficEntity>, token: String): String {
        val rowsHtml = if (rows.isEmpty()) {
            """
            <div class="empty">
                No transactions in this session yet.
                <br>Session ID: $sessionId
            </div>
            """.trimIndent()
        } else {
            buildString {
                append("<table>")
                append("<thead><tr>")
                append("<th>Method</th><th>Status</th><th>Host</th><th>Path</th><th>Duration</th><th>Time</th>")
                append("</tr></thead><tbody>")
                for (row in rows) {
                    val status = row.statusCode?.toString() ?: "-"
                    val duration = if (row.latencyMs > 0) "${row.latencyMs} ms" else "-"
                    val time = formatTimestamp(row.timestamp)
                    val detailUrl = "/detail?token=${escapeUrl(token)}&id=${row.id}"
                    append(
                        """
                        <tr>
                            <td><span class="method ${methodClass(row.method)}">${escapeHtml(row.method)}</span></td>
                            <td>${escapeHtml(status)}</td>
                            <td>${escapeHtml(row.host)}</td>
                            <td><a href="$detailUrl">${escapeHtml(row.path.ifBlank { row.url })}</a></td>
                            <td>${escapeHtml(duration)}</td>
                            <td>${escapeHtml(time)}</td>
                        </tr>
                        """.trimIndent(),
                    )
                }
                append("</tbody></table>")
            }
        }

        return pageTemplate(
            title = "requestr report · session $sessionId",
            body = buildString {
                append("<h1>Session $sessionId</h1>")
                append("<p>${rows.size} transaction(s)</p>")
                append("<div class='actions'>")
                append("<a href=\"/har?token=${escapeUrl(token)}&sessionId=$sessionId\" download=\"session-$sessionId.har\">Download HAR</a>")
                append("</div>")
                append(rowsHtml)
            },
        )
    }

    fun renderDetail(
        row: CapturedTrafficEntity,
        requestHeaders: List<TransactionHeaderEntity>,
        responseHeaders: List<TransactionHeaderEntity>,
        timings: TransactionTimingEntity?,
        token: String,
    ): String {
        val status = row.statusCode?.toString() ?: "no response status"
        val duration = if (row.latencyMs > 0) "${row.latencyMs} ms" else "-"
        val requestBody = bodyPreview(row.requestBody, row.isRequestBodyBinary, row.requestBodyFilePath)
        val responseBody = bodyPreview(row.responseBody, row.isResponseBodyBinary, row.responseBodyFilePath)
        val requestHeaderRows = headerRows(requestHeaders.ifEmpty { row.requestHeaders.map { (k, v) -> TransactionHeaderEntity(0, row.id, HeaderKind.REQUEST, 0, k, k.lowercase(), v) } })
        val responseHeaderRows = headerRows(responseHeaders.ifEmpty { row.responseHeaders.map { (k, v) -> TransactionHeaderEntity(0, row.id, HeaderKind.RESPONSE, 0, k, k.lowercase(), v) } })
        val timingRows = renderTimings(timings)

        return pageTemplate(
            title = "${row.method} ${row.host}",
            body = buildString {
                append("<h1>${escapeHtml(row.method)} ${escapeHtml(row.url)}</h1>")
                append("<div class='meta'>")
                append("<span>Status: <strong>${escapeHtml(status)}</strong></span>")
                append("<span>Duration: <strong>${escapeHtml(duration)}</strong></span>")
                append("<span>Time: <strong>${escapeHtml(formatTimestamp(row.timestamp))}</strong></span>")
                append("</div>")

                append("<div class='actions'>")
                append("<button id='repeat-btn' onclick='repeatOnce(${row.id})'>Repeat this request</button>")
                append("<a href=\"/har?token=${escapeUrl(token)}&sessionId=${row.sessionId}\">Back to session HAR</a>")
                append("<a href=\"/\">Back to session list</a>")
                append("</div>")
                append("<pre id='repeat-result'></pre>")

                append("<h2>Request</h2>")
                append(requestHeaderRows)
                append("<h3>Body</h3>")
                append(requestBody)

                append("<h2>Response</h2>")
                append(responseHeaderRows)
                append("<h3>Body</h3>")
                append(responseBody)

                if (timings != null) {
                    append("<h2>Timings</h2>")
                    append(timingRows)
                }

                append(
                    """
                    <script>
                    function repeatOnce(id) {
                        const btn = document.getElementById('repeat-btn');
                        if (btn.disabled) return;
                        btn.disabled = true;
                        const result = document.getElementById('repeat-result');
                        result.textContent = 'Sending...';
                        fetch('/repeat?id=' + id, {
                            method: 'POST',
                            headers: { 'Authorization': 'Bearer ${escapeJsString(token)}' }
                        })
                        .then(r => r.json())
                        .then(j => { result.textContent = JSON.stringify(j, null, 2); })
                        .catch(e => { result.textContent = 'Error: ' + e; });
                    }
                    </script>
                    """.trimIndent(),
                )
            },
        )
    }

    private fun headerRows(headers: List<TransactionHeaderEntity>): String {
        if (headers.isEmpty()) return "<p class='empty'>No headers captured.</p>"
        return buildString {
            append("<table class='headers'><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>")
            for (h in headers) {
                append("<tr><td>${escapeHtml(h.name)}</td><td><code>${escapeHtml(h.value)}</code></td></tr>")
            }
            append("</tbody></table>")
        }
    }

    private fun renderTimings(timings: TransactionTimingEntity?): String {
        if (timings == null) return "<p class='empty'>No timing breakdown.</p>"
        val labels = listOf(
            "dns" to timings.dnsMs,
            "connect" to timings.connectMs,
            "tls" to timings.tlsMs,
            "send" to timings.sendMs,
            "wait" to timings.waitMs,
            "receive" to timings.receiveMs,
            "total" to timings.totalMs,
        )
        return buildString {
            append("<table class='headers'><thead><tr><th>Phase</th><th>ms</th></tr></thead><tbody>")
            for ((name, value) in labels) {
                append("<tr><td>${escapeHtml(name)}</td><td>${value?.let { "$it" } ?: "-"}</td></tr>")
            }
            append("</tbody></table>")
        }
    }

    private fun bodyPreview(body: String?, isBinary: Boolean, filePath: String?): String {
        if (isBinary) {
            val note = if (filePath != null) "binary body spilled to $filePath" else "binary body (not stored inline)"
            return "<p class='empty'>${escapeHtml(note)}</p>"
        }
        val text = body?.ifBlank { null } ?: return "<p class='empty'>No body.</p>"
        return "<pre><code>${escapeHtml(text)}</code></pre>"
    }

    private fun methodClass(method: String): String = when (method.uppercase()) {
        "GET" -> "m-get"
        "POST" -> "m-post"
        "PUT" -> "m-put"
        "DELETE" -> "m-delete"
        "PATCH" -> "m-patch"
        else -> "m-other"
    }

    private fun formatTimestamp(ts: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return sdf.format(Date(ts))
    }

    private fun pageTemplate(title: String, body: String): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escapeHtml(title)}</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; background: #0f172a; color: #e2e8f0; padding: 20px; }
                h1, h2, h3 { color: #f8fafc; }
                a { color: #38bdf8; text-decoration: none; }
                a:hover { text-decoration: underline; }
                table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
                th, td { padding: 8px 10px; text-align: left; border-bottom: 1px solid #334155; }
                th { color: #94a3b8; font-weight: 600; }
                tr:hover { background: #1e293b; }
                .method { display: inline-block; padding: 2px 6px; border-radius: 4px; font-weight: 600; font-size: 12px; }
                .m-get { background: #22c55e; color: #0f172a; }
                .m-post { background: #3b82f6; color: #0f172a; }
                .m-put { background: #f59e0b; color: #0f172a; }
                .m-delete { background: #ef4444; color: #0f172a; }
                .m-patch { background: #8b5cf6; color: #0f172a; }
                .m-other { background: #64748b; color: #0f172a; }
                .empty { color: #94a3b8; padding: 16px; background: #1e293b; border-radius: 8px; }
                .meta { display: flex; gap: 24px; flex-wrap: wrap; margin: 12px 0; color: #94a3b8; }
                .meta strong { color: #e2e8f0; }
                .actions { display: flex; gap: 12px; align-items: center; margin: 16px 0; }
                button { background: #2563eb; color: #fff; border: none; padding: 8px 14px; border-radius: 6px; cursor: pointer; font-size: 13px; }
                button:disabled { background: #475569; cursor: not-allowed; }
                button:hover:not(:disabled) { background: #1d4ed8; }
                pre { background: #1e293b; padding: 12px; border-radius: 8px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
                code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
                .headers td:first-child { width: 25%; color: #94a3b8; }
            </style>
        </head>
        <body>
            $body
        </body>
        </html>
        """.trimIndent()
    }

    /**
     * Minimal valid HAR 1.2 fallback used by the default [ReportServer.harProvider] seam. It is
     * intentionally simple so the Report Server compiles and tests pass before the sibling
     * `data/export/HarWriter.kt` lands; production should swap this provider for the real
     * session-level writer.
     */
    fun defaultHarForSession(sessionId: Long): String {
        val log = JSONObject()
            .put("version", "1.2")
            .put("creator", JSONObject().put("name", "requestr").put("version", "report"))
            .put("comment", "session $sessionId")
            .put("entries", JSONArray())
        return JSONObject().put("log", log).toString(2)
    }

    fun escapeHtml(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun escapeUrl(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")

    private fun escapeJsString(text: String): String = JSONObject.quote(text).removeSurrounding("\"")
}
