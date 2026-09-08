package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/** Display-only, one pixel per viewport pixel; vector data remains the source of truth. */
internal class InkRasterCache {
    private var bitmap: Bitmap? = null
    private var target: Canvas? = null
    private var repairBitmap: Bitmap? = null
    private val replacePixels = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }
    private val destination = Rect()
    private val blit = Paint()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    var generation = 0; private set
    fun resize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (destination.width() == width && destination.height() == height && bitmap != null) return false
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        repairBitmap = null
        target = Canvas(bitmap!!); destination.set(0, 0, width, height); generation++
        return true
    }
    fun clear() { bitmap?.eraseColor(Color.TRANSPARENT) }
    fun repair(region: Rect, coverage: Rect, draw: () -> Unit) {
        val original = target ?: return
        var scratch = repairBitmap
        if (scratch == null) {
            scratch = Bitmap.createBitmap(destination.width(), destination.height(), Bitmap.Config.ARGB_8888)
            repairBitmap = scratch
        }
        val canvas = Canvas(scratch)
        // Keep the same device-space origin and clip as the full cache: software stroke
        // rasterization can otherwise round antialiased edge coverage differently.
        canvas.save(); canvas.clipRect(coverage)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.restore()
        target = canvas
        try { draw() } finally { target = original }
        original.drawBitmap(scratch, region, region, replacePixels)
    }
    fun release() { target = null; bitmap = null; repairBitmap = null; destination.setEmpty() }
    fun show(canvas: Canvas) { bitmap?.let { canvas.drawBitmap(it, null, destination, blit) } }
    fun stroke(page: PagePlacement, stroke: InkStroke) {
        val points = stroke.points
        if (points.isEmpty()) return
        val canvas = target ?: return
        val style = stroke.style
        // A marker must not allocate a full-page temporary layer for every short stroke.
        val checkpoint = if (style.alpha < 255) {
            val bounds = inkBounds(stroke)
            val left = maxOf(page.left, page.left + bounds.left * page.scale - 2)
            val top = maxOf(page.top, page.top + bounds.top * page.scale - 2)
            val right = minOf(page.left + page.width * page.scale, page.left + bounds.right * page.scale + 2)
            val bottom = minOf(page.top + page.height * page.scale, page.top + bounds.bottom * page.scale + 2)
            if (left >= right || top >= bottom) return
            canvas.saveLayerAlpha(left, top, right, bottom, style.alpha)
        } else canvas.save()
        segment(page, null, points[0], style.opaqueColor, style.width, style.brush)
        for (i in 1 until points.size) segment(page, points[i - 1], points[i], style.opaqueColor, style.width, style.brush)
        canvas.restoreToCount(checkpoint)
    }
    fun segment(page: PagePlacement, from: InkPoint?, to: InkPoint, color: Int, width: Float, brush: String = "pressure") {
        val canvas = target ?: return
        canvas.save()
        canvas.clipRect(page.left, page.top, page.left + page.width * page.scale, page.top + page.height * page.scale)
        canvas.translate(page.left, page.top); canvas.scale(page.scale, page.scale)
        pen.color = color
        fun lineWidth(pressure: Float) = if (brush == "pressure") pressureWidth(width, pressure) else width
        if (from == null) canvas.drawCircle(to.x, to.y, lineWidth(to.pressure) / 2, pen)
        else {
            pen.strokeWidth = lineWidth((from.pressure + to.pressure) / 2)
            canvas.drawLine(from.x, from.y, to.x, to.y, pen)
        }
        canvas.restore()
    }
}
