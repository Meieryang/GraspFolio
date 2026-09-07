package io.graspfolio.app

import android.graphics.Canvas
import android.graphics.RenderNode
import androidx.annotation.RequiresApi
import kotlin.math.ceil

/** Render-thread-owned immutable display-list chunks; the mutable tail is at most 127 samples. */
@RequiresApi(29)
internal class RecordedInkTrace {
    private val chunks = mutableListOf<RenderNode>()
    var recordedThisDraw = 0; private set
    fun draw(canvas: Canvas, page: PagePlacement, points: List<InkPoint>, segment: (Canvas, InkPoint?, InkPoint) -> Unit) {
        recordedThisDraw = 0
        val fullChunks = points.size / 128
        while (chunks.size < fullChunks) {
            val first = chunks.size * 128
            val node = RenderNode("InkChunk").apply {
                setPosition(0, 0, ceil(page.width).toInt().coerceAtLeast(1), ceil(page.height).toInt().coerceAtLeast(1))
                clipToBounds = false
            }
            val recording = node.beginRecording()
            try {
                for (i in first until first + 128) segment(recording, if (i == 0) null else points[i - 1], points[i])
            } finally { node.endRecording() }
            recordedThisDraw += 128
            chunks += node
        }
        for (node in chunks) canvas.drawRenderNode(node)
        for (i in fullChunks * 128 until points.size) {
            segment(canvas, if (i == 0) null else points[i - 1], points[i]); recordedThisDraw++
        }
    }
    fun release() { chunks.forEach { it.discardDisplayList() }; chunks.clear() }
}
