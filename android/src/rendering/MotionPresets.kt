package dev.deal.embedding

/** One sample of a Motion preset: a scale, a clockwise rotation, a translation and an opacity. */
internal data class MotionFrame(
    val at: Float,
    val scale: Float = 1f,
    val rotate: Float = 0f,
    val moveX: Float = 0f,
    val moveY: Float = 0f,
    val alpha: Float = 1f,
)

/**
 * The declared Motion presets.
 *
 * The style names the preset; the trigger only decides when it plays, and it plays once per changed
 * trigger after the first render. A trajectory is presentation only: it never governs computation.
 * Every preset ends at the resting frame, so a finished motion leaves the content exactly where an
 * unmoved node paints it.
 */
internal object MotionPresets {
    private val resting = MotionFrame(1f)

    private val presets: Map<String, List<MotionFrame>> = linkedMapOf(
        "pop" to listOf(MotionFrame(0f, scale = 0.6f, alpha = 0.4f), resting),
        "pulse" to listOf(MotionFrame(0f), MotionFrame(0.5f, scale = 1.12f), resting),
        "jelly" to listOf(MotionFrame(0f), MotionFrame(0.3f, scale = 1.18f, rotate = -3f),
            MotionFrame(0.65f, scale = 0.92f, rotate = 3f), resting),
        "press" to listOf(MotionFrame(0f, scale = 1.08f), resting),
        "flash" to listOf(MotionFrame(0f, alpha = 0.2f), MotionFrame(0.5f, alpha = 1f), resting),
        "breathe" to listOf(MotionFrame(0f), MotionFrame(0.5f, scale = 1.08f), resting),
        "shake" to listOf(MotionFrame(0f), MotionFrame(0.2f, moveX = -8f), MotionFrame(0.4f, moveX = 8f),
            MotionFrame(0.6f, moveX = -6f), MotionFrame(0.8f, moveX = 6f), resting),
        "nod" to listOf(MotionFrame(0f), MotionFrame(0.35f, rotate = 6f), MotionFrame(0.7f, rotate = -4f), resting),
        "wobble" to listOf(MotionFrame(0f, rotate = -6f), MotionFrame(0.35f, rotate = 6f),
            MotionFrame(0.7f, rotate = -4f), resting),
        "swing" to listOf(MotionFrame(0f, rotate = -10f), MotionFrame(0.4f, rotate = 10f),
            MotionFrame(0.75f, rotate = -6f), resting),
        "recoil" to listOf(MotionFrame(0f), MotionFrame(0.35f, moveY = 8f), resting),
        "bounce" to listOf(MotionFrame(0f), MotionFrame(0.45f, moveY = -20f), MotionFrame(0.75f, moveY = -6f), resting),
        "hop" to listOf(MotionFrame(0f), MotionFrame(0.5f, moveY = -16f, scale = 1.04f), resting),
        "jump" to listOf(MotionFrame(0f), MotionFrame(0.5f, moveY = -28f), MotionFrame(0.8f, moveY = -4f), resting),
        "fall" to listOf(MotionFrame(0f, moveY = -24f, alpha = 0.3f), resting),
        "spin" to listOf(MotionFrame(0f), MotionFrame(0.5f, rotate = 180f), resting),
        "turn" to listOf(MotionFrame(0f, scale = 0.9f), MotionFrame(0.5f, rotate = 90f), resting),
        "tumble" to listOf(MotionFrame(0f, scale = 0.6f, rotate = -120f), MotionFrame(0.6f, rotate = 40f), resting),
        "flip" to listOf(MotionFrame(0f, scale = 0.2f, alpha = 0.6f), resting),
        "fade" to listOf(MotionFrame(0f, alpha = 0f), resting),
        "rise" to listOf(MotionFrame(0f, moveY = 16f, alpha = 0f), resting),
        "zoom" to listOf(MotionFrame(0f, scale = 0.8f, alpha = 0f), resting),
        "slide" to listOf(MotionFrame(0f, moveX = -24f, alpha = 0f), resting),
        "float" to listOf(MotionFrame(0f, moveY = -8f, alpha = 0.4f), resting),
        "drift" to listOf(MotionFrame(0f, moveX = 12f), resting),
    )

    /** The preset a resolved Motion style names; the declared pop is the fallback for any other name. */
    fun preset(style: String): List<MotionFrame> = presets[style] ?: presets.getValue("pop")

    val names: List<String> get() = presets.keys.toList()

    /** Whether this composition animates: a first render never does, and an unchanged trigger never does. */
    fun animates(previous: Double?, trigger: Double): Boolean = previous != null && previous != trigger

    /** Linear interpolation between the surrounding frames of a preset trajectory. */
    fun sample(frames: List<MotionFrame>, progress: Float): MotionFrame {
        if (frames.isEmpty()) return resting
        val at = progress.coerceIn(0f, 1f)
        val first = frames.first()
        if (at <= first.at) return first
        for (index in 1 until frames.size) {
            val previous = frames[index - 1]
            val next = frames[index]
            if (at > next.at) continue
            val span = next.at - previous.at
            val fraction = if (span <= 0f) 1f else (at - previous.at) / span
            return MotionFrame(
                at = at,
                scale = lerp(previous.scale, next.scale, fraction),
                rotate = lerp(previous.rotate, next.rotate, fraction),
                moveX = lerp(previous.moveX, next.moveX, fraction),
                moveY = lerp(previous.moveY, next.moveY, fraction),
                alpha = lerp(previous.alpha, next.alpha, fraction),
            )
        }
        return frames.last().copy(at = at)
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction
}
