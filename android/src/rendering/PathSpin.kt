package dev.deal.embedding

/**
 * The declared Path rotation.
 *
 * `spinPeriodMs` is a full-turn period; every other declared period is a still Path. The rotation is
 * presentation only: it advances with visible frames and stops when the node leaves or the page is
 * covered, so it never becomes a computation clock.
 */
internal object PathSpin {
    const val TURN_DEGREES = 360f

    /** Rotation at one visible elapsed time, modulo the declared period. */
    fun rotationDegrees(spinPeriodMs: Double, elapsedMillis: Long): Float {
        if (!spinPeriodMs.isFinite() || spinPeriodMs <= 0 || elapsedMillis <= 0L) return 0f
        val elapsed = elapsedMillis.toDouble() % spinPeriodMs
        return (TURN_DEGREES * (elapsed / spinPeriodMs)).toFloat()
    }
}
