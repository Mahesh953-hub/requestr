package com.example.data.export

import com.example.data.models.CapturedTrafficItem

enum class ExportFormat(val label: String, val ext: String) {
    CURL("cURL", "sh"),
    PYTHON("Python", "py"),
    JAVASCRIPT("JavaScript", "js"),
    GO("Go", "go"),
    HAR("HAR", "har"),
    JSON("JSON", "json"),
}

object CodeExporter {

    fun export(item: CapturedTrafficItem, format: ExportFormat): String = when (format) {
        ExportFormat.CURL -> toCurl(item)
        ExportFormat.PYTHON -> toPython(item)
        ExportFormat.JAVASCRIPT -> toJavaScript(item)
        ExportFormat.GO -> toGo(item)
        ExportFormat.HAR -> toHar(item)
        ExportFormat.JSON -> toJson(item)
    }

    // ── cURL ────────────────────────────────────────────────────────────

    private fun toCurl(item: CapturedTrafficItem): String = buildString {
        append("curl -X ${item.method.name}")
        append(" \\\n  '${item.url}'")
        item.requestHeaders.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                append(" \\\n  -H '${escapeShell(k)}: ${escapeShell(v)}'")
            }
        }
        if (item.requestBody.isNotBlank()) {
            append(" \\\n  -d '${escapeShell(item.requestBody)}'")
        }
        append("\n")
    }

    // ── Python ─────────────────────────────────────────────────────────

    private fun toPython(item: CapturedTrafficItem): String = buildString {
        append("import requests\n\n")
        append("url = \"${escapePy(item.url)}\"\n\n")
        append("headers = {\n")
        item.requestHeaders.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                append("    \"${escapePy(k)}\": \"${escapePy(v)}\",\n")
            }
        }
        append("}\n\n")
        if (item.requestBody.isNotBlank()) {
            val ct = item.requestHeaders["Content-Type"] ?: item.requestHeaders["content-type"] ?: ""
            if (ct.contains("json")) {
                append("data = ${formatJsonBody(item.requestBody)}\n\n")
                append("response = requests.${item.method.name.lowercase()}(url, headers=headers, json=data)\n")
            } else {
                append("data = '''${escapePy(item.requestBody)}'''\n\n")
                append("response = requests.${item.method.name.lowercase()}(url, headers=headers, data=data)\n")
            }
        } else {
            append("response = requests.${item.method.name.lowercase()}(url, headers=headers)\n")
        }
        append("print(response.status_code)\n")
        append("print(response.text)\n")
    }

    // ── JavaScript ──────────────────────────────────────────────────────

    private fun toJavaScript(item: CapturedTrafficItem): String = buildString {
        append("const url = '${escapeJs(item.url)}';\n\n")
        append("const options = {\n")
        append("  method: '${item.method.name}',\n")
        append("  headers: {\n")
        item.requestHeaders.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                append("    '${escapeJs(k)}': '${escapeJs(v)}',\n")
            }
        }
        append("  },\n")
        if (item.requestBody.isNotBlank()) {
            append("  body: '${escapeJs(item.requestBody)}',\n")
        }
        append("};\n\n")
        append("fetch(url, options)\n")
        append("  .then(res => res.text())\n")
        append("  .then(body => console.log(body));\n")
    }

    // ── Go ──────────────────────────────────────────────────────────────

    private fun toGo(item: CapturedTrafficItem): String = buildString {
        val bodyVar = if (item.requestBody.isNotBlank()) "body" else "nil"
        append("package main\n\n")
        append("import (\n")
        append("    \"fmt\"\n")
        append("    \"io\"\n")
        append("    \"net/http\"\n")
        if (item.requestBody.isNotBlank()) append("    \"strings\"\n")
        append(")\n\n")
        append("func main() {\n")
        if (item.requestBody.isNotBlank()) {
            append("    body := strings.NewReader(`$item.requestBody`)\n")
        }
        append("    req, err := http.NewRequest(\"${item.method.name}\", \"${escapeGo(item.url)}\", $bodyVar)\n")
        append("    if err != nil { panic(err) }\n")
        item.requestHeaders.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                append("    req.Header.Set(\"${escapeGo(k)}\", \"${escapeGo(v)}\")\n")
            }
        }
        append("    resp, err := http.DefaultClient.Do(req)\n")
        append("    if err != nil { panic(err) }\n")
        append("    defer resp.Body.Close()\n")
        append("    data, _ := io.ReadAll(resp.Body)\n")
        append("    fmt.Println(resp.StatusCode, string(data))\n")
        append("}\n")
    }

    // ── HAR ─────────────────────────────────────────────────────────────

    private fun toHar(item: CapturedTrafficItem): String = buildString {
        append("{\n")
        append("  \"log\": {\n")
        append("    \"entries\": [{\n")
        append("      \"request\": {\n")
        append("        \"method\": \"${item.method.name}\",\n")
        append("        \"url\": \"${escapeJson(item.url)}\",\n")
        append("        \"headers\": [\n")
        item.requestHeaders.forEach { (k, v) ->
            append("          {\"name\": \"${escapeJson(k)}\", \"value\": \"${escapeJson(v)}\"},\n")
        }
        append("        ],\n")
        append("        \"bodySize\": ${item.requestSize}\n")
        append("      },\n")
        append("      \"response\": {\n")
        append("        \"status\": ${item.statusCode},\n")
        append("        \"headers\": [\n")
        item.responseHeaders.forEach { (k, v) ->
            append("          {\"name\": \"${escapeJson(k)}\", \"value\": \"${escapeJson(v)}\"},\n")
        }
        append("        ],\n")
        append("        \"bodySize\": ${item.responseSize}\n")
        append("      },\n")
        append("      \"time\": ${item.latencyMs},\n")
        append("      \"timings\": {\n")
        append("        \"dns\": ${if (item.dnsMs >= 0) item.dnsMs else -1},\n")
        append("        \"connect\": ${if (item.connectMs >= 0) item.connectMs else -1},\n")
        append("        \"ssl\": ${if (item.tlsMs >= 0) item.tlsMs else -1},\n")
        append("        \"wait\": ${if (item.waitMs >= 0) item.waitMs else -1},\n")
        append("        \"receive\": ${if (item.downloadMs >= 0) item.downloadMs else -1}\n")
        append("      }\n")
        append("    }]\n")
        append("  }\n")
        append("}\n")
    }

    // ── JSON ────────────────────────────────────────────────────────────

    private fun toJson(item: CapturedTrafficItem): String = buildString {
        append("{\n")
        append("  \"method\": \"${item.method.name}\",\n")
        append("  \"url\": \"${escapeJson(item.url)}\",\n")
        append("  \"host\": \"${escapeJson(item.host)}\",\n")
        append("  \"path\": \"${escapeJson(item.path)}\",\n")
        append("  \"status\": ${item.statusCode},\n")
        append("  \"latency_ms\": ${item.latencyMs},\n")
        append("  \"protocol\": \"${escapeJson(item.protocol)}\",\n")
        append("  \"traffic_source\": \"${item.trafficSource.label}\",\n")
        append("  \"protocol_hint\": \"${item.protocolHint.label}\",\n")
        if (item.tlsVersion.isNotBlank()) {
            append("  \"tls_version\": \"${escapeJson(item.tlsVersion)}\",\n")
            append("  \"tls_cipher\": \"${escapeJson(item.tlsCipher)}\",\n")
        }
        append("  \"request_headers\": {\n")
        item.requestHeaders.entries.take(15).forEach { (k, v) ->
            append("    \"${escapeJson(k)}\": \"${escapeJson(v)}\",\n")
        }
        append("  },\n")
        append("  \"response_headers\": {\n")
        item.responseHeaders.entries.take(15).forEach { (k, v) ->
            append("    \"${escapeJson(k)}\": \"${escapeJson(v)}\",\n")
        }
        append("  }\n")
        append("}\n")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun escapeShell(s: String): String = s.replace("'", "'\\''")
    private fun escapePy(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun escapeJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")
    private fun escapeGo(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun formatJsonBody(body: String): String {
        return try {
            body.trim()
        } catch (_: Exception) {
            "\"$body\""
        }
    }
}
