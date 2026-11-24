package dev.deal.embedding

internal data class ChartPoint(val x: Float, val y: Float)

internal data class ChartRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * One Chart mode: the declared line-or-bar shape, the tone of the second series and, for a dataset
 * with no points, the explanation the node shows instead of an empty drawing.
 */
internal data class ChartMode(val bars: Boolean, val tone: String, val explanation: String?)

/**
 * The declared chart modes of Sparkline: `bars` selects labelled bars instead of a line,
 * `series2Style` the second series' tone family, `categories` the labels under the points, and
 * `minimum`/`maximum` the vertical scale. A dataset with no points is explained, never drawn blank.
 *
 * Geometry is presentation only and stays inside the drawing area: a value outside the declared
 * scale paints at the edge it passed instead of outside the node. Both series share one x axis, so a
 * shorter second series keeps the points of the first.
 */
internal object ChartModes {
    const val EMPTY = "No chart data"

    fun mode(series: List<Double>, second: List<Double>, bars: Boolean, secondStyle: String): ChartMode {
        val empty = series.isEmpty() && second.isEmpty()
        return ChartMode(bars = bars, tone = StyleFamilies.resolve("Sparkline", secondStyle), explanation = if (empty) EMPTY else null)
    }

    /** The number of points the shared x axis carries. */
    fun count(series: List<Double>, second: List<Double>): Int = maxOf(series.size, second.size)

    /** Whether a scale can place points: a finite ordered domain and at least one point. */
    fun plottable(minimum: Double, maximum: Double): Boolean =
        minimum.isFinite() && maximum.isFinite() && maximum > minimum

    /** Point geometry across the drawing width; each point sits at the centre of its share, like a bar. */
    fun points(values: List<Double>, count: Int, minimum: Double, maximum: Double, width: Float, height: Float): List<ChartPoint> {
        if (values.isEmpty() || count <= 0 || !plottable(minimum, maximum) || width <= 0f || height <= 0f) return emptyList()
        val share = width / count
        return values.mapIndexed { index, value -> ChartPoint(share * (index + 0.5f), y(value, minimum, maximum, height)) }
    }

    /** Bar geometry: one equal share of the shared axis per point, rising from the scale minimum. */
    fun bars(values: List<Double>, count: Int, minimum: Double, maximum: Double, width: Float, height: Float): List<ChartRect> {
        if (values.isEmpty() || count <= 0 || !plottable(minimum, maximum) || width <= 0f || height <= 0f) return emptyList()
        val share = width / count
        val inset = share / INSET_DIVISOR
        return values.mapIndexed { index, value ->
            ChartRect(
                left = share * index + inset,
                top = y(value, minimum, maximum, height),
                right = share * (index + 1) - inset,
                bottom = height,
            )
        }
    }

    /** The label of one point: its declared category at that very position, or empty where the tree
     *  declares none. A declaration that is not a string leaves its own point unlabelled instead of
     *  shifting every later label onto the wrong point. */
    fun category(index: Int, categories: List<String?>): String = categories.getOrElse(index) { "" } ?: ""

    /** Each bar leaves this fraction of its share as the gap to its neighbour. */
    private const val INSET_DIVISOR = 8f

    private fun y(value: Double, minimum: Double, maximum: Double, height: Float): Float {
        val fraction = ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0)
        return (height * (1.0 - fraction)).toFloat()
    }
}
