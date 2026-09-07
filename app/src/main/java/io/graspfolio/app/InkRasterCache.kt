package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.sqrt

/** Display-only, bounded to 16 MB per layer; vector data remains the source of truth. */
internal class InkRasterCache {
    private var bitmap: Bitmap? = null
    private var target: Canvas? = null
    private var scale = 1f
    private val destination = Rect()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    var generation = 0; private set
    fun resize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (destination.width() == width && destination.height() == height && bitmap != null) return false
        scale = minOf(1f, sqrt(4_000_000f / (width.toFloat() * height)))
        bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        target = Canvas(bitmap!!); destination.set(0, 0, width, height); generation++
        return true
    }
    fun clear() { bitmap?.eraseColor(Color.TRANSPARENT) }
    fun release() { target = null; bitmap = null; destination.setEmpty() }
    fun show(canvas: Canvas) { bitmap?.let { canvas.drawBitmap(it, null, destination, blit) } }
    fun stroke(page: PagePlacement, stroke: InkStroke) {
        val points = stroke.points
        if (points.isEmpty()) return
        segment(page, null, points[0], stroke.color, stroke.width)
        for (i in 1 until points.size) segment(page, points[i - 1], points[i], stroke.color, stroke.width)
    }
    fun segment(page: PagePlacement, from: InkPoint?, to: InkPoint, color: Int, width: Float) {
        val canvas = target ?: return
        canvas.save(); canvas.scale(scale, scale)
        canvas.clipRect(page.left, page.top, page.left + page.width * page.scale, page.top + page.height * page.scale)
        canvas.translate(page.left, page.top); canvas.scale(page.scale, page.scale)
        pen.color = color
        if (from == null) canvas.drawCircle(to.x, to.y, pressureWidth(width, to.pressure) / 2, pen)
        else {
            pen.strokeWidth = pressureWidth(width, (from.pressure + to.pressure) / 2)
            canvas.drawLine(from.x, from.y, to.x, to.y, pen)
        }
        canvas.restore()
    }
}
