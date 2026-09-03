package com.example.interception.relay

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-connection lifecycle for every relayed session (master plan §7 task 7):
 *
 * ```
 * ACCEPTED -> DETECTED -> TLS -> RELAYING -> DRAINING -> CLOSED
 *     |          |         |        |           |
 *     +----------+---------+--------+-----------+--> FAILED(reason)
 * ```
 *
 * `TLS` is optional (plaintext sessions go `DETECTED -> RELAYING`), `DRAINING` is optional (a
 * session whose both legs end together goes `RELAYING -> CLOSED`). Every other edge is illegal
 * and [transition] reports it instead of silently accepting it, so a caller that emits a
 * "decrypted" row can only do so from a genuine terminal state -- which is exactly the guarantee
 * the old `Thread.join(60_000)` path lacked.
 *
 * The terminal callback [onTerminal] fires **exactly once**, on the first of [close]/[fail],
 * regardless of how many legs race to end the connection. Phase timestamps are recorded so task
 * 18 (measured timings) can read connect/TLS/relay durations without re-instrumenting.
 *
 * Deadlines are checked by the owner via [checkDeadlines] (the machine has no timer of its own):
 * [idleDeadlineMs] bounds the gap since the last [touch] while relaying/draining, and
 * [totalDeadlineMs] bounds the whole connection lifetime. Zero disables a deadline.
 */
class ConnectionStateMachine(
    private val idleDeadlineMs: Long = DEFAULT_IDLE_DEADLINE_MS,
    private val totalDeadlineMs: Long = DEFAULT_TOTAL_DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTerminal: (Terminal) -> Unit = {},
) {
    enum class State { ACCEPTED, DETECTED, TLS, RELAYING, DRAINING, CLOSED, FAILED }

    sealed class Terminal {
        object Closed : Terminal() {
            override fun toString() = "Closed"
        }

        data class Failed(val reason: String) : Terminal()
    }

    sealed class DeadlineBreach(val label: String) {
        object Idle : DeadlineBreach("idle deadline")
        object Total : DeadlineBreach("total deadline")
    }

    private val lock = Any()
    private val terminalFired = AtomicBoolean(false)

    @Volatile
    var state: State = State.ACCEPTED
        private set

    @Volatile
    var terminal: Terminal? = null
        private set

    /** Wall-clock time of entry into each state, first entry only. */
    private val enteredAt = java.util.EnumMap<State, Long>(State::class.java)

    @Volatile
    private var lastActivityAt: Long = clock()

    val acceptedAt: Long

    init {
        val now = clock()
        acceptedAt = now
        enteredAt[State.ACCEPTED] = now
    }

    val isTerminal: Boolean get() = state == State.CLOSED || state == State.FAILED

    fun enteredAt(s: State): Long? = enteredAt[s]

    /** Records relay activity for the idle deadline. */
    fun touch() {
        lastActivityAt = clock()
    }

    /**
     * Moves to [to] if the edge is legal. Returns `false` (and leaves state untouched) otherwise.
     * Terminal states must be entered through [close]/[fail].
     */
    fun transition(to: State): Boolean {
        synchronized(lock) {
            if (to == State.CLOSED || to == State.FAILED) return false
            if (!isLegal(state, to)) return false
            enter(to)
            return true
        }
    }

    /** Normal completion. Idempotent; the second terminal call is ignored. */
    fun close(): Boolean = finish(State.CLOSED, Terminal.Closed)

    /** Abnormal completion with a human-readable [reason]. Idempotent. */
    fun fail(reason: String): Boolean = finish(State.FAILED, Terminal.Failed(reason))

    /**
     * Returns the first breached deadline, or null. Only meaningful in non-terminal states;
     * returns null once terminal.
     */
    fun checkDeadlines(now: Long = clock()): DeadlineBreach? {
        if (isTerminal) return null
        if (totalDeadlineMs > 0 && now - acceptedAt > totalDeadlineMs) return DeadlineBreach.Total
        val idleApplies = state == State.RELAYING || state == State.DRAINING
        if (idleApplies && idleDeadlineMs > 0 && now - lastActivityAt > idleDeadlineMs) return DeadlineBreach.Idle
        return null
    }

    /** Duration spent in [from] before entering [to], if both were entered. */
    fun durationBetween(from: State, to: State): Long? {
        val a = enteredAt[from] ?: return null
        val b = enteredAt[to] ?: return null
        return b - a
    }

    private fun finish(to: State, t: Terminal): Boolean {
        val fire: Boolean
        synchronized(lock) {
            if (isTerminal) return false
            enter(to)
            terminal = t
            fire = terminalFired.compareAndSet(false, true)
        }
        if (fire) onTerminal(t)
        return true
    }

    private fun enter(to: State) {
        state = to
        val now = clock()
        enteredAt.putIfAbsent(to, now)
        lastActivityAt = now
    }

    companion object {
        const val DEFAULT_IDLE_DEADLINE_MS: Long = 10 * 60_000L
        const val DEFAULT_TOTAL_DEADLINE_MS: Long = 0L

        /** Legal non-terminal edges. Terminal edges are legal from every non-terminal state. */
        fun isLegal(from: State, to: State): Boolean = when (from) {
            State.ACCEPTED -> to == State.DETECTED
            State.DETECTED -> to == State.TLS || to == State.RELAYING
            State.TLS -> to == State.RELAYING
            State.RELAYING -> to == State.DRAINING
            State.DRAINING -> false
            State.CLOSED, State.FAILED -> false
        }
    }
}
