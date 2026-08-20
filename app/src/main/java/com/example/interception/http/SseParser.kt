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
 *
 * Data lines concatenated with \n -> dispatch "message" event
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
        val ct = (headers?.get("Content-Type") ?: headers?.get("content-type") ?: "") .lowercase()
        return ct.contains("text/event-stream") || ct.contains("application/event-stream")
    }

    /** Parses SSE events from an accumulated response body string. */
    fun parseEvents(body: String, lastSeenBlock: Int = 0): Pair<List<SseEvent>, Int> {
        // Track last fully-consumed block to avoid re-dispatching partial tails
        val events = mutableListOf<SseEvent>()
        var id: String? = null
        var eventName = "message"
        var retry: Int? = null
        val dataLines = mutableListOf<String>()

        var consumedBlock = -1
        val blocks = body.split("\n\n")
        for (i in blocks.indices) {
            if (i <= lastSeenBlock) continue
            val block = blocks[i]
            var isEvent = false
            for (line in block.split("\n")) {
                when {
                    line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("retry:") -> retry = line.removePrefix("retry:").trim().toIntOrNull()
                    line.startsWith("data:") -> {
                        val d = line.removePrefix("data:")
                            .removePrefix(" ") // strip the single leading space
                        dataLines.add(d)
                        isEvent = true
                    }
                    line == ":heartbeat" || line.startsWith(":") -> { /* comment = heartbeat */ }
                    line.isBlank() && !isEvent -> { /* whitespace block */ }
                }
            }
            // A blank line (block end) terminates the event
            dataLines.let { lines ->
                if (lines.isNotEmpty()) {
                    events.add(
                        SseEvent(
                            id = id,
                            event = eventName,
                            retryMs = retry,
                            data = lines.joinToString("\n"),
                        )
                    )
                }
            }
            if (isEvent || block.isBlank() || block.endsWith("\n\n")) {
                dataLines.clear()
                eventName = "message"
                id = null
                retry = null
            }
            consumedBlock = i
        }

        return events to consumedBlock
    }
}
