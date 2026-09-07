package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class PenPipelineTest {
    private fun mixed(action: Int, time: Long, penX: Float = 60f): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 3; toolType = MotionEvent.TOOL_TYPE_FINGER }, MotionEvent.PointerProperties().apply { id = 9; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f }, MotionEvent.PointerCoords().apply { x = penX; y = 30f; pressure = .8f })
        return MotionEvent.obtain(1, time, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }
    @Test fun normalizationSelectsPenNotPalmAndPreservesHistory() {
        val source = mixed(MotionEvent.ACTION_MOVE, 20)
        val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())
        source.getPointerCoords(0, coords[0]); source.getPointerCoords(1, coords[1]); coords[1].x = 70f
        source.addBatch(24, coords, 0)
        val pen = PenEventNormalizer().obtain(source, 1, 10, MotionEvent.ACTION_MOVE)
        try {
            assertEquals(1, pen.pointerCount); assertEquals(0, pen.getPointerId(0))
            assertEquals(MotionEvent.TOOL_TYPE_STYLUS, pen.getToolType(0))
            assertEquals(1, pen.historySize); assertEquals(60f, pen.getHistoricalX(0), 0f)
            assertEquals(70f, pen.x, 0f); assertEquals(.8f, pen.pressure, 0f); assertEquals(10L, pen.downTime)
        } finally { source.recycle(); pen.recycle() }
    }
    @Test fun palmLiftDoesNotCommitOrContaminatePenStroke() = withNativeInkTestHost { activity ->
        val view = StylusInkView(activity).apply { enabledForWriting = true; placements = listOf(PagePlacement(0, 0f, 0f, 200f, 200f, 1f)) }
        var result = emptyList<InkStroke>(); view.onChange = { result = it }
        fun send(action: Int, time: Long) { mixed(action, time).let { try { view.onTouchEvent(it) } finally { it.recycle() } } }
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10)
        send(MotionEvent.ACTION_MOVE, 20)
        send(MotionEvent.ACTION_POINTER_UP, 21) // Finger index 0 lifts, pen still held.
        assertTrue(result.isEmpty())
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 24)
        assertEquals(1, result.size); assertTrue(result.single().points.all { it.x == 60f && it.pressure == .8f })
        assertFalse(result.single().points.any { it.time == 21L })
    }
    @Test fun rasterCachePreservesSegmentsClearsAndRebuildsAfterResize() {
        val cache = InkRasterCache(); cache.resize(100, 100)
        val page = PagePlacement(0, 0f, 0f, 100f, 100f, 1f)
        val a = InkPoint(10f, 50f, .5f, 1); val b = a.copy(x = 90f, time = 2)
        cache.segment(page, a, b, android.graphics.Color.BLACK, 4f)
        val output = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        cache.show(Canvas(output)); assertEquals(android.graphics.Color.BLACK, output.getPixel(50, 50))
        assertEquals(0, output.getPixel(50, 10))
        cache.clear(); output.eraseColor(0); cache.show(Canvas(output)); assertEquals(0, output.getPixel(50, 50))
        assertFalse(cache.resize(100, 100)); assertTrue(cache.resize(200, 100))
        cache.release(); output.recycle()
    }
}
