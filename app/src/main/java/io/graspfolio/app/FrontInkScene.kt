package io.graspfolio.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Build

/** Owned exclusively by the front-buffer worker. No UI-thread collections cross into it. */
internal class FrontInkScene {
    private data class Trace(val page: PagePlacement, val points: MutableList<InkPoint>, val style: BrushStyle, var id: String? = null, var recorded: RecordedInkTrace? = null)
    var recordedSegmentsLastDraw = 0; private set
    private fun release(trace: Trace) { if (Build.VERSION.SDK_INT >= 29) trace.recorded?.release() }
    private val finished = mutableListOf<Trace>()
    private var active: Trace? = null
    private var prediction: InkPoint? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff111111.toInt(); strokeCap = Paint.Cap.ROUND }
    val pendingCount get() = finished.size
    fun begin(page: PagePlacement, style: BrushStyle = BrushStyle()) { cancelActive(); active = Trace(page, mutableListOf(), style) }
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
        finished.removeAll { if (it.id in ids) { release(it); true } else false }
    }
    fun cancelActive() { active?.let(::release); active = null; prediction = null }
    fun reset() { finished.forEach(::release); finished.clear(); cancelActive() }
    fun draw(canvas: Canvas, width: Int, height: Int) {
        recordedSegmentsLastDraw = 0
        // Each callback records a complete display list. Do not depend on pixels surviving
        // a previous hardware-buffer submission (including callbacks without new samples).
        // Only transient ink is replayed here, never the PDF or the saved annotation layer.
        canvas.save(); canvas.clipRect(0, 0, width, height); canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        for (trace in finished) drawTrace(canvas, trace)
        active?.let { trace ->
            drawTrace(canvas, trace)
        }
        canvas.restore()
    }
    private fun drawTrace(canvas: Canvas, trace: Trace) {
        val page = trace.page
        canvas.save(); canvas.clipRect(page.left, page.top, page.left + page.width * page.scale, page.top + page.height * page.scale)
        canvas.translate(page.left, page.top); canvas.scale(page.scale, page.scale)
        val checkpoint = if (trace.style.alpha < 255) canvas.saveLayerAlpha(0f, 0f, page.width, page.height, trace.style.alpha) else canvas.save()
        paint.color = trace.style.opaqueColor
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
            val recorded = trace.recorded ?: RecordedInkTrace().also { trace.recorded = it }
            recorded.draw(canvas, page, trace.points) { target, from, to -> drawSegment(target, from, to, trace.style) }
            recordedSegmentsLastDraw += recorded.recordedThisDraw
        } else for (i in trace.points.indices) {
            val point = trace.points[i]; val previous = trace.points[maxOf(0, i - 1)]
            drawSegment(canvas, if (i == 0) null else previous, point, trace.style)
        }
        if (trace === active) prediction?.let { end -> trace.points.lastOrNull()?.let { drawSegment(canvas, it, end, trace.style) } }
        canvas.restoreToCount(checkpoint)
        canvas.restore()
    }
    private fun drawSegment(canvas: Canvas, from: InkPoint?, to: InkPoint, style: BrushStyle) {
        if (from == null) canvas.drawCircle(to.x, to.y, style.widthAt(to.pressure) / 2, paint)
        else { paint.strokeWidth = style.widthAt((from.pressure + to.pressure) / 2); canvas.drawLine(from.x, from.y, to.x, to.y, paint) }
    }
}
