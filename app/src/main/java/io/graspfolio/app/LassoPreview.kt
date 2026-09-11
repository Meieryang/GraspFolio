package io.graspfolio.app

import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/** Full-resolution, tightly cropped layers, built once on the worker and translated during drag. */
internal class LassoPreview private constructor(
    private val background: Layer?, private val selected: Layer?,
    private val width: Int, private val height: Int
) {
    private class Layer(val raster: InkRasterCache, val bounds: Rect) {
        fun draw(canvas: Canvas, dx: Float, dy: Float) {
            canvas.save()
            canvas.translate(bounds.left + dx, bounds.top + dy)
            raster.show(canvas)
            canvas.restore()
        }
    }
    internal val pixelCount: Long get() = listOfNotNull(background, selected).sumOf {
        it.bounds.width().toLong() * it.bounds.height()
    }
    fun draw(canvas: Canvas, dx: Float, dy: Float) {
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        background?.draw(canvas, 0f, 0f)
        selected?.draw(canvas, dx, dy)
        canvas.restore()
    }
    fun release() { background?.raster?.release(); selected?.raster?.release() }
    companion object {
        fun build(width: Int, height: Int, placements: List<PagePlacement>, strokes: List<InkStroke>, ids: Set<String>): LassoPreview {
            val pages = placements.associateBy { it.page }
            val backgroundBounds = Rect(); val selectedBounds = Rect()
            for (stroke in strokes) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val page = pages[stroke.page] ?: continue
                if (stroke.points.isEmpty()) continue
                val b = inkBounds(stroke)
                // Integer crop origins preserve the original device-pixel grid and antialiasing.
                val left = maxOf(0, floor(maxOf(page.left, page.left + b.left * page.scale - 2)).toInt())
                val top = maxOf(0, floor(maxOf(page.top, page.top + b.top * page.scale - 2)).toInt())
                val right = minOf(width, ceil(minOf(page.left + page.width * page.scale, page.left + b.right * page.scale + 2)).toInt())
                val bottom = minOf(height, ceil(minOf(page.top + page.height * page.scale, page.top + b.bottom * page.scale + 2)).toInt())
                if (left < right && top < bottom)
                    (if (stroke.id in ids) selectedBounds else backgroundBounds).union(left, top, right, bottom)
            }
            fun layer(bounds: Rect): Layer? = if (bounds.isEmpty) null else Layer(InkRasterCache().apply {
                resize(bounds.width(), bounds.height())
            }, bounds)
            var background: Layer? = null; var selected: Layer? = null
            try {
                background = layer(backgroundBounds); selected = layer(selectedBounds)
                val backgroundPages = pages.mapValues { (_, p) -> p.copy(left = p.left - backgroundBounds.left, top = p.top - backgroundBounds.top) }
                val selectedPages = pages.mapValues { (_, p) -> p.copy(left = p.left - selectedBounds.left, top = p.top - selectedBounds.top) }
                for (stroke in strokes) {
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    val isSelected = stroke.id in ids
                    val page = (if (isSelected) selectedPages else backgroundPages)[stroke.page] ?: continue
                    (if (isSelected) selected else background)?.raster?.stroke(page, stroke)
                }
                return LassoPreview(background, selected, width, height)
            } catch (failure: Throwable) { background?.raster?.release(); selected?.raster?.release(); throw failure }
        }
    }
}
