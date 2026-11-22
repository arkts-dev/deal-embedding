package dev.deal.embedding

/**
 * Native Timer presentation over Unix epoch milliseconds. `millis` is a deadline when `down` is
 * true and an origin otherwise; the presentation clock is a wall-clock reading in epoch
 * milliseconds. Every reading recomputes the distance from that clock, so a reading taken after
 * hiding reports the current distance and never replays the ticks it skipped.
 *
 * Ticking is presentation only: this object mutates no state and dispatches nothing.
 */
internal object TimerPresentation {
    /** Largest exact whole-millisecond distance (2^53); a farther distance paints the saturating
     *  `Long.MAX_VALUE` reading and stops ticking. */
    private const val MAX_MILLIS = 9_007_199_254_740_992.0

    private const val SECOND = 1_000L

    /** A wait longer than a day is re-derived from the clock, so no tick waits an unbounded span. */
    private const val MAX_WAIT = 86_400_000L

    /** The displayed text: remaining time for a deadline, elapsed time for an origin. */
    fun text(epochMillis: Double, down: Boolean, clockMillis: Long): String =
        format(seconds(epochMillis, down, clockMillis)) + if (down) " left" else " ago"

    /**
     * Milliseconds until the displayed text can change, or null when it will not change again.
     * A finished or saturated deadline stops ticking, as does an epoch too far away to display, so
     * the caller's loop ends and releases its timer instead of idling forever. A far-future origin
     * waits once for the second its own display changes, bounded by one day so a corrected clock is
     * re-read; the wait is cancelled and restarted from the clock when the node is hidden, disposed
     * or repainted with another identity.
     */
    fun nextTickMillis(epochMillis: Double, down: Boolean, clockMillis: Long): Long? {
        val distance = distance(epochMillis, clockMillis, down)
        // This guard must precede the boundary arithmetic: it keeps a saturated reading's second count
        // away from a `* SECOND` that would overflow back into a one-millisecond spin.
        if (!distance.isFinite() || kotlin.math.abs(distance) >= MAX_MILLIS) return null
        if (down && distance <= 0.0) return null
        // Wait for the boundary the display itself uses, so text and wake-up never disagree: the
        // remainder is rounded up, so no wake lands a millisecond before the second it announces and
        // no sub-millisecond remainder spins on zero.
        val displayed = seconds(epochMillis, down, clockMillis)
        val boundary = if (down) (displayed - 1L) * SECOND else (displayed + 1L) * SECOND
        val remaining = if (down) distance - boundary else boundary - distance
        return kotlin.math.ceil(remaining).toLong().coerceIn(1L, MAX_WAIT)
    }

    /** Whole displayed seconds: a deadline rounds up so it reaches zero at the deadline, never before. */
    private fun seconds(epochMillis: Double, down: Boolean, clockMillis: Long): Long {
        val distance = distance(epochMillis, clockMillis, down)
        // A nonfinite epoch is rejected at ingress; the unvalidated one still reads as no distance.
        if (!distance.isFinite() || distance <= 0.0) return 0L
        if (distance >= MAX_MILLIS) return Long.MAX_VALUE
        // A sub-millisecond remainder is still a remaining second for a deadline, never a premature zero.
        return (if (down) kotlin.math.ceil(distance / SECOND) else kotlin.math.floor(distance / SECOND)).toLong()
    }

    private fun distance(epochMillis: Double, clockMillis: Long, down: Boolean): Double =
        if (down) epochMillis - clockMillis.toDouble() else clockMillis.toDouble() - epochMillis

    private fun format(seconds: Long): String {
        val hours = seconds / 3_600L
        val minutes = seconds % 3_600L / 60L
        val rest = seconds % 60L
        return if (hours > 0) "$hours:${pad(minutes)}:${pad(rest)}" else "$minutes:${pad(rest)}"
    }

    private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()
}
