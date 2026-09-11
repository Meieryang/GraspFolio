package io.graspfolio.app

/** Selection is transient; moving commits ordinary strokes with their original IDs. */
internal class LassoSelection {
    var selected = emptySet<String>(); private set
    var page = -1; private set
    val path = mutableListOf<InkPoint>()
    var dragging = false; private set
    private var origin: InkPoint? = null
    var dx = 0f; private set
    var dy = 0f; private set
    private var boundsSource: List<InkStroke>? = null
    private var cachedBounds: FloatArray? = null
    fun clear() { accept(emptySet()); page = -1; cancel() }
    fun cancel() { path.clear(); origin = null; dragging = false; dx = 0f; dy = 0f }
    fun bounds(strokes: List<InkStroke>): FloatArray? {
        if (boundsSource !== strokes) {
            var left = Float.POSITIVE_INFINITY; var top = left
            var right = Float.NEGATIVE_INFINITY; var bottom = right
            for (stroke in strokes) if (stroke.page == page && stroke.id in selected) for (p in stroke.points) {
                left = minOf(left, p.x); top = minOf(top, p.y); right = maxOf(right, p.x); bottom = maxOf(bottom, p.y)
            }
            cachedBounds = if (left.isFinite()) floatArrayOf(left, top, right, bottom) else null
            boundsSource = strokes
        }
        return cachedBounds?.let { floatArrayOf(it[0] + dx, it[1] + dy, it[2] + dx, it[3] + dy) }
    }
    fun accept(ids: Set<String>, selectedPage: Int = page) { selected = ids; page = selectedPage; boundsSource = null }

    fun down(point: InkPoint, placement: PagePlacement, strokes: List<InkStroke>) {
        cancel()
        val b = bounds(strokes)
        val margin = 12f / placement.scale
        dragging = page == placement.page && b != null && point.x >= b[0] - margin && point.x <= b[2] + margin &&
            point.y >= b[1] - margin && point.y <= b[3] + margin
        if (!dragging) { accept(emptySet()); page = placement.page; path += point }
        origin = point
    }
    fun move(point: InkPoint, placement: PagePlacement, strokes: List<InkStroke>) {
        if (!point.x.isFinite() || !point.y.isFinite()) return
        if (!dragging) {
            val last = path.lastOrNull()
            val spacing = 2f / placement.scale
            if (last == null || kotlin.math.hypot(point.x - last.x, point.y - last.y) >= spacing) {
                path += point
                if (path.size > 512) {
                    val reduced = path.filterIndexed { index, _ -> index % 2 == 0 || index == path.lastIndex }
                    path.clear(); path.addAll(reduced)
                }
            }
            return
        }
        val start = origin ?: return
        dx = 0f; dy = 0f
        val b = bounds(strokes) ?: return
        dx = (point.x - start.x).coerceIn(minOf(0f, -b[0]), maxOf(0f, placement.width - b[2]))
        dy = (point.y - start.y).coerceIn(minOf(0f, -b[1]), maxOf(0f, placement.height - b[3]))
    }
    fun preview(strokes: List<InkStroke>): List<InkStroke> = if (dx == 0f && dy == 0f) strokes else strokes.map {
        if (it.page == page && it.id in selected) it.copy(points = it.points.map { p -> p.copy(x = p.x + dx, y = p.y + dy) }) else it
    }
    fun up(strokes: List<InkStroke>): List<InkStroke>? {
        val moved = if (dragging && (dx != 0f || dy != 0f)) preview(strokes) else null
        if (!dragging) accept(select(strokes, page, path))
        cancel()
        return moved
    }
    companion object {
        fun select(strokes: List<InkStroke>, page: Int, polygon: List<InkPoint>): Set<String> {
            if (polygon.size < 3) return emptySet()
            var area = 0f
            for (i in polygon.indices) {
                val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
                area += a.x * b.y - b.x * a.y
            }
            if (kotlin.math.abs(area) < 4f) return emptySet()
            fun inside(p: InkPoint): Boolean {
                var result = false
                for (i in polygon.indices) {
                    val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
                    if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) result = !result
                }
                return result
            }
            val polygonBounds = InkBounds(polygon.minOf { it.x }, polygon.minOf { it.y }, polygon.maxOf { it.x }, polygon.maxOf { it.y })
            val edges = polygon.indices.map { i -> polygon[i] to polygon[(i + 1) % polygon.size] }
            val edgeBounds = edges.map { (a, b) -> InkBounds(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)) }
            val result = mutableSetOf<String>()
            for (stroke in strokes) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                if (stroke.page != page || stroke.points.isEmpty()) continue
                val bounds = inkBounds(stroke)
                if (!bounds.intersects(polygonBounds)) continue
                // Reject most strokes/edges by bounds before examining their sampled segments.
                val relevant = edges.indices.filter { bounds.intersects(edgeBounds[it]) }
                if (relevant.isEmpty()) {
                    if (inside(stroke.points.first())) result += stroke.id
                    continue
                }
                if (inside(stroke.points.first()) || relevant.any { i ->
                    val (a, b) = edges[i]; strokeHit(stroke, a, b, 0f)
                }) result += stroke.id
            }
            return result
        }
    }
}
