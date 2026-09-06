package com.example.scripting

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One `console.*` line or error surfaced by a SCRIPT rule run (S4 task 32). */
data class ScriptLogEntry(
    val flowId: String,
    val scriptId: String,
    val level: Level,
    val message: String,
    val backtrace: String? = null,
    val atMs: Long = System.currentTimeMillis(),
) {
    enum class Level { LOG, WARN, ERROR }
}

/**
 * Bounded ring buffer of script output for the rule UI. Oldest entries fall off at [capacity];
 * the log itself never blocks a relay thread (a single synchronized append).
 */
class ScriptConsoleLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val entries = ArrayDeque<ScriptLogEntry>(capacity)
    private val _snapshot = MutableStateFlow<List<ScriptLogEntry>>(emptyList())

    /** Latest-first snapshot for observers. */
    val snapshot: StateFlow<List<ScriptLogEntry>> get() = _snapshot

    val size: Int get() = synchronized(entries) { entries.size }

    fun append(entry: ScriptLogEntry) {
        val published: List<ScriptLogEntry>
        synchronized(entries) {
            if (entries.size >= capacity) entries.pollFirst()
            entries.addLast(entry)
            published = entries.toList().asReversed()
        }
        _snapshot.value = published
    }

    fun appendAll(flowId: String, scriptId: String, console: List<String>, error: String? = null) {
        console.forEach { append(ScriptLogEntry(flowId, scriptId, ScriptLogEntry.Level.LOG, it)) }
        if (error != null) {
            val lines = error.lines()
            append(ScriptLogEntry(flowId, scriptId, ScriptLogEntry.Level.ERROR, lines.first(), backtrace = lines.drop(1).joinToString("\n").ifBlank { null }))
        }
    }

    /** Oldest-first copy. */
    fun entries(): List<ScriptLogEntry> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        _snapshot.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
        val shared: ScriptConsoleLog by lazy { ScriptConsoleLog() }
    }
}
