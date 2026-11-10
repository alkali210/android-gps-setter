package io.github.jqssun.gpssetter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.min

// A route's shape for the Saved list: the line in the accent colour, green start, red end.
// [points] comes from RouteMath.sketch (0..1 pairs); no map tiles involved.
class RouteSketchView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var points: FloatArray = FloatArray(0)
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = MaterialColors.getColor(this@RouteSketchView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        if (points.size < 4) return
        val pad = 6 * density
        val side = min(width, height) - 2 * pad
        val ox = (width - side) / 2
        val oy = (height - side) / 2
        path.reset()
        for (i in 0 until points.size / 2) {
            val x = ox + points[2 * i] * side
            val y = oy + points[2 * i + 1] * side
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
        val r = 3 * density
        dot.color = 0xFF22C55E.toInt()
        canvas.drawCircle(ox + points[0] * side, oy + points[1] * side, r, dot)
        dot.color = 0xFFEF4444.toInt()
        canvas.drawCircle(ox + points[points.size - 2] * side, oy + points[points.size - 1] * side, r, dot)
    }
}
