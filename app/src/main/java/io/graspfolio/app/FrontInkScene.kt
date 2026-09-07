package io.graspfolio.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF

/** Owned exclusively by the front-buffer worker. No UI-thread collections cross into it. */
internal class FrontInkScene {
    private data class Trace(val page: PagePlacement, val points: MutableList<InkPoint>, var id: String? = null)
    private val finished = mutableListOf<Trace>()
    private var active: Trace? = null
    private var prediction: InkPoint? = null
    private val dirty = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff111111.toInt(); strokeCap = Paint.Cap.ROUND }
    val pendingCount get() = finished.size
    private fun bounds(page: PagePlacement, a: InkPoint, b: InkPoint = a): RectF {
        val pad = 2f * page.scale + 2f
        return RectF(page.left + minOf(a.x, b.x) * page.scale - pad, page.top + minOf(a.y, b.y) * page.scale - pad,
            page.left + maxOf(a.x, b.x) * page.scale + pad, page.top + maxOf(a.y, b.y) * page.scale + pad)
    }
    private fun mark(trace: Trace) { for (i in trace.points.indices) dirty.union(bounds(trace.page, trace.points[i], trace.points[maxOf(0, i - 1)])) }
    private fun markPrediction() { active?.let { trace -> prediction?.let { p -> trace.points.lastOrNull()?.let { dirty.union(bounds(trace.page, it, p)) } } } }
    fun begin(page: PagePlacement) { cancelActive(); active = Trace(page, mutableListOf()) }
    fun append(points: List<InkPoint>, nextPrediction: InkPoint?) {
        val trace = active ?: return
        markPrediction()
        for (point in points) {
            dirty.union(bounds(trace.page, trace.points.lastOrNull() ?: point, point))
            trace.points += point
        }
        prediction = nextPrediction; markPrediction()
    }
    fun finish(id: String) {
        markPrediction(); prediction = null
        active?.let { it.id = id; finished += it }; active = null
    }
    fun handoff(ids: Set<String>) {
        val iterator = finished.iterator()
        while (iterator.hasNext()) { val trace = iterator.next(); if (trace.id in ids) { mark(trace); iterator.remove() } }
    }
    fun cancelActive() { active?.let(::mark); markPrediction(); active = null; prediction = null }
    fun reset() { finished.clear(); active = null; prediction = null; dirty.setEmpty() }
    fun draw(canvas: Canvas, width: Int, height: Int, full: Boolean = false) {
        val region = if (full) RectF(0f, 0f, width.toFloat(), height.toFloat()) else RectF(dirty)
        if (region.isEmpty) return
        canvas.save(); canvas.clipRect(region); canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        for (trace in finished) drawTrace(canvas, trace, region)
        active?.let { trace ->
            drawTrace(canvas, trace, region)
            prediction?.let { end -> trace.points.lastOrNull()?.let { segment(canvas, trace.page, it, end) } }
        }
        canvas.restore(); dirty.setEmpty()
    }
    private fun drawTrace(canvas: Canvas, trace: Trace, region: RectF) {
        for (i in trace.points.indices) {
            val point = trace.points[i]; val previous = trace.points[maxOf(0, i - 1)]
            if (RectF.intersects(region, bounds(trace.page, previous, point))) segment(canvas, trace.page, if (i == 0) null else previous, point)
        }
    }
    private fun segment(canvas: Canvas, page: PagePlacement, from: InkPoint?, to: InkPoint) {
        canvas.save(); canvas.clipRect(page.left, page.top, page.left + page.width * page.scale, page.top + page.height * page.scale)
        canvas.translate(page.left, page.top); canvas.scale(page.scale, page.scale)
        if (from == null) canvas.drawCircle(to.x, to.y, pressureWidth(2f, to.pressure) / 2, paint)
        else { paint.strokeWidth = pressureWidth(2f, (from.pressure + to.pressure) / 2); canvas.drawLine(from.x, from.y, to.x, to.y, paint) }
        canvas.restore()
    }
}
