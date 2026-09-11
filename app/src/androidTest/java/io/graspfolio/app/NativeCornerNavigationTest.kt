package io.graspfolio.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Uses a declared debug Activity, avoiding the device's stalled Compose test Activity launch. */
class NativeCornerNavigationTest {
    @Test fun outerCornersCancellationAndContinuousDragWithNativeOverlay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.CornerNavigationTestActivity"
        )).use { it.readBytes() }
        fun eventually(check: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!check()) { assertTrue("Gesture fixture timed out", SystemClock.uptimeMillis() < deadline); Thread.sleep(20) }
        }
        eventually { CornerNavigationTestActivity.current?.viewport?.width?.let { it > 0 } == true }
        val host = checkNotNull(CornerNavigationTestActivity.current)
        val bounds = host.viewport
        val left = bounds.left + 10f; val right = bounds.right - 10f; val top = bounds.top + 10f
        var downTime = 0L
        fun event(action: Int, x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            instrumentation.runOnMainSync {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                val e = MotionEvent.obtain(downTime, now, action, 1, arrayOf(properties), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { host.dispatchTouchEvent(e) } finally { e.recycle() }
            }
        }
        fun tap(x: Float) { event(MotionEvent.ACTION_DOWN, x, top); event(MotionEvent.ACTION_UP, x, top) }
        try {
            Thread.sleep(200)
            tap(right); eventually { host.turns == 1 }
            tap(left); eventually { host.turns == 0 }
            // Keep pointer 0 resting in the page centre while pointer 1 turns repeatedly.
            event(MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y)
            fun secondFinger(action: Int, x: Float) {
                instrumentation.runOnMainSync {
                    val properties = Array(2) { i -> MotionEvent.PointerProperties().apply {
                        id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                    } }
                    val coordinates = Array(2) { i -> MotionEvent.PointerCoords().apply {
                        this.x = if (i == 0) bounds.center.x else x
                        y = if (i == 0) bounds.center.y else top
                        pressure = 1f; size = 1f
                    } }
                    val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates,
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { host.dispatchTouchEvent(e) } finally { e.recycle() }
                }
            }
            secondFinger(MotionEvent.ACTION_POINTER_DOWN, right)
            secondFinger(MotionEvent.ACTION_POINTER_UP, right)
            eventually { host.turns == 1 }
            secondFinger(MotionEvent.ACTION_POINTER_DOWN, left)
            secondFinger(MotionEvent.ACTION_POINTER_UP, left)
            eventually { host.turns == 0 }
            event(MotionEvent.ACTION_UP, bounds.center.x, bounds.center.y)
            tap(bounds.center.x - 10f)
            event(MotionEvent.ACTION_DOWN, bounds.center.x + 10f, top)
            Thread.sleep(750)
            event(MotionEvent.ACTION_UP, bounds.center.x + 10f, top)
            assertEquals(0, host.turns); assertEquals(0, host.starts)

            event(MotionEvent.ACTION_DOWN, right, top)
            event(MotionEvent.ACTION_MOVE, bounds.center.x, bounds.center.y)
            Thread.sleep(750)
            event(MotionEvent.ACTION_MOVE, right, top)
            event(MotionEvent.ACTION_UP, right, top)
            assertEquals(0, host.turns); assertEquals(0, host.starts)
            tap(right); eventually { host.turns == 1 }

            for ((x, direction) in listOf(right to 1, left to -1)) {
                val before = host.turns
                event(MotionEvent.ACTION_DOWN, x, top)
                eventually { host.continuous && (host.turns - before) * direction >= 2 }
                val active = host.turns
                event(MotionEvent.ACTION_MOVE, x, bounds.center.y)
                eventually { (host.turns - active) * direction >= 2 }
                assertTrue(host.continuous)
                event(MotionEvent.ACTION_MOVE, x, top)
                event(MotionEvent.ACTION_UP, x, top)
                eventually { !host.continuous }
                val stopped = host.turns
                Thread.sleep(750)
                assertEquals(stopped, host.turns)
            }
            assertEquals(2, host.starts)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
