package com.example.interception.rules

import java.util.concurrent.Semaphore

/**
 * S4 task 33 (D3): the replay-connection cap. The S5 replay queue must take a permit before
 * dialing and release it when the connection closes; the 256th concurrent dial is refused
 * instead of queued so a runaway replay job can never exhaust sockets or the relay pool.
 */
class ReplayPermits(val max: Int = RuleLimits.MAX_REPLAY_CONCURRENCY) {
    private val slots = Semaphore(max)

    val inFlight: Int get() = max - slots.availablePermits()

    /** Non-blocking; false means the cap is reached and the caller must fail this replay. */
    fun tryAcquire(): Boolean = slots.tryAcquire()

    fun release() {
        if (slots.availablePermits() < max) slots.release()
    }

    companion object {
        val shared: ReplayPermits by lazy { ReplayPermits() }
    }
}
