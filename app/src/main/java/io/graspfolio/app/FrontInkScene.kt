package io.graspfolio.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff

/** Owned exclusively by the front-buffer worker. No UI-thread collections cross into it. */
internal class FrontInkScene {
    private data class Trace(val page: PagePlacement, val points: MutableList<InkPoint>, var id: String? = null)
    private val finished = mutableListOf<Trace>()
    private var active: Trace? = null
    private var prediction: InkPoint? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff111111.toInt(); strokeCap = Paint.Cap.ROUND }
    val pendingCount get() = finished.size
    fun begin(page: PagePlacement) { cancelActive(); active = Trace(page, mutableListOf()) }
    fun append(points: List<InkPoint>, nextPrediction: InkPoint?) {
        val trace = active ?: return
        trace.points.addAll(points)
        prediction = nextPrediction
    }
    fun finish(id: String) {
        prediction = null
        active?.let { it.id = id; finished += it }; active = null
    }
    fun handoff(ids: Set<String>) {
        finished.removeAll { it.id in ids }
    }
    fun cancelActive() { active = null; prediction = null }
    fun reset() { finished.clear(); active = null; prediction = null }
    fun draw(canvas: Canvas, width: Int, height: Int) {
        // Each callback records a complete display list. Do not depend on pixels surviving
        // a previous hardware-buffer submission (including callbacks without new samples).
        // Only transient ink is replayed here, never the PDF or the saved annotation layer.
        canvas.save(); canvas.clipRect(0, 0, width, height); canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        for (trace in finished) drawTrace(canvas, trace)
        active?.let { trace ->
            drawTrace(canvas, trace)
            prediction?.let { end -> trace.points.lastOrNull()?.let { segment(canvas, trace.page, it, end) } }
        }
        canvas.restore()
    }
    private fun drawTrace(canvas: Canvas, trace: Trace) {
        for (i in trace.points.indices) {
            val point = trace.points[i]; val previous = trace.points[maxOf(0, i - 1)]
            segment(canvas, trace.page, if (i == 0) null else previous, point)
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
