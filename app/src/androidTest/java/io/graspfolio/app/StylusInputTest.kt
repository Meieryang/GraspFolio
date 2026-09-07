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
