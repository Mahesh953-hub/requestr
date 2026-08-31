package com.example.ui.screens

/**
 * Dossier Q7 — the API Testr mockup's redirect rocket icon has
 * `aria-label="Follow redirects — tap to toggle, long-press for options"`, and the task's literal
 * spec is stricter: tap = follow once (this single send only), long-press = always follow (every
 * send until turned back off). We implement the literal spec below; the mockup's own legend text
 * ("single tap toggles on/off; long-press opens redirect options") is looser/different phrasing —
 * flagged in the final report as a discrepancy, not silently resolved either way.
 *
 * [alwaysFollow] persists across sends (toggled by long-press). [onceArmed] is a one-shot flag
 * (toggled by tap) that is consumed after the next send completes via [consumeOnceAfterSend] so a
 * single "follow once" tap doesn't silently keep following redirects forever.
 *
 * The default [RedirectFollowState] evaluates [shouldFollowRedirects] to `true`, matching the
 * screen's pre-existing default (`followRedirects = true`) so this is a backward-compatible
 * replacement for the old plain `Boolean`.
 */
data class RedirectFollowState(
    val alwaysFollow: Boolean = true,
    val onceArmed: Boolean = false
)

/** Whether the *next* send should follow redirects, given the current toggle state. */
fun shouldFollowRedirects(state: RedirectFollowState): Boolean =
    state.alwaysFollow || state.onceArmed

/** Tap: arms/disarms a one-shot "follow this send only" flag without touching [alwaysFollow]. */
fun toggleOnceArmed(state: RedirectFollowState): RedirectFollowState =
    state.copy(onceArmed = !state.onceArmed)

/** Long-press: flips the persistent "always follow redirects" setting. */
fun toggleAlwaysFollow(state: RedirectFollowState): RedirectFollowState =
    state.copy(alwaysFollow = !state.alwaysFollow)

/**
 * Call after a send completes so a one-shot "follow once" tap doesn't keep following redirects
 * on every subsequent send. No-op when [RedirectFollowState.onceArmed] is already false, and does
 * not touch [RedirectFollowState.alwaysFollow].
 */
fun consumeOnceAfterSend(state: RedirectFollowState): RedirectFollowState =
    if (state.onceArmed) state.copy(onceArmed = false) else state
