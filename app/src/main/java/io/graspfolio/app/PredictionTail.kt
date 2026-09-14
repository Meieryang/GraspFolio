package io.graspfolio.app

import kotlin.math.hypot

internal enum class PredictionMode(val key: String, val label: String, val horizonMs: Float, val maxDp: Float, val gain: Float) {
    STABLE("stable", "稳定跟手", 12f, 12f, 1f), STRONG("strong", "更强预测", 18f, 18f, 1.5f);
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: STABLE }
}

internal enum class PredictionRejection(val label: String) {
    UNAVAILABLE("无有效返回"), SAMPLES("采样不足"), STALE("采样时间异常"), SLOW("慢速或停笔"),
    TURN("急转弯"), REVERSE("反向"), LATERAL("横向跳点")
}

/** Display-only result; distances are screen dp, independent of PDF scale. */
internal data class PredictionResult(val point: InkPoint? = null, val reason: PredictionRejection? = null, val lengthDp: Float = 0f)

internal fun evaluatePrediction(points: List<InkPoint>, predicted: InkPoint?, scale: Float, density: Float,
    mode: PredictionMode): PredictionResult {
    fun reject(reason: PredictionRejection) = PredictionResult(reason = reason)
    if (predicted == null || !predicted.x.isFinite() || !predicted.y.isFinite()) return reject(PredictionRejection.UNAVAILABLE)
    if (points.size < 2 || !scale.isFinite() || scale <= 0f || !density.isFinite() || density <= 0f) return reject(PredictionRejection.SAMPLES)
    val last = points.last(); val previous = points[points.lastIndex - 1]
    val dt = last.time - previous.time
    if (dt !in 1L..40L) return reject(PredictionRejection.STALE)
    val dx = (last.x - previous.x) * scale; val dy = (last.y - previous.y) * scale
    val distance = hypot(dx, dy)
    if (!distance.isFinite()) return reject(PredictionRejection.SAMPLES)
    val speed = distance / dt
    if (distance < .05f * density || speed < .03f * density) return reject(PredictionRejection.SLOW)
    var taper = 1f
    if (points.size >= 3) {
        val before = points[points.lastIndex - 2]
        val ax = (previous.x - before.x) * scale; val ay = (previous.y - before.y) * scale
        val length = hypot(ax, ay)
        if (length > .05f * density) {
            val cosine = (ax * dx + ay * dy) / (length * distance)
            if (!cosine.isFinite() || cosine < .75f) return reject(PredictionRejection.TURN)
            taper = ((cosine - .75f) / .25f).coerceIn(0f, 1f)
            val previousDt = previous.time - before.time
            if (previousDt in 1L..40L) taper *= (speed / (length / previousDt)).coerceIn(0f, 1f)
        }
    }
    val px = (predicted.x - last.x) * scale; val py = (predicted.y - last.y) * scale
    val forward = (px * dx + py * dy) / distance
    val lateral = kotlin.math.abs(px * dy - py * dx) / distance
    if (!forward.isFinite() || !lateral.isFinite()) return reject(PredictionRejection.UNAVAILABLE)
    if (forward <= 0f) return reject(PredictionRejection.REVERSE)
    if (lateral > maxOf(.5f * density, forward * .35f)) return reject(PredictionRejection.LATERAL)
    val length = hypot(px, py)
    val limit = minOf(mode.maxDp * density, speed * mode.horizonMs) * taper
    // Amplify only a forward SDK result that has passed all guards; never synthesize a
    // direction when the SDK is unavailable or predicts behind the real pen position.
    val ratio = minOf(mode.gain, limit / length)
    return PredictionResult(last.copy(x = last.x + px * ratio / scale, y = last.y + py * ratio / scale), lengthDp = length * ratio / density)
}

internal fun guardedPrediction(points: List<InkPoint>, predicted: InkPoint?, scale: Float, density: Float,
    mode: PredictionMode = PredictionMode.STABLE): InkPoint? = evaluatePrediction(points, predicted, scale, density, mode).point

internal class PredictionDiagnostics {
    private var attempts = 0
    private var sdkValid = 0
    private var accepted = 0
    private var totalLength = 0f
    private var maxLength = 0f
    private val rejected = mutableMapOf<PredictionRejection, Int>()
    fun reset() { attempts = 0; sdkValid = 0; accepted = 0; totalLength = 0f; maxLength = 0f; rejected.clear() }
    fun record(raw: android.graphics.PointF?, result: PredictionResult) {
        attempts++
        if (raw != null) sdkValid++
        if (result.point != null) { accepted++; totalLength += result.lengthDp; maxLength = maxOf(maxLength, result.lengthDp) }
        result.reason?.let { rejected[it] = rejected.getOrDefault(it, 0) + 1 }
    }
    fun summary(mode: PredictionMode) = "${mode.label}：SDK 有效 $sdkValid/$attempts；预测采用 $accepted/$attempts；" +
        String.format(java.util.Locale.ROOT, "尾迹均值 %.1f / 最大 %.1f dp", totalLength / maxOf(1, accepted), maxLength) +
        if (rejected.isEmpty()) "" else "\n过滤：" + rejected.entries.joinToString("、") { "${it.key.label} ${it.value}" }
}
