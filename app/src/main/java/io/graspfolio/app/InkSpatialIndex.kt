package io.graspfolio.app

import kotlin.math.floor

internal data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(other: InkBounds) = left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top
}

internal fun inkBounds(stroke: InkStroke): InkBounds {
    var left = Float.POSITIVE_INFINITY; var top = left
    var right = Float.NEGATIVE_INFINITY; var bottom = right
    for (p in stroke.points) { left = minOf(left, p.x); top = minOf(top, p.y); right = maxOf(right, p.x); bottom = maxOf(bottom, p.y) }
    val pad = stroke.width * .75f
    return InkBounds(left - pad, top - pad, right + pad, bottom + pad)
}

/** Page-coordinate grid. Bounds of unchanged immutable strokes are reused across edits. */
internal class InkSpatialIndex {
    private data class Entry(val stroke: InkStroke, val bounds: InkBounds, val cells: List<Long>?)
    private class Page {
        val cells = mutableMapOf<Long, MutableSet<String>>()
        val broad = mutableSetOf<String>()
        val all = mutableSetOf<String>()
    }
    private val pages = mutableMapOf<Int, Page>()
    private val entries = mutableMapOf<String, Entry>()
    private val order = mutableMapOf<String, Int>()
    private var source: List<InkStroke>? = null
    private fun cells(bounds: InkBounds): List<Long>? {
        val x0 = floor(bounds.left / 64).toInt(); val x1 = floor(bounds.right / 64).toInt()
        val y0 = floor(bounds.top / 64).toInt(); val y1 = floor(bounds.bottom / 64).toInt()
        val nx = x1.toLong() - x0 + 1; val ny = y1.toLong() - y0 + 1
        if (nx !in 1..4096 || ny !in 1..4096 || nx * ny > 4096) return null
        return buildList { for (x in x0..x1) for (y in y0..y1) add((x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)) }
    }
    fun sync(strokes: List<InkStroke>) {
        if (source === strokes) return
        val incoming = strokes.associateBy { it.id }
        val removed = entries.values.filter { incoming[it.stroke.id] !== it.stroke }
        for (entry in removed) {
            val id = entry.stroke.id; val page = pages.getValue(entry.stroke.page)
            page.all.remove(id); page.broad.remove(id)
            entry.cells?.forEach { cell -> page.cells[cell]?.let { it.remove(id); if (it.isEmpty()) page.cells.remove(cell) } }
            entries.remove(id)
            if (page.all.isEmpty()) pages.remove(entry.stroke.page)
        }
        order.clear()
        strokes.forEachIndexed { i, stroke ->
            order[stroke.id] = i
            if (stroke.id !in entries && stroke.points.isNotEmpty()) {
                val bounds = inkBounds(stroke); val keys = cells(bounds)
                entries[stroke.id] = Entry(stroke, bounds, keys)
                val page = pages.getOrPut(stroke.page) { Page() }
                page.all.add(stroke.id)
                if (keys == null) page.broad.add(stroke.id)
                else keys.forEach { page.cells.getOrPut(it) { mutableSetOf() }.add(stroke.id) }
            }
        }
        source = strokes
    }
    fun query(page: Int, bounds: InkBounds): List<InkStroke> {
        val grid = pages[page] ?: return emptyList()
        val keys = cells(bounds)
        val ids = if (keys == null) grid.all else mutableSetOf<String>().apply {
            addAll(grid.broad); keys.forEach { grid.cells[it]?.let(::addAll) }
        }
        return ids.asSequence().mapNotNull(entries::get).filter { it.bounds.intersects(bounds) }
            .sortedBy { order[it.stroke.id] }.map { it.stroke }.toList()
    }
    fun candidates(page: Int, a: InkPoint, b: InkPoint, radius: Float) = query(page,
        InkBounds(minOf(a.x, b.x) - radius, minOf(a.y, b.y) - radius, maxOf(a.x, b.x) + radius, maxOf(a.y, b.y) + radius))
}
