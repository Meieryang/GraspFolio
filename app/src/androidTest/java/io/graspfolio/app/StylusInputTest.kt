package io.graspfolio.app

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class StylusInputTest {
    private fun event(action: Int, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 7; toolType = tool }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .7f }
        return MotionEvent.obtain(0, 10, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }
    private fun send(view: StylusInkView, action: Int, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        event(action, x, y, tool).let { try { view.onTouchEvent(it) } finally { it.recycle() } }
    }
    @Test fun pageContactDismissalDoesNotEatFirstStrokeOrFingerTouch() = withNativeInkTestHost { activity ->
        val view = StylusInkView(activity).apply {
            enabledForWriting = true
            placements = listOf(PagePlacement(0, 0f, 0f, 100f, 100f, 1f))
        }
        var contacts = 0
        var result = emptyList<InkStroke>()
        view.onPageContact = { contacts++ }
        view.onChange = { result = it }
        send(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(view, MotionEvent.ACTION_UP, 20f, 20f)
        assertEquals(1, contacts)
        assertEquals(1, result.size)
        send(view, MotionEvent.ACTION_DOWN, 30f, 30f, MotionEvent.TOOL_TYPE_FINGER)
        send(view, MotionEvent.ACTION_UP, 30f, 30f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(2, contacts)
        assertEquals(1, result.size)
        send(view, MotionEvent.ACTION_DOWN, 120f, 120f, MotionEvent.TOOL_TYPE_FINGER)
        send(view, MotionEvent.ACTION_UP, 120f, 120f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(2, contacts)
    }
    @Test fun lassoMovesInkWithoutAddingAStrokeAndCancelRestores() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val original = InkStroke("selected", 0, listOf(InkPoint(20f, 20f, .7f, 1), InkPoint(40f, 40f, .7f, 2)))
        lateinit var view: StylusInkView
        var commits = 0
        withNativeInkTestHost { activity ->
            view = StylusInkView(activity).apply {
                enabledForWriting = true; lasso = true; layout(0, 0, 100, 100)
                placements = listOf(PagePlacement(0, 0f, 0f, 100f, 100f, 1f)); strokes = listOf(original)
                onChange = { commits++ }
            }
            send(view, MotionEvent.ACTION_DOWN, 10f, 10f)
            send(view, MotionEvent.ACTION_MOVE, 50f, 10f)
            send(view, MotionEvent.ACTION_MOVE, 50f, 50f)
            send(view, MotionEvent.ACTION_MOVE, 10f, 50f)
            send(view, MotionEvent.ACTION_UP, 10f, 10f)
        }
        fun awaitWork() {
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (true) {
                var busy = false
                instrumentation.runOnMainSync { busy = view.lassoBusy }
                if (!busy) break
                assertTrue("Lasso worker timed out", android.os.SystemClock.uptimeMillis() < deadline)
                Thread.sleep(10)
            }
        }
        awaitWork()
        instrumentation.runOnMainSync {
            assertEquals(0, commits)
            send(view, MotionEvent.ACTION_DOWN, 30f, 30f)
            send(view, MotionEvent.ACTION_MOVE, 50f, 50f)
            send(view, MotionEvent.ACTION_CANCEL, 50f, 50f)
            assertEquals(original, view.strokes.single())
            send(view, MotionEvent.ACTION_DOWN, 30f, 30f)
            send(view, MotionEvent.ACTION_UP, 50f, 50f)
        }
        awaitWork()
        instrumentation.runOnMainSync {
            assertEquals(1, commits)
            assertEquals("selected", view.strokes.single().id)
            assertEquals(40f, view.strokes.single().points.first().x)
            view.lasso = false
        }
    }
    @Test fun eraserContactCircleDisappearsOnUpAndCancel() = withNativeInkTestHost { activity ->
        val view = StylusInkView(activity).apply {
            enabledForWriting = true; eraser = true
            placements = listOf(PagePlacement(0, 0f, 0f, 100f, 100f, 1f)); layout(0, 0, 100, 100)
        }
        fun hasMarker(): Boolean {
            val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val visible = android.graphics.Color.alpha(bitmap.getPixel(50, 50)) > 0
            bitmap.recycle(); return visible
        }
        assertFalse(hasMarker())
        send(view, MotionEvent.ACTION_DOWN, 50f, 50f); assertTrue(hasMarker())
        send(view, MotionEvent.ACTION_UP, 50f, 50f); assertFalse(hasMarker())
        send(view, MotionEvent.ACTION_DOWN, 50f, 50f); assertTrue(hasMarker())
        send(view, MotionEvent.ACTION_CANCEL, 50f, 50f); assertFalse(hasMarker())
    }
    @Test fun fingerDoesNotWriteAndCancelDoesNotCommit() = withNativeInkTestHost { activity ->
        val view = StylusInkView(activity).apply { enabledForWriting = true; placements = listOf(PagePlacement(0, 0f, 0f, 100f, 100f, 1f)) }
        var commits = 0; view.onChange = { commits++ }
        send(view, MotionEvent.ACTION_DOWN, 10f, 10f, MotionEvent.TOOL_TYPE_FINGER)
        send(view, MotionEvent.ACTION_UP, 10f, 10f, MotionEvent.TOOL_TYPE_FINGER)
        send(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(view, MotionEvent.ACTION_CANCEL, 20f, 20f)
        assertEquals(0, commits)
    }
    @Test fun penCommitsToStartingPhysicalPageOnly() = withNativeInkTestHost { activity ->
        val view = StylusInkView(activity).apply { enabledForWriting = true; placements = listOf(PagePlacement(3, 0f, 0f, 100f, 100f, 1f), PagePlacement(4, 100f, 0f, 100f, 100f, 1f)) }
        var result = emptyList<InkStroke>(); view.onChange = { result = it }
        send(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(view, MotionEvent.ACTION_MOVE, 120f, 20f)
        send(view, MotionEvent.ACTION_UP, 130f, 30f)
        assertEquals(1, result.size); assertEquals(3, result.single().page)
        assertTrue(result.single().points.all { it.pressure == .7f })
    }
    @Test fun consecutiveStrokesBeforeRecompositionAreBothKeptAndEraserCancelRestores() = withNativeInkTestHost { activity ->
        val view = StylusInkView(activity).apply { enabledForWriting = true; placements = listOf(PagePlacement(0, 0f, 0f, 100f, 100f, 1f)) }
        var result = emptyList<InkStroke>(); view.onChange = { result = it }
        repeat(2) { i ->
            send(view, MotionEvent.ACTION_DOWN, 10f + i * 50, 10f)
            send(view, MotionEvent.ACTION_UP, 10f + i * 50, 20f)
        }
        assertEquals(2, result.size)
        view.eraser = true
        send(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(view, MotionEvent.ACTION_CANCEL, 10f, 10f)
        assertEquals(2, result.size)
        send(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(view, MotionEvent.ACTION_UP, 10f, 10f)
        assertEquals(1, result.size)
    }
}
