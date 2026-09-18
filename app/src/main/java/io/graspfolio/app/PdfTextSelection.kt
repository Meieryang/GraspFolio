package io.graspfolio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class SelectedPdfText(val page: Int, val text: String, val bounds: List<RectF>)

/** Finger-only selection; disabled mode never installs this handler. */
internal class PdfTextSelection(private val scope: CoroutineScope,
    private val select: suspend (Int, Point, Point) -> SelectedPdfText?,
    private val menu: (SelectedPdfText?) -> Unit,
    private val error: (String) -> Unit) {
    private var view: View? = null
    private var pages = emptyList<PagePlacement>()
    private var selected: SelectedPdfText? = null
    private var start: Point? = null
    private var placement: PagePlacement? = null
    private var x = 0f; private var y = 0f
    private var dragging = false
    private var eligible = false
    private var generation = 0
    private var job: Job? = null
    private val paint = Paint().apply { color = 0x55567896 }
    private val longPress = Runnable {
        if (eligible) { dragging = true; start?.let { request(it) } }
    }
    fun attach(host: View, placements: List<PagePlacement>) {
        view = host
        if (pages != placements) { clear(); pages = placements }
    }
    fun clear() {
        generation++; job?.cancel(); job = null
        view?.removeCallbacks(longPress); eligible = false; dragging = false; start = null; placement = null
        selected = null; menu(null); view?.invalidate()
    }
    private fun point(px: Float, py: Float, page: PagePlacement) = Point(((px - page.left) / page.scale).toInt(), ((py - page.top) / page.scale).toInt())
    private fun request(end: Point) {
        val page = placement ?: return
        val begin = start ?: return
        val token = ++generation
        job?.cancel()
        job = scope.launch {
            try {
                val result = select(page.page, begin, end)
                if (token == generation) { selected = result; menu(null); view?.invalidate() }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { if (token == generation) error(failure.message ?: "无法读取 PDF 文字") }
        }
    }
    fun touch(event: MotionEvent): Boolean {
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) { clear(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.x; y = event.y; dragging = false; eligible = true
                placement = pages.firstOrNull { x >= it.left && x <= it.left + it.width * it.scale && y >= it.top && y <= it.top + it.height * it.scale }
                start = placement?.let { point(x, y, it) }
                if (start != null) view?.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) placement?.let { request(point(event.x, event.y, it)) }
                else if (kotlin.math.hypot(event.x - x, event.y - y) > ViewConfiguration.get(view!!.context).scaledTouchSlop) {
                    eligible = false; view?.removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                view?.removeCallbacks(longPress)
                if (!dragging && eligible) {
                    val value = selected
                    val page = pages.firstOrNull { it.page == value?.page }
                    val p = page?.let { point(event.x, event.y, it) }
                    if (value != null && p != null && value.bounds.any { it.contains(p.x.toFloat(), p.y.toFloat()) }) menu(value)
                    else clear()
                }
                eligible = false; dragging = false
            }
        }
        return true
    }
    fun draw(canvas: Canvas) {
        val value = selected ?: return
        val page = pages.firstOrNull { it.page == value.page } ?: return
        canvas.save(); canvas.translate(page.left, page.top); canvas.scale(page.scale, page.scale)
        value.bounds.forEach { canvas.drawRect(it, paint) }; canvas.restore()
    }
}

internal fun copyPdfText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PDF 文字", text))
}
internal fun translatePdfText(context: Context, text: String): Boolean {
    val base = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    for (name in listOf("com.vivo.translator", "com.vivo.translator.base", "com.vivo.browser")) {
        val intent = Intent(base).setPackage(name)
        val info = context.packageManager.resolveActivity(intent, 0) ?: continue
        if (!info.activityInfo.exported) continue
        try { context.startActivity(intent); return true }
        catch (_: android.content.ActivityNotFoundException) { }
        catch (_: SecurityException) { }
    }
    return false
}
