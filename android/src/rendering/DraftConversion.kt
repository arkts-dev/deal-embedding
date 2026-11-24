package dev.deal.embedding

/**
 * Checked conversion of a typed draft into the authoritative value it names.
 *
 * A draft is user text, so nothing is coerced: only the exact declared spelling commits, and every
 * other spelling stays visible and uncommitted. The decimal grammar is
 * `[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)` - no grouping separators, units, exponent or whitespace, and
 * no locale-specific decimal comma. The temporal domains are the shared ones: minute-of-day integral
 * 0-1439 for a time and local epoch-day integral for a date.
 */
internal object DraftConversion {
    private val decimal = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)")
    private val clock = Regex("([0-9]{1,2}):([0-9]{2})")

    /** Last minute of the shared time domain. */
    const val LAST_MINUTE = 1439.0

    fun decimal(text: String): Double? =
        if (decimal.matches(text)) text.toDoubleOrNull()?.takeIf { it.isFinite() } else null

    /** A count is integral: the same grammar, restricted to a whole value. */
    fun integral(text: String): Double? = decimal(text)?.takeIf { it == kotlin.math.floor(it) }

    /**
     * Minute-of-day, spelled `H:MM`/`HH:MM` or as the domain value itself, inside 0-1439. A spelling
     * outside the domain never commits.
     */
    fun timeOfDay(text: String): Double? {
        val parts = clock.matchEntire(text)
        if (parts != null) {
            val hours = parts.groupValues[1].toDouble()
            val minutes = parts.groupValues[2].toDouble()
            if (hours > 23 || minutes > 59) return null
            return hours * 60 + minutes
        }
        return integral(text)?.takeIf { it >= 0 && it <= LAST_MINUTE }
    }

    /** Local epoch-day, integral. */
    fun day(text: String): Double? = integral(text)

    /** The time domain's own presentation: `HH:MM` of a minute-of-day. */
    fun timeText(minutes: Double): String {
        if (!minutes.isFinite()) return ""
        val whole = kotlin.math.floor(minutes).toLong()
        val hours = Math.floorDiv(whole, 60L)
        val rest = whole - hours * 60L
        return NumericPresentation.format(hours.toDouble(), "", "", 2, 0) + ":" +
            NumericPresentation.format(rest.toDouble(), "", "", 2, 0)
    }

    /** The date domain's own presentation: the local epoch-day. */
    fun dayText(day: Double): String =
        if (!day.isFinite()) "" else NumericPresentation.format(kotlin.math.floor(day), "", "", 1, 0)
}
