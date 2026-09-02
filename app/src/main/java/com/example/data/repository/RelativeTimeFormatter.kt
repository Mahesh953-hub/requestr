package com.example.data.repository

import java.util.concurrent.TimeUnit

/**
 * Pure formatter for the relative-timestamp pill on Home's "Recently opened" rows
 * (home_rebranding.md §6: `"3m ago"`, `"5m ago"`, `"12m ago"`, `"25m ago"`).
 *
 * No Android or Compose dependency so it is directly unit-testable; `now` is a parameter for
 * the same reason. Buckets are chosen by the elapsed time between [format]'s `nowMillis` and
 * the row's real `openedAt`, so it can never print anything a row's timestamp does not back --
 * this replaces the literal `"Recent"`/`"Saved"` strings Home used to hardcode.
 *
 * | elapsed                | output      |
 * |------------------------|-------------|
 * | < 1 minute (or future) | `just now`  |
 * | < 1 hour               | `Nm ago`    |
 * | < 1 day                | `Nh ago`    |
 * | < 7 days               | `Nd ago`    |
 * | < 30 days              | `Nw ago`    |
 * | < 365 days             | `Nmo ago`   |
 * | otherwise              | `Ny ago`    |
 *
 * A timestamp slightly in the future (device clock moved backwards after the row was written)
 * reads as `just now` rather than a negative number.
 */
object RelativeTimeFormatter {

    private val MINUTE_MS = TimeUnit.MINUTES.toMillis(1)
    private val HOUR_MS = TimeUnit.HOURS.toMillis(1)
    private val DAY_MS = TimeUnit.DAYS.toMillis(1)
    private val WEEK_MS = TimeUnit.DAYS.toMillis(7)
    private val MONTH_MS = TimeUnit.DAYS.toMillis(30)
    private val YEAR_MS = TimeUnit.DAYS.toMillis(365)

    fun format(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val elapsed = nowMillis - epochMillis
        return when {
            elapsed < MINUTE_MS -> "just now"
            elapsed < HOUR_MS -> "${elapsed / MINUTE_MS}m ago"
            elapsed < DAY_MS -> "${elapsed / HOUR_MS}h ago"
            elapsed < WEEK_MS -> "${elapsed / DAY_MS}d ago"
            elapsed < MONTH_MS -> "${elapsed / WEEK_MS}w ago"
            elapsed < YEAR_MS -> "${elapsed / MONTH_MS}mo ago"
            else -> "${elapsed / YEAR_MS}y ago"
        }
    }
}
