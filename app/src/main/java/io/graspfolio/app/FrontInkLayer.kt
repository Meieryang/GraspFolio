package io.graspfolio.app

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.LowLatencyCanvasView
import androidx.graphics.surface.SurfaceControlCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** UI-thread controller. Scene updates and draws are serialized on AndroidX's render thread. */
@RequiresApi(29)
internal class FrontInkLayer(context: Context) {
    val view = LowLatencyCanvasView(context)
    private val scene = FrontInkScene()
    private val enabled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val lastInput = AtomicLong(0)
    private val frames = AtomicLong(0)
    private val cpuNanos = AtomicLong(0)
    private val maxAge = AtomicLong(0)
    private val pendingIds = mutableSetOf<String>() // Only UI thread touches this set.
    private var active = false
    val hasPending get() = pendingIds.isNotEmpty()
    val ready get() = initialized.get() && !closed.get() && view.isAttachedToWindow
    val label get() = if (closed.get()) "前缓冲不可用，已回退" else if (ready) "前缓冲就绪" else "前缓冲初始化中"
    init {
        view.isClickable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.setRenderCallback(object : LowLatencyCanvasView.Callback {
            override fun onRedrawRequested(canvas: Canvas, width: Int, height: Int) {
                draw(canvas, width, height, true)
                if (!closed.get()) { initialized.set(true); Log.i("GraspFolioPen", "Front buffer surface ready ${width}x$height") }
            }
            override fun onDrawFrontBufferedLayer(canvas: Canvas, width: Int, height: Int) = draw(canvas, width, height, false)
            override fun onFrontBufferedLayerRenderComplete(frontBufferedLayerSurfaceControl: SurfaceControlCompat, transaction: SurfaceControlCompat.Transaction) {
                if (!enabled.get() || closed.get()) transaction.setVisibility(frontBufferedLayerSurfaceControl, false)
            }
        })
    }
    private fun draw(canvas: Canvas, width: Int, height: Int, full: Boolean) {
        try {
            val start = System.nanoTime()
            scene.draw(canvas, width, height, full)
            if (enabled.get()) {
                frames.incrementAndGet(); cpuNanos.addAndGet(System.nanoTime() - start)
                maxAge.accumulateAndGet((SystemClock.uptimeMillis() - lastInput.get()).coerceAtLeast(0), ::maxOf)
            }
        } catch (failure: Exception) {
            closed.set(true); enabled.set(false)
            Log.e("GraspFolioPen", "Front buffer draw failed; next stroke uses standard rendering", failure)
            view.post { view.cancel(); view.clear(); (view.parent as? View)?.invalidate() }
        }
    }
    fun begin(page: PagePlacement) {
        active = true; enabled.set(true); frames.set(0); cpuNanos.set(0); maxAge.set(0)
        view.execute { scene.begin(page) }
    }
    fun append(points: List<InkPoint>, prediction: InkPoint?, eventTime: Long) {
        lastInput.set(eventTime)
        view.execute { scene.append(points, prediction) }
        view.renderFrontBufferedLayer()
    }
    fun finish(id: String) {
        active = false; pendingIds += id
        view.execute { scene.finish(id) }
        view.renderFrontBufferedLayer(); view.commit()
    }
    /** Called after the parent has recorded the finished ink in this HWUI frame. */
    fun handoff(committedIds: Set<String>) {
        val delivered = pendingIds.intersect(committedIds)
        if (delivered.isEmpty()) return
        pendingIds.removeAll(delivered)
        view.execute { scene.handoff(delivered) }
        if (!active && pendingIds.isEmpty()) reset() else view.renderFrontBufferedLayer()
    }
    fun cancelActive() {
        if (!active) return
        active = false
        view.execute { scene.cancelActive() }
        if (pendingIds.isEmpty()) reset() else { view.renderFrontBufferedLayer(); view.commit() }
    }
    fun reset() {
        active = false; pendingIds.clear(); enabled.set(false)
        view.execute { scene.reset() }
        view.cancel(); view.clear()
    }
    fun close() { reset(); closed.set(true); initialized.set(false); view.setRenderCallback(null) }
    fun summary(): String = "前缓冲回调 ${frames.get()} 次；CPU均值 " +
        String.format(java.util.Locale.ROOT, "%.2f ms", cpuNanos.get() / maxOf(1, frames.get()) / 1_000_000.0) +
        "；采样至回调最大 ${maxAge.get()} ms（非屏幕实测延迟）"
}
