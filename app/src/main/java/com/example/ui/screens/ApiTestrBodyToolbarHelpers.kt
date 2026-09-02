package com.example.ui.screens

import com.example.data.models.KeyValueItem
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Dossier §7 item 6/Q7 — pure raw-text ↔ [KeyValueItem] sync logic, extracted out of
 * `RawTextEditor`'s inline `onValueChange` lambda (previously untested) so it can be unit tested
 * and reused for the Body tab's new FORM/MULTIPART raw-editor toggle without duplicating the
 * parsing rules. Behavior is unchanged from the original inline implementation:
 *  - blank lines are skipped (produce no row)
 *  - a leading `//` marks the row disabled and is stripped before parsing the rest of the line
 *  - the remaining text is split on the first `:` (`limit = 2`); a line with no `:` is dropped
 *    entirely (not treated as a row with an empty value)
 *  - both the key and value are `.trim()`-ed
 *  - duplicate keys are preserved as separate positional rows ([KeyValueItem] is a list, not a
 *    map, so there is nothing to de-duplicate)
 */
fun parseRawTextToKeyValueItems(text: String): List<KeyValueItem> =
    text.split("\n").mapNotNull { line ->
        if (line.isBlank()) {
            null
        } else {
            val disabled = line.startsWith("//")
            val content = if (disabled) line.removePrefix("//").trim() else line
            val parts = content.split(":", limit = 2)
            if (parts.size == 2) {
                KeyValueItem(key = parts[0].trim(), value = parts[1].trim(), isEnabled = !disabled)
            } else {
                null
            }
        }
    }

/** Inverse of [parseRawTextToKeyValueItems] — the exact format `RawTextEditor` seeds itself with. */
fun keyValueItemsToRawText(items: List<KeyValueItem>): String =
    items.joinToString("\n") { if (it.isEnabled) "${it.key}: ${it.value}" else "// ${it.key}: ${it.value}" }

/**
 * Dossier §7 item 6 — "Copy as flat key=value" for the request Body tab's JSON/RAW toolbar.
 * Walks a JSON body recursively, building `path=value` lines using dot-notation for nested
 * object keys and `[i]` bracket notation for array indices (e.g. `user.roles[0]=admin`).
 *
 * The mockup's own aria-label for this button ("Copy as key=value") describes it generically;
 * this implementation is scoped to the Body tab's actual JSON content rather than the mockup's
 * literal Query/Header rows (see dossier note: the mockup ties its JSON toolbar to
 * `.jsonwrap`/query-header rows, not literally the raw HTTP body — flagged in the final report).
 *
 * Falls back to returning [json] unmodified when it is not valid JSON at all, so GraphQL queries
 * or plain-text bodies degrade gracefully instead of throwing.
 */
fun flattenJsonBodyToKeyValueLines(json: String): String {
    val trimmed = json.trim()
    if (trimmed.isEmpty()) return json
    // Match ToolsRepository.formatJson's convention: only attempt to parse text that actually
    // looks like a JSON object/array. JSONTokener itself is lenient enough to happily parse a
    // bare word like "query" out of a GraphQL body as a one-off String value, which would
    // otherwise defeat the "fall back on non-JSON input" guarantee below.
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return json
    val root = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() ?: return json
    val lines = mutableListOf<String>()
    fun walk(prefix: String, value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                if (keys.isEmpty() && prefix.isNotEmpty()) {
                    lines.add("$prefix={}")
                } else {
                    keys.forEach { key ->
                        val nextPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                        walk(nextPrefix, value.get(key))
                    }
                }
            }
            is JSONArray -> {
                if (value.length() == 0 && prefix.isNotEmpty()) {
                    lines.add("$prefix=[]")
                } else {
                    for (i in 0 until value.length()) {
                        walk("$prefix[$i]", value.get(i))
                    }
                }
            }
            else -> lines.add("$prefix=${value ?: "null"}")
        }
    }
    walk("", root)
    return if (lines.isEmpty()) json else lines.joinToString("\n")
}
