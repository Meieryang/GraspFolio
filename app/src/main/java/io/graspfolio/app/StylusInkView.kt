package io.graspfolio.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vivo.penengine.impl.VivoAlgorithmManagerImpl
import com.vivo.penengine.impl.VivoStylusGestureManagerImpl
import com.vivo.penengine.impl.VivoStylusManagerImpl
import java.util.UUID

internal class VivoPenAdapter(private val activity: Activity, private val toggle: () -> Boolean) {
    var predictionStatus = "预测服务尚未初始化"; private set
    private fun status(value: String) { if (predictionStatus != value) { predictionStatus = value; Log.i("GraspFolioPen", value) } }
    private var algorithm: VivoAlgorithmManagerImpl? = null
    private var stylus: VivoStylusManagerImpl? = null
    private var gestures: VivoStylusGestureManagerImpl? = null
    private val callback = VivoStylusGestureManagerImpl.OnGestureCallback { type -> type == 2 && toggle() }
    fun start() {
        stop()
        try { algorithm = VivoAlgorithmManagerImpl(activity); status("等待 vivo 预测服务") }
        catch (e: Exception) { status("预测初始化失败：${e.javaClass.simpleName}") }
        catch (e: LinkageError) { status("预测库不可用：${e.javaClass.simpleName}") }
        try {
            stylus = VivoStylusManagerImpl.getInstance(activity).also { it.init() }
            gestures = VivoStylusGestureManagerImpl.getInstance(activity).also { it.registerLifecycle(activity); it.registerGestureCallback(callback) }
        } catch (e: Exception) { Log.w("GraspFolioPen", "笔手势/振动初始化失败", e) }
        catch (e: LinkageError) { Log.w("GraspFolioPen", "笔手势/振动库不可用", e) }
    }
    fun predict(event: MotionEvent): PointF? = try {
        val engine = algorithm
        if (engine != null && engine.isEstimateEnable) {
            status("vivo 预测服务已启用")
            engine.computeEstimatePoint(event)?.takeIf { it.x.isFinite() && it.y.isFinite() }
        } else { if (engine != null) status("vivo 预测服务尚不可用，使用真实采样"); null }
    } catch (e: Exception) { status("预测调用失败：${e.javaClass.simpleName}"); null }
      catch (e: LinkageError) { status("预测调用失败：${e.javaClass.simpleName}"); null }
    fun vibrate(enabled: Boolean) { try { stylus?.enableWritingVibrate(enabled) } catch (_: Exception) { } catch (_: LinkageError) { } }
    fun stop() {
        vibrate(false)
        try { gestures?.unregisterGestureCallback(callback); gestures?.unregisterLifecycle(activity) } catch (_: Exception) { } catch (_: LinkageError) { }
        try { stylus?.destroy() } catch (_: Exception) { } catch (_: LinkageError) { }
        try { algorithm?.release() } catch (_: Exception) { } catch (_: LinkageError) { }
        gestures = null; stylus = null; algorithm = null
    }
}

internal class StylusInkView(context: Context) : FrameLayout(context), DefaultLifecycleObserver {
    init { setWillNotDraw(false) }
    private var front: FrontInkLayer? = null
    internal val lowLatencyReady get() = Build.VERSION.SDK_INT >= 29 && front?.ready == true
    private var frontStroke = false
    private var frontSentCount = 0
    var frontBufferEnabled = true
        set(value) { if (field != value) { cancelStroke(); resetFront(); field = value; report() } }
    private fun resetFront() { if (Build.VERSION.SDK_INT >= 29) front?.reset() }
    var placements: List<PagePlacement> = emptyList()
        set(value) { if (field != value) { cancelStroke(); clearLassoPreview(); selection.clear(); resetFront(); field = value; invalidate() } }
    var strokes: List<InkStroke> = emptyList()
        set(value) { if (field !== value) { stopLassoWork(); clearLassoPreview(); selection.clear(); field = value; invalidate() } }
    private val selection = LassoSelection()
    private var lassoPreview: LassoPreview? = null
    private var lassoWork: java.util.concurrent.Future<*>? = null
    private var lassoGeneration = 0
    internal var lassoBusy = false; private set
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun clearLassoPreview() { lassoPreview?.release(); lassoPreview = null }
    private fun stopLassoWork() { lassoGeneration++; lassoWork?.cancel(true); lassoWork = null; lassoBusy = false }
    companion object {
        private val lassoWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "lasso-worker").apply { isDaemon = true }
        }
    }
    var lasso = false
        set(value) { if (field != value) { cancelStroke(); clearLassoPreview(); selection.clear(); field = value; invalidate() } }
    var eraser = false
    var brushStyle = BrushStyle()
    private var activeStyle = BrushStyle()
    var writingVibration = true
    var predictionMode = PredictionMode.STABLE
    private var activePredictionMode = PredictionMode.STABLE
    private val predictionDiagnostics = PredictionDiagnostics()
    var predictionEnabled = true
        set(value) { if (field != value) { cancelStroke(); field = value; report() } }
    var enabledForWriting = false
        set(value) { if (field && !value) { cancelStroke(); resetFront() }; field = value }
    var onChange: (List<InkStroke>) -> Unit = {}
    var onContact: (Boolean) -> Unit = {}
    var onPageContact: () -> Unit = {}
    var onToggle: () -> Unit = {}
    var onDiagnostics: (String) -> Unit = {}
    private val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first()
    private val owner = activity as LifecycleOwner
    private var foreground = false
    private val doubleTapSwitch = (activity as? MainActivity)?.hoverSwitch ?: DoubleTapSwitch().apply { enabled = false }
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (activity !is MainActivity) doubleTapSwitch.observeStylus(event)
        return super.dispatchHoverEvent(event)
    }
    private val sdk = VivoPenAdapter(activity) {
        doubleTapSwitch.dispatch(
            canToggle = { foreground && enabledForWriting && active == null },
            enqueue = { action -> post { action() } },
            toggle = { onToggle() }
        )
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var active: PagePlacement? = null
    private var pointer = -1
    private var erasing = false
    private val points = mutableListOf<InkPoint>()
    private var predicted: InkPoint? = null
    private val erased = mutableSetOf<String>()
    private val normalizer = PenEventNormalizer()
    private var penDownTime = 0L
    private var committed = CommittedInkCache()
    private val spatial = InkSpatialIndex()
    private val live = InkRasterCache()
    private var liveCount = 0
    private var latestEventTime = 0L
    private var drawCount = 0
    private var drawNanos = 0L
    private var maxEventAge = 0L
    private var predictionAttempts = 0
    private var acceptedPredictions = 0
    private var lastSummary = "尚无书写数据"
    private fun report() {
        val mode = if (!frontBufferEnabled) "前缓冲已关闭" else if (Build.VERSION.SDK_INT >= 29) front?.label ?: "使用常规绘制" else "当前系统使用常规绘制"
        val text = (if (predictionEnabled) sdk.predictionStatus else "预测已关闭，使用真实采样") + "\n" + mode + "\n" + lastSummary
        Log.i("GraspFolioPen", text.replace('\n', ' '))
        onDiagnostics(text)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated && front == null) {
            try {
                front = FrontInkLayer(context)
                addView(front!!.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } catch (e: Exception) { Log.w("GraspFolioPen", "Front buffer unavailable", e); front = null }
              catch (e: LinkageError) { Log.w("GraspFolioPen", "Front buffer unavailable", e); front = null }
        }
        owner.lifecycle.addObserver(this)
    }
    override fun onDetachedFromWindow() {
        doubleTapSwitch.enabled = false
        cancelStroke(); clearLassoPreview()
        if (Build.VERSION.SDK_INT >= 29) front?.let { it.close(); removeView(it.view) }
        front = null
        sdk.stop(); committed.release(); live.release(); owner.lifecycle.removeObserver(this); super.onDetachedFromWindow()
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onResume(owner: LifecycleOwner) { foreground = true; sdk.start() }
    override fun onPause(owner: LifecycleOwner) { foreground = false; doubleTapSwitch.enabled = false; cancelStroke(); resetFront(); sdk.stop() }
    fun cancelStroke() {
        stopLassoWork()
        if (Build.VERSION.SDK_INT >= 29) front?.cancelActive()
        frontStroke = false; frontSentCount = 0
        selection.cancel()
        if (pointer != -1 && predictionEnabled) {
            val cancel = MotionEvent.obtain(penDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { sdk.predict(cancel) } finally { cancel.recycle() }
        }
        active = null; pointer = -1; points.clear(); erased.clear(); predicted = null
        live.clear(); liveCount = 0
        sdk.vibrate(false); onContact(false); invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        doubleTapSwitch.observeStylus(event)
        val actionIndex = event.actionIndex
        if ((event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
            placements.any { it.contains(event.getX(actionIndex), event.getY(actionIndex)) }) onPageContact()
        val pen = event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancelStroke(); return true }
        if ((event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) && pen) {
            if (lasso && lassoBusy) return true
            cancelStroke()
            if (!enabledForWriting) return true
            val placement = placements.firstOrNull { it.contains(event.getX(actionIndex), event.getY(actionIndex)) } ?: return true
            active = placement; pointer = event.getPointerId(actionIndex); penDownTime = event.eventTime
            if (lasso) {
                selection.down(placement.toPage(event.getX(actionIndex), event.getY(actionIndex), 1f, event.eventTime), placement, strokes)
                if (!selection.dragging) clearLassoPreview()
                onContact(true); invalidate(); return true
            }
            activeStyle = brushStyle
            activePredictionMode = predictionMode
            predictionDiagnostics.reset()
            drawCount = 0; drawNanos = 0; maxEventAge = 0; predictionAttempts = 0; acceptedPredictions = 0
            erasing = eraser || event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER || event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
            if (erasing) InkPerformance.measure("erase_index") { spatial.sync(strokes) }
            // Translucent ink uses the single-composition cache path: overlapping pending
            // front and committed surfaces would briefly double its opacity on handoff.
            frontStroke = Build.VERSION.SDK_INT >= 29 && frontBufferEnabled && !erasing && activeStyle.alpha == 255 && front?.ready == true
            if (Build.VERSION.SDK_INT >= 29 && frontStroke) { live.release(); front?.begin(placement, activeStyle) }
            onContact(true); sdk.vibrate(writingVibration && !erasing)
        }
        // Accept the finger's initial DOWN too: a pen may join this same native event stream.
        // The Compose ancestor observes touch independently (requireUnconsumed = false).
        val placement = active ?: return true
        val index = event.findPointerIndex(pointer)
        if (index < 0) { cancelStroke(); return true }
        // A palm's pointer-down/up must not become a pen sample or reset SDK history.
        if ((event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP) && index != actionIndex) return true
        if (lasso) {
            selection.move(placement.toPage(event.getX(index), event.getY(index), 1f, event.eventTime), placement, strokes)
            val up = (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) && index == actionIndex
            if (up) finishLasso()
            invalidate(); return true
        }
        requestUnbufferedDispatch(event)
        fun sample(x: Float, y: Float, pressure: Float, time: Long) {
            if (!x.isFinite() || !y.isFinite()) return
            val p = placement.toPage(x, y, if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else .5f, time)
            val previousPoint = points.lastOrNull()
            if (previousPoint != null && (p.time < previousPoint.time || p == previousPoint)) return
            if (erasing) {
                val previous = points.lastOrNull() ?: p
                // A stroke may leave the page, but cannot erase anything on another page.
                clipToPage(previous, p, placement.width, placement.height)?.let { (from, to) ->
                    val radius = 12f * resources.displayMetrics.density / placement.scale
                    InkPerformance.measure("erase_hit") {
                    for (stroke in spatial.candidates(placement.page, from, to, radius)) if (stroke.id !in erased && strokeHit(stroke, from, to, radius)) {
                        erased += stroke.id
                    }
                    }
                }
            }
            points += p
        }
        for (h in 0 until event.historySize) sample(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalPressure(index, h), event.getHistoricalEventTime(h))
        sample(event.getX(index), event.getY(index), event.getPressure(index), event.eventTime)
        val up = (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) && event.getPointerId(actionIndex) == pointer
        latestEventTime = event.eventTime
        val sdkAction = if (up) MotionEvent.ACTION_UP else if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
        val rawPrediction = if (predictionEnabled && !erasing) {
            val normalized = normalizer.obtain(event, index, penDownTime, sdkAction)
            try { sdk.predict(normalized) } finally { normalized.recycle() }
        } else null
        predicted = if (sdkAction == MotionEvent.ACTION_MOVE && !erasing && predictionEnabled) {
            predictionAttempts++
            evaluatePrediction(points, rawPrediction?.let { placement.toPage(it.x, it.y, event.getPressure(index), event.eventTime) }, placement.scale, resources.displayMetrics.density, activePredictionMode)
                .also { predictionDiagnostics.record(rawPrediction, it) }.point
                .also { if (it != null) acceptedPredictions++ }
        } else null
        if (Build.VERSION.SDK_INT >= 29 && frontStroke) {
            if (front?.ready == true) {
                front?.append(points.subList(frontSentCount, points.size).toList(), predicted, event.eventTime)
                frontSentCount = points.size
            } else { frontStroke = false; front?.cancelActive() }
        }
        if (up) {
            if (erasing) { if (erased.isNotEmpty()) commit(strokes.filterNot { it.id in erased }) }
            else if (points.isNotEmpty()) {
                val stroke = InkStroke(UUID.randomUUID().toString(), placement.page, points.toList(), activeStyle.color, activeStyle.width, activeStyle.brush)
                if (Build.VERSION.SDK_INT >= 29 && frontStroke) front?.finish(stroke.id)
                commit(strokes + stroke)
            }
            lastSummary = "预测尾迹 $acceptedPredictions/$predictionAttempts；绘制CPU均值 " +
                String.format(java.util.Locale.ROOT, "%.2f ms", if (drawCount == 0) 0.0 else drawNanos / drawCount / 1_000_000.0) + "；采样至绘制最大 ${maxEventAge} ms（非屏幕实测延迟）"
            if (Build.VERSION.SDK_INT >= 29 && frontStroke) lastSummary = "预测尾迹 $acceptedPredictions/$predictionAttempts；" + front?.summary()
            lastSummary += "\n" + predictionDiagnostics.summary(activePredictionMode)
            if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) Log.d("GraspFolioPen", lastSummary)
            pointer = -1 // UP already reset SDK state; do not send an extra CANCEL.
            cancelStroke(); report()
        }
        // Live front-buffer moves do not change the parent's committed bitmap.
        if (!frontStroke || up) invalidate()
        return true
    }
    private fun commit(value: List<InkStroke>) {
        strokes = value // A second stroke may begin before Compose's next frame.
        onChange(value)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val begin = System.nanoTime()
        val preview = lassoPreview
        if (lasso && selection.dragging && preview != null) {
            val page = placements.firstOrNull { it.page == selection.page }
            preview.draw(canvas, selection.dx * (page?.scale ?: 1f), selection.dy * (page?.scale ?: 1f))
        } else {
            committed.update(width, height, placements, strokes, erased, spatial)
            committed.show(canvas)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val layer = front
            if (layer?.hasPending == true && isHardwareAccelerated) {
                val ids = strokes.mapTo(mutableSetOf()) { it.id }
                val generation = layer.generation
                viewTreeObserver.registerFrameCommitCallback { post { if (front === layer) layer.handoff(ids, generation) } }
            }
            if (frontStroke && layer?.ready != true) frontStroke = false
        }
        active?.takeIf { !lasso && !erasing && !frontStroke }?.let { p ->
            if (live.resize(width, height)) liveCount = 0
            for (i in liveCount until points.size) live.segment(p, if (i == 0) null else points[i - 1], points[i], activeStyle.opaqueColor, activeStyle.width, activeStyle.brush)
            liveCount = points.size
            val opacityLayer = if (activeStyle.alpha < 255) canvas.saveLayerAlpha(p.left, p.top,
                p.left + p.width * p.scale, p.top + p.height * p.scale, activeStyle.alpha) else canvas.save()
            live.show(canvas)
            canvas.save(); canvas.clipRect(p.left, p.top, p.left + p.width * p.scale, p.top + p.height * p.scale)
            canvas.translate(p.left, p.top); canvas.scale(p.scale, p.scale)
            predicted?.let { tail -> points.lastOrNull()?.let { last ->
                paint.color = activeStyle.opaqueColor; paint.strokeWidth = activeStyle.widthAt((last.pressure + tail.pressure) / 2)
                canvas.drawLine(last.x, last.y, tail.x, tail.y, paint)
            } }
            canvas.restore()
            canvas.restoreToCount(opacityLayer)
        }
        if (lasso) drawSelection(canvas)
        if (!lasso && erasing) active?.let { p -> points.lastOrNull()?.let { point ->
            val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22567896; style = Paint.Style.FILL }
            val x = p.left + point.x * p.scale; val y = p.top + point.y * p.scale
            val radius = 12f * resources.displayMetrics.density
            canvas.drawCircle(x, y, radius, marker)
            marker.color = 0xff567896.toInt(); marker.style = Paint.Style.STROKE; marker.strokeWidth = resources.displayMetrics.density
            canvas.drawCircle(x, y, radius, marker)
        } }
        if (active != null) {
            drawCount++; drawNanos += System.nanoTime() - begin
            maxEventAge = maxOf(maxEventAge, (SystemClock.uptimeMillis() - latestEventTime).coerceAtLeast(0))
        }
    }
    private fun finishLasso() {
        pointer = -1; active = null; onContact(false)
        val source = strokes; val pages = placements
        val page = selection.page; val ids = selection.selected
        val dx = selection.dx; val dy = selection.dy
        val moving = selection.dragging
        if (moving && dx == 0f && dy == 0f) { selection.cancel(); invalidate(); return }
        val outline = selection.path.toList()
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        stopLassoWork()
        val generation = lassoGeneration
        lassoBusy = true
        lassoWork = lassoWorker.submit {
            var preview: LassoPreview? = null
            var prepared: CommittedInkCache? = null
            try {
                val selected = if (moving) ids else LassoSelection.select(source, page, outline)
                val moved = if (moving) source.map { stroke ->
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    if (stroke.page == page && stroke.id in selected) stroke.copy(points = stroke.points.map { it.copy(x = it.x + dx, y = it.y + dy) }) else stroke
                } else source
                if (moving) {
                    prepared = CommittedInkCache()
                    prepared.update(w, h, pages, moved, emptySet(), InkSpatialIndex())
                }
                if (selected.isNotEmpty()) preview = LassoPreview.build(w, h, pages, moved, selected)
                val resultPreview = preview; val resultCache = prepared
                mainHandler.post {
                    if (generation == lassoGeneration && lasso && strokes === source && placements == pages &&
                        width.coerceAtLeast(1) == w && height.coerceAtLeast(1) == h) {
                        lassoWork = null; lassoBusy = false
                        if (moving) {
                            commit(moved)
                            committed.release(); committed = checkNotNull(resultCache)
                        }
                        clearLassoPreview(); lassoPreview = resultPreview
                        selection.cancel(); selection.accept(selected, page)
                        selection.bounds(strokes) // Cache once, not at every MOVE or frame.
                        invalidate()
                    } else {
                        resultPreview?.release(); resultCache?.release()
                        if (generation == lassoGeneration) { stopLassoWork(); selection.cancel(); invalidate() }
                    }
                }
            } catch (failure: Throwable) {
                preview?.release(); prepared?.release()
                if (failure !is java.util.concurrent.CancellationException && failure !is InterruptedException)
                    Log.w("GraspFolioPen", "套索计算失败，保留原笔迹", failure)
                mainHandler.post {
                    if (generation == lassoGeneration) { stopLassoWork(); selection.cancel(); invalidate() }
                }
            }
        }
        invalidate()
    }
    private fun drawSelection(canvas: Canvas) {
        val p = placements.firstOrNull { it.page == selection.page } ?: return
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff567896.toInt(); style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density / p.scale
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f / p.scale, 5f / p.scale), 0f)
        }
        canvas.save()
        canvas.clipRect(p.left, p.top, p.left + p.width * p.scale, p.top + p.height * p.scale)
        canvas.translate(p.left, p.top); canvas.scale(p.scale, p.scale)
        if (selection.path.isNotEmpty()) {
            val outline = android.graphics.Path()
            selection.path.forEachIndexed { index, point -> if (index == 0) outline.moveTo(point.x, point.y) else outline.lineTo(point.x, point.y) }
            outline.close(); canvas.drawPath(outline, marker)
        }
        if (lassoBusy) {
            marker.pathEffect = null; marker.style = Paint.Style.FILL
            marker.textSize = 12f * resources.displayMetrics.density / p.scale
            val anchor = selection.path.lastOrNull()
            canvas.drawText(if (selection.dragging) "正在保存位置…" else "正在圈选…",
                anchor?.x ?: 12f, (anchor?.y ?: 24f) + marker.textSize, marker)
            marker.style = Paint.Style.STROKE
        }
        selection.bounds(strokes)?.let { b ->
            val margin = 6f / p.scale
            canvas.drawRoundRect(b[0] - margin, b[1] - margin, b[2] + margin, b[3] + margin, margin, margin, marker)
        }
        canvas.restore()
    }

}
