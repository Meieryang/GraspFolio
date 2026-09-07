package io.graspfolio.app

import kotlin.math.hypot

data class InkPoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
data class InkStroke(val id: String, val page: Int, val points: List<InkPoint>, val color: Int = 0xff111111.toInt(), val width: Float = 2f, val brush: String = "pressure")
data class PagePlacement(val page: Int, val left: Float, val top: Float, val width: Float, val height: Float, val scale: Float) {
    fun contains(x: Float, y: Float) = x >= left && y >= top && x < left + width * scale && y < top + height * scale
    fun toPage(x: Float, y: Float, pressure: Float, time: Long) = InkPoint((x - left) / scale, (y - top) / scale, pressure, time)
}
fun pressureWidth(width: Float, pressure: Float) = width * (.25f + 1.25f * pressure.coerceIn(0f, 1f))

internal fun clipToPage(a: InkPoint, b: InkPoint, width: Float, height: Float): Pair<InkPoint, InkPoint>? {
    var start = 0f; var end = 1f
    val dx = b.x - a.x; val dy = b.y - a.y
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x, width - a.x, a.y, height - a.y)
    for (i in 0..3) {
        if (p[i] == 0f) { if (q[i] < 0f) return null }
        else {
            val t = q[i] / p[i]
            if (p[i] < 0f) start = maxOf(start, t) else end = minOf(end, t)
            if (start > end) return null
        }
    }
    return a.copy(x = a.x + start * dx, y = a.y + start * dy) to b.copy(x = a.x + end * dx, y = a.y + end * dy)
}

private fun distance(p: InkPoint, a: InkPoint, b: InkPoint): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val length = dx * dx + dy * dy
    val t = if (length == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}
fun strokeHit(stroke: InkStroke, a: InkPoint, b: InkPoint, radius: Float): Boolean {
    val reach = radius + stroke.width * .75f
    if (stroke.points.any { distance(it, a, b) <= reach }) return true
    for (i in 1 until stroke.points.size) {
        val c = stroke.points[i - 1]; val d = stroke.points[i]
        val dx = b.x - a.x; val dy = b.y - a.y
        val ex = d.x - c.x; val ey = d.y - c.y
        val cross = dx * ey - dy * ex
        val intersects = if (cross == 0f) false else {
            val t = ((c.x - a.x) * ey - (c.y - a.y) * ex) / cross
            val u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / cross
            t in 0f..1f && u in 0f..1f
        }
        if (intersects || distance(a, c, d) <= reach || distance(b, c, d) <= reach) return true
    }
    return false
}
