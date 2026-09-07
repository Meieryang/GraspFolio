package io.graspfolio.app

import android.graphics.RenderNode
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class LongInkTraceTest {
    @Test fun longHeldStrokeReusesStableChunksWithoutDroppingSamples() {
        val scene = FrontInkScene()
        val output = RenderNode("LongInkTest").apply { setPosition(0, 0, 1200, 1800) }
        val page = PagePlacement(0, 0f, 0f, 1200f, 1800f, 1f)
        fun draw() {
            val canvas = output.beginRecording()
            try { assertTrue(canvas.isHardwareAccelerated); scene.draw(canvas, 1200, 1800) }
            finally { output.endRecording() }
        }
        try {
            scene.begin(page)
            val points = (0 until 12000).map { InkPoint((it % 1000).toFloat(), (it % 1500).toFloat(), .5f, it.toLong()) }
            scene.append(points, null); draw()
            assertEquals(12000, scene.recordedSegmentsLastDraw)
            repeat(256) { n ->
                scene.append(listOf(InkPoint(100f + n, 100f, .5f, 12000L + n)), null); draw()
                assertTrue("Stable history must not be recorded on every MOVE", scene.recordedSegmentsLastDraw <= 128)
            }
            // No UP: an extra frame must still replay all stable chunks and the live tail.
            draw(); assertTrue(scene.recordedSegmentsLastDraw < 128)
            scene.finish("long"); draw(); assertEquals(1, scene.pendingCount)
            scene.handoff(setOf("long")); draw(); assertEquals(0, scene.pendingCount)
            scene.begin(page); scene.append(points.take(256), null); draw()
            scene.cancelActive(); draw(); assertEquals(0, scene.recordedSegmentsLastDraw)
        } finally { scene.reset(); output.discardDisplayList() }
    }
}
