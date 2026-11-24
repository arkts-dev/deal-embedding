package dev.deal.embedding

/**
 * The declared Transform pose and the Stage's shared perspective.
 *
 * A pose is `[rotateX, rotateY, rotateZ, moveX, moveY, moveZ, scale, opacity]`: finite, with opacity
 * inside [0, 1], and `[0, 0, 0, 0, 0, 0, 1, 1]` as its identity. The pose is a presentation value:
 * interpolating it, hiding its backface or ordering Stage children never changes business state.
 */
internal object PoseMotion {
    val IDENTITY = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0)

    /** The declared pose, or the identity when the value is not a finite eight-entry pose. */
    fun normalised(pose: List<Double>): List<Double> =
        if (pose.size == 8 && pose.all { it.isFinite() }) pose else IDENTITY

    /** Linear interpolation of the whole pose from one committed value to the next. */
    fun interpolate(from: List<Double>, to: List<Double>, progress: Double): List<Double> {
        val start = normalised(from)
        val end = normalised(to)
        val at = progress.coerceIn(0.0, 1.0)
        return start.indices.map { index -> start[index] + (end[index] - start[index]) * at }
    }

    /**
     * Whether a committed pose is interpolated: only a declared positive duration animates one. A zero
     * duration applies the committed pose at once, so no pose is dropped for want of a trajectory.
     */
    fun animates(durationMs: Double): Boolean = durationMs.isFinite() && durationMs > 0

    /**
     * Whether a pose shows a backface: the layer is turned more than a quarter turn away from the
     * viewer about either axis. Angles are taken modulo a full turn, so a heading of 280 degrees is
     * the same edge as -80 and is not a backface.
     */
    fun backface(pose: List<Double>): Boolean {
        val normalisedPose = normalised(pose)
        return turnedAway(normalisedPose[0]) || turnedAway(normalisedPose[1])
    }

    private fun turnedAway(degrees: Double): Boolean {
        var angle = degrees % 360.0
        if (angle > 180.0) angle -= 360.0
        if (angle <= -180.0) angle += 360.0
        return kotlin.math.abs(angle) > 90.0
    }

    /**
     * Depth of a pose for the Stage's painting order: its moveZ, larger being nearer the viewer. The
     * platform's layer has no z translation of its own, so the declared depth reaches the Stage's
     * order - the shared perspective the spec assigns it - and never a second, invented displacement.
     */
    fun depth(pose: List<Double>): Double = normalised(pose)[5]

    /**
     * The camera distance in pixels for a declared perspective in dp: the Stage's shared perspective,
     * or this renderer's default depth cue where the declaration leaves it unset. The declared number
     * is a depth in dp, so presentation scales it to the display's pixels; nothing else reads it.
     */
    fun cameraDistance(dp: Double, density: Float): Float {
        val scene = if (dp.isFinite() && dp > 0) dp else DEFAULT_PERSPECTIVE_DP
        return (scene * density.coerceAtLeast(0.5f)).toFloat()
    }

    /** This renderer's default depth cue in dp, where no Stage perspective is in force. */
    const val DEFAULT_PERSPECTIVE_DP = 8.0
}

/**
 * The Stage paints its Transform children in depth order under one shared perspective: the farther
 * layer first, so a nearer layer covers it. Ties keep the declared order, because painting order is
 * the only thing sorted and the declared order stays authoritative for equal depth.
 */
internal object StageSort {
    fun order(poses: List<List<Double>>): List<Int> =
        poses.indices.sortedBy { PoseMotion.depth(poses[it]) }
}
