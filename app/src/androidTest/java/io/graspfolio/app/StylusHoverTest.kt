package io.graspfolio.app

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class StylusHoverTest {
    @Test fun hoverOnlyAndQueuedGestureCannotSurviveExitOrContact() {
        val gate = DoubleTapSwitch().apply { enabled = false }
        var toggles = 0
        var pending: (() -> Unit)? = null
        fun gesture() = gate.dispatch({ true }, { pending = it; true }, { toggles++ })
        fun event(action: Int, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, distance: Float = 0f) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
            val coordinates = MotionEvent.PointerCoords().apply { x = 100f; y = 100f; setAxisValue(MotionEvent.AXIS_DISTANCE, distance) }
            val e = MotionEvent.obtain(0, 1, action, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { gate.observeStylus(e) } finally { e.recycle() }
        }
        assertFalse(gesture())
        event(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.TOOL_TYPE_MOUSE)
        assertFalse(gesture())
        event(MotionEvent.ACTION_HOVER_ENTER, distance = 100f)
        assertTrue(gesture()); pending!!(); assertEquals(1, toggles)
        assertTrue(gesture())
        event(MotionEvent.ACTION_HOVER_EXIT)
        event(MotionEvent.ACTION_HOVER_ENTER)
        pending!!(); assertEquals(1, toggles)
        assertTrue(gesture())
        event(MotionEvent.ACTION_DOWN)
        pending!!(); assertEquals(1, toggles); assertFalse(gesture())
        event(MotionEvent.ACTION_UP)
        assertFalse(gesture())
        event(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.TOOL_TYPE_ERASER)
        assertTrue(gesture()); pending!!(); assertEquals(2, toggles)
        event(MotionEvent.ACTION_CANCEL)
        assertFalse(gesture())
    }
}
