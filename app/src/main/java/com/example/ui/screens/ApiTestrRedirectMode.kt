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
 * (toggled by tap) that is consumed after the next send completes via [afterSend] so a single
 * "follow once" tap doesn't silently keep following redirects forever.
 *
 * The default state has [shouldFollow] `true`, matching the screen's pre-existing default
 * (`followRedirects = true`).
 */
data class RedirectFollowState(
    val alwaysFollow: Boolean = true,
    val onceArmed: Boolean = false
) {
    /** Whether the *next* send should follow redirects. */
    val shouldFollow: Boolean get() = alwaysFollow || onceArmed

    /** Tap: arms/disarms the one-shot "follow this send only" flag, leaving [alwaysFollow] alone. */
    fun withOnceToggled(): RedirectFollowState = copy(onceArmed = !onceArmed)

    /** Long-press: flips the persistent "always follow redirects" setting. */
    fun withAlwaysToggled(): RedirectFollowState = copy(alwaysFollow = !alwaysFollow)

    /**
     * Call after a send completes so a one-shot "follow once" tap doesn't keep following redirects
     * on every subsequent send. Leaves [alwaysFollow] alone.
     */
    fun afterSend(): RedirectFollowState = copy(onceArmed = false)
}
