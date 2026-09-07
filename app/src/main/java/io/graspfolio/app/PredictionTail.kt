package io.graspfolio.app

import kotlin.math.hypot

/** Screen-coordinate guard, never modifies or persists real samples. Defaults need device tuning. */
internal fun guardedPrediction(points: List<InkPoint>, predicted: InkPoint?, scale: Float, density: Float): InkPoint? {
    if (predicted == null || points.size < 2 || scale <= 0f || !predicted.x.isFinite() || !predicted.y.isFinite()) return null
    val last = points.last(); val previous = points[points.lastIndex - 1]
    val dt = last.time - previous.time
    if (dt !in 1L..40L) return null
    val dx = (last.x - previous.x) * scale; val dy = (last.y - previous.y) * scale
    val distance = hypot(dx, dy)
    if (distance < .05f * density || distance / dt < .03f * density) return null
    if (points.size >= 3) {
        val before = points[points.lastIndex - 2]
        val ax = (previous.x - before.x) * scale; val ay = (previous.y - before.y) * scale
        val length = hypot(ax, ay)
        if (length > .05f * density && (ax * dx + ay * dy) / (length * distance) < .75f) return null
    }
    val px = (predicted.x - last.x) * scale; val py = (predicted.y - last.y) * scale
    val forward = (px * dx + py * dy) / distance
    val lateral = kotlin.math.abs(px * dy - py * dx) / distance
    if (forward <= 0f || lateral > maxOf(.5f * density, forward * .35f)) return null
    // At most 6 ms of observed motion and 6 dp, taper to zero as the pen slows.
    val limit = minOf(6f * density, distance / dt * 6f)
    val length = hypot(px, py)
    if (length <= 0f) return null
    val ratio = minOf(1f, limit / length)
    return last.copy(x = last.x + px * ratio / scale, y = last.y + py * ratio / scale)
}
