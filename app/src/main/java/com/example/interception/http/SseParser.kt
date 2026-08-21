package com.example.interception.http

/**
 * Server-Sent Events (SSE) parser.
 *
 * SSE format (/text/event-stream):
 *   event: name
 *   id: 123
 *   retry: 3000
 *   data: line1
 *   data: line2
 *   (blank line separates events)
 */
object SseParser {

    data class SseEvent(
        val id: String? = null,
        val event: String = "message",
        val retryMs: Int? = null,
        val data: String = "",
    )

    /** True if the response content-type is text/event-stream. */
    fun looksLikeSse(headers: Map<String, String>?): Boolean {
        val ct = (headers?.get("Content-Type") ?: headers?.get("content-type") ?: "").lowercase()
        return ct.contains("text/event-stream") || ct.contains("application/event-stream")
    }

    /** Parses SSE events from a response body string. */
    fun parseEvents(body: String, lastSeenBlock: Int = -1): Pair<List<SseEvent>, Int> {
        val events = mutableListOf<SseEvent>()
        val blocks = body.split("\n\n")

        for (i in blocks.indices) {
            if (i <= lastSeenBlock) continue
            val block = blocks[i].trim()
            if (block.isEmpty()) continue

            var id: String? = null
            var eventName = "message"
            var retry: Int? = null
            val dataLines = mutableListOf<String>()

            for (line in block.split("\n")) {
                val trimmed = line.trimEnd('\r')
                when {
                    trimmed.startsWith("data:") -> {
                        var value = trimmed.removePrefix("data:")
                        if (value.startsWith(" ")) value = value.substring(1)
                        dataLines.add(value)
                    }
                    trimmed.startsWith("event:") -> {
                        var value = trimmed.removePrefix("event:")
                        if (value.startsWith(" ")) value = value.substring(1)
                        eventName = value
                    }
                    trimmed.startsWith("id:") -> {
                        var value = trimmed.removePrefix("id:")
                        if (value.startsWith(" ")) value = value.substring(1)
                        id = value
                    }
                    trimmed.startsWith("retry:") -> {
                        var value = trimmed.removePrefix("retry:")
                        if (value.startsWith(" ")) value = value.substring(1)
                        retry = value.trim().toIntOrNull()
                    }
                    trimmed.startsWith(":") -> { /* comment / heartbeat */ }
                }
            }

            if (dataLines.isNotEmpty()) {
                events.add(
                    SseEvent(
                        id = id,
                        event = eventName,
                        retryMs = retry,
                        data = dataLines.joinToString("\n"),
                    )
                )
            }
        }

        return events to (blocks.size - 1)
    }
}
