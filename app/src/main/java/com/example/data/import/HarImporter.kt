package com.example.data.import

import android.content.Context
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * HAR (HTTP Archive) file importer.
 *
 * Parses .har files (JSON format used by Chrome DevTools, Firefox, Burp, etc.)
 * and imports entries into Room as captured traffic. This lets users load
 * externally-captured traffic into requestr for analysis.
 *
 * Format: https://w3c.github.io/web-performance/specs/HAR/Overview.html
 */
object HarImporter {

    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val errors: List<String>,
    )

    /**
     * Parses a HAR JSON string and imports entries into Room.
     * Returns a summary of what was imported.
     */
    suspend fun importFromJson(context: Context, harJson: String): ImportResult {
        val db = RequestrDatabase.get(context)
        // S2 task 15: imports are bulk, non-relay producers -- use the back-pressured path so
        // they never evict live capture writes, and await each row so `imported` stays honest.
        val queue = com.example.data.persistence.PersistenceQueue.forDatabase(db)
        val root = JSONObject(harJson)
        val log = root.optJSONObject("log") ?: return ImportResult(0, 0, listOf("Missing 'log' key"))
        val entries = log.optJSONArray("entries") ?: return ImportResult(0, 0, listOf("Missing 'log.entries'"))

        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (i in 0 until entries.length()) {
            try {
                val entry = entries.getJSONObject(i)
                val request = entry.getJSONObject("request")
                val response = entry.optJSONObject("response")

                val method = request.optString("method", "GET")
                val url = request.optString("url", "")
                val headers = parseHeaders(request.optJSONArray("headers"))
                val body = request.optJSONObject("postData")?.optString("text", "") ?: ""

                val statusCode = response?.optInt("status", 0) ?: 0
                val responseHeaders = parseHeaders(response?.optJSONArray("headers"))
                val responseBody = response?.optJSONObject("content")?.optString("text", "") ?: ""

                // Parse URL into host + path
                val uri = java.net.URI(url)
                val host = uri.host ?: ""
                val path = uri.path ?: "/"

                val entity = CapturedTrafficEntity(
                    sessionId = 0, // imported sessions get sessionId 0
                    method = method,
                    url = url,
                    host = host,
                    path = path,
                    statusCode = if (statusCode > 0) statusCode else null,
                    protocol = response?.optString("httpVersion", "HTTP/1.1") ?: "HTTP/1.1",
                    requestHeaders = headers,
                    responseHeaders = responseHeaders,
                    requestBody = body.ifBlank { null },
                    responseBody = responseBody.ifBlank { null },
                    requestSizeBytes = body.toByteArray().size.toLong(),
                    responseSizeBytes = responseBody.toByteArray().size.toLong(),
                    latencyMs = entry.optLong("time", 0L),
                    timestamp = parseTimestamp(entry.optString("startedDateTime", "")),
                    wasDecrypted = false,
                    captureMethod = com.example.data.local.CaptureRowMethod.HAR_IMPORT,
                )

                queue.insertTransactionAndAwait(entity)
                imported++
            } catch (e: Exception) {
                skipped++
                errors.add("Entry $i: ${e.message}")
            }
        }

        return ImportResult(imported, skipped, errors)
    }

    /** Imports from a file path (content URI or file path). */
    suspend fun importFromFile(context: Context, filePath: String): ImportResult {
        val file = java.io.File(filePath)
        if (!file.exists()) return ImportResult(0, 0, listOf("File not found: $filePath"))
        return importFromJson(context, file.readText())
    }

    /** Imports from a content URI (e.g., from file picker). */
    suspend fun importFromUri(context: Context, uri: android.net.Uri): ImportResult {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(0, 0, listOf("Cannot open URI"))
        val json = inputStream.bufferedReader().readText()
        inputStream.close()
        return importFromJson(context, json)
    }

    private fun parseHeaders(headersArray: JSONArray?): Map<String, String> {
        if (headersArray == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (i in 0 until headersArray.length()) {
            val h = headersArray.getJSONObject(i)
            val name = h.optString("name", "")
            val value = h.optString("value", "")
            if (name.isNotBlank()) map[name] = value
        }
        return map
    }

    private fun parseTimestamp(iso: String): Long {
        if (iso.isBlank()) return System.currentTimeMillis()
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(iso)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
