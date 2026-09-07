package io.graspfolio.app

import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/** Repair only the screen region whose visible strokes changed; preserve overlap and draw order. */
internal class CommittedInkCache {
    private val raster = InkRasterCache()
    private var previous: List<InkStroke>? = null
    private var pages: List<PagePlacement> = emptyList()
    private var hidden: Set<String> = emptySet()
    var lastRepaintPixels = 0L; private set
    fun release() { raster.release(); previous = null; hidden = emptySet(); pages = emptyList() }
    fun update(width: Int, height: Int, placements: List<PagePlacement>, strokes: List<InkStroke>, erased: Set<String>, index: InkSpatialIndex) {
        val full = raster.resize(width, height) || previous == null || pages != placements
        if (!full && previous === strokes && hidden == erased) { lastRepaintPixels = 0; return }
        index.sync(strokes)
        val oldList = previous
        if (!full && oldList != null && hidden.isEmpty() && erased.isEmpty() && strokes.size >= oldList.size && oldList.indices.all { oldList[it] === strokes[it] }) {
            InkPerformance.measure("committed_cache_append") {
                for (i in oldList.size until strokes.size) {
                    val stroke = strokes[i]
                    placements.firstOrNull { it.page == stroke.page }?.let { raster.stroke(it, stroke) }
                }
            }
            previous = strokes; pages = placements; lastRepaintPixels = 0
            return
        }
        InkPerformance.measure("erase_cache_repair") {
            val region = Rect()
            fun include(stroke: InkStroke, destination: Rect = region) {
                val page = placements.firstOrNull { it.page == stroke.page } ?: return
                val b = inkBounds(stroke)
                destination.union(floor(page.left + b.left * page.scale - 2).toInt(), floor(page.top + b.top * page.scale - 2).toInt(),
                    ceil(page.left + b.right * page.scale + 2).toInt(), ceil(page.top + b.bottom * page.scale + 2).toInt())
            }
            if (full) region.set(0, 0, width, height)
            else {
                val old = previous!!.associateBy { it.id }; val current = strokes.associateBy { it.id }
                for ((id, stroke) in old) if (current[id] !== stroke || (id in hidden) != (id in erased)) include(stroke)
                for ((id, stroke) in current) if (old[id] !== stroke || (id in hidden) != (id in erased)) include(stroke)
            }
            lastRepaintPixels = 0
            if (region.intersect(0, 0, width, height) && !region.isEmpty) {
                lastRepaintPixels = region.width().toLong() * region.height()
                val candidates = mutableListOf<Pair<PagePlacement, InkStroke>>()
                val coverage = Rect(region)
                for (page in placements) {
                        val area = InkBounds((region.left - page.left - 2) / page.scale, (region.top - page.top - 2) / page.scale,
                            (region.right - page.left + 2) / page.scale, (region.bottom - page.top + 2) / page.scale)
                    for (stroke in index.query(page.page, area)) if (stroke.id !in erased) {
                        candidates += page to stroke
                        include(stroke, coverage)
                    }
                }
                coverage.intersect(0, 0, width, height)
                if (full) {
                    raster.clear()
                    candidates.forEach { (page, stroke) -> raster.stroke(page, stroke) }
                } else raster.repair(region, coverage) {
                    candidates.forEach { (page, stroke) -> raster.stroke(page, stroke) }
                }
            }
            previous = strokes; pages = placements; hidden = erased.toSet()
        }
    }
    fun show(canvas: Canvas) = raster.show(canvas)
}
