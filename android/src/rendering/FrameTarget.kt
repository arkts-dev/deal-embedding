package dev.deal.embedding

/** One frame delivery decision for a node that declares `onFrame`. */
internal sealed interface FramePlan {
    /** The node declares no `onFrame`: a static Surface receives no frame and dispatches nothing. */
    data object None : FramePlan

    /** The target slot and the nonnegative millisecond delta; the first frame after activation or resume is zero. */
    data class Frame(val slot: Int, val deltaMillis: Long) : FramePlan
}

/**
 * Frame targeting and delta rule over neutral values: only a node declaring `onFrame` receives
 * frames, and the first frame after activation or resume has delta zero with hidden time excluded.
 */
internal object FrameTarget {
    fun plan(frameSlot: Int?, previousMillis: Long?, nowMillis: Long): FramePlan {
        if (frameSlot == null) return FramePlan.None
        if (previousMillis == null) return FramePlan.Frame(frameSlot, 0L)
        return FramePlan.Frame(frameSlot, (nowMillis - previousMillis).coerceAtLeast(0L))
    }
}
