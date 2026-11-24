package dev.deal.embedding

/** The declared gap a container paints between its children; the painter maps it to theme geometry. */
internal enum class GapToken { TIGHT, GROUP, SCREEN }

/** Horizontal placement of a container's children. */
internal enum class Horizontal { START, CENTER, END }

/** How a Row arranges its children: the adaptive wrap, one anchored row, or a split/joined row. */
internal enum class RowArrangement { WRAP, CENTER, END, SPLIT, JOINED }

/** A declared surface tone, painted as the theme's surface or accent background. */
internal enum class Tone { NONE, SURFACE, ACCENT }

internal data class ColumnMode(val gap: GapToken, val align: Horizontal, val tone: Tone)

internal data class RowMode(val arrangement: RowArrangement, val gap: GapToken, val tone: Tone)

/**
 * The declared container and column modes.
 *
 * `Column` and `Row` carry one family of styles; `split` and `joined` belong to `Row` only, so a
 * Column asking for them falls back through [StyleFamilies]. An empty style keeps the base layout
 * the component paints without a style, which takes its gap from the theme's group geometry.
 * The grid family (`default`, `compact`, `spacious`) names the gap between a decided grid's columns
 * and rows.
 */
internal object ContainerModes {
    fun column(style: String): ColumnMode {
        val resolved = StyleFamilies.resolve("Column", style)
        return ColumnMode(gap = gap(resolved), align = align(resolved), tone = tone(resolved))
    }

    fun row(style: String): RowMode {
        val resolved = StyleFamilies.resolve("Row", style)
        val arrangement = when (resolved) {
            "center" -> RowArrangement.CENTER
            "end" -> RowArrangement.END
            "split" -> RowArrangement.SPLIT
            "joined" -> RowArrangement.JOINED
            else -> RowArrangement.WRAP
        }
        return RowMode(arrangement = arrangement, gap = gap(resolved), tone = tone(resolved))
    }

    /** The grid family's declared gap, shared by Grid, MetricGroup, KeyValueGroup and AdaptiveColumns. */
    fun gridGap(component: String, style: String): GapToken = gap(StyleFamilies.resolve(component, style))

    private fun gap(style: String): GapToken = when (style) {
        "compact" -> GapToken.TIGHT
        "spacious" -> GapToken.SCREEN
        else -> GapToken.GROUP
    }

    private fun align(style: String): Horizontal = when (style) {
        "center" -> Horizontal.CENTER
        "end" -> Horizontal.END
        else -> Horizontal.START
    }

    private fun tone(style: String): Tone = when (style) {
        "surface" -> Tone.SURFACE
        "accent" -> Tone.ACCENT
        else -> Tone.NONE
    }

    /**
     * The declared `columns` count of Grid, MetricGroup and KeyValueGroup. Ingress admits an
     * integral count; a count below one paints one column instead of a degenerate row.
     */
    fun columns(declared: Double): Int =
        if (!declared.isFinite()) 1 else declared.toInt().coerceAtLeast(1)

    /**
     * AdaptiveColumns: two columns only when two minimum widths plus the theme gap fit in the
     * available width, otherwise one column.
     */
    fun adaptiveColumns(widthPx: Int, minimumPx: Int, gapPx: Int): Int {
        if (minimumPx <= 0) return 1
        return if (widthPx.toLong() >= 2L * minimumPx.toLong() + gapPx.toLong()) 2 else 1
    }
}
