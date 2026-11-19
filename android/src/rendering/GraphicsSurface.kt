package dev.deal.embedding

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray

/** Drawing and local pointer transport only; geometry and game state remain in DEAL.
 * Ingress validation decides validity before this painter runs. */
internal fun paintShapes(canvas: Canvas, shapes: JSONArray, depth: Int, count: IntArray) {
    require(depth <= 12)
    for (i in 0 until shapes.length()) {
        require(++count[0] <= 4000)
        val shape = shapes.getJSONObject(i)
        fun n(name: String) = shape.getDouble(name).also { require(it.isFinite()) }.toFloat()
        val tag = shape.getString("tag")
        if (tag == "group") {
            val alpha = n("alpha"); require(alpha in 0f..1f); val scale = n("scale"); require(scale >= 0)
            val saved = canvas.saveLayerAlpha(null, (alpha * 255).toInt())
            try { canvas.translate(n("x"), n("y")); canvas.rotate(n("rotation")); canvas.scale(scale, scale); paintShapes(canvas, shape.getJSONArray("shapes"), depth + 1, count) } finally { canvas.restoreToCount(saved) }
            continue
        }
        val color = shape.getString("color"); require(color.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))
        val argb = if (color.length == 9) "#" + color.substring(7, 9) + color.substring(1, 7) else color
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.parseColor(argb) }
        if (tag in setOf("line", "polyline", "arc", "strokePath")) { paint.style = Paint.Style.STROKE; paint.strokeWidth = n("lineWidth"); require(paint.strokeWidth >= 0) }
        when (tag) {
            "rect", "roundRect" -> { val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height"); require(w >= 0 && h >= 0); val rect = RectF(x, y, x + w, y + h); if (tag == "rect") canvas.drawRect(rect, paint) else { val r = n("radius"); require(r >= 0); canvas.drawRoundRect(rect, r, r, paint) } }
            "circle" -> { val r = n("radius"); require(r >= 0); canvas.drawCircle(n("x"), n("y"), r, paint) }
            "arc" -> { val r = n("radius"); require(r >= 0); val x = n("x"); val y = n("y"); canvas.drawArc(RectF(x - r, y - r, x + r, y + r), n("startDegrees") - 90, n("sweepDegrees"), false, paint) }
            "line" -> canvas.drawLine(n("x1"), n("y1"), n("x2"), n("y2"), paint)
            "polygon", "polyline" -> { val points = shape.getJSONArray("points"); require(points.length() <= 2000 && points.length() % 2 == 0); val path = android.graphics.Path(); for (j in 0 until points.length() step 2) { val x = points.getDouble(j); val y = points.getDouble(j + 1); require(x.isFinite() && y.isFinite()); if (j == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat()) }; if (tag == "polygon") path.close(); canvas.drawPath(path, paint) }
            "path", "strokePath" -> canvas.drawPath(checkNotNull(androidx.core.graphics.PathParser.createPathFromPathData(shape.getString("commands"))), paint)
            "text" -> { paint.textSize = n("size"); require(paint.textSize >= 0); paint.isFakeBoldText = shape.getBoolean("bold"); paint.textAlign = when (shape.getString("align")) { "left" -> Paint.Align.LEFT; "center" -> Paint.Align.CENTER; "right" -> Paint.Align.RIGHT; else -> error("Graphics text alignment") }; canvas.drawText(shape.getString("text"), n("x"), n("y") - (paint.ascent() + paint.descent()) / 2, paint) }
            else -> error("Unknown graphics shape")
        }
    }
}
