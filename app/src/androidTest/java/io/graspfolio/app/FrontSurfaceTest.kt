package io.graspfolio.app

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Samples actual composed surfaces, not only the software scene renderer. */
class FrontSurfaceTest {
    @Test fun activeInkAndImmediateNextStrokeRemainOnScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.PenPreviewActivity"
        )).use { it.readBytes() }
        ActivityScenario.launch<PenPreviewActivity>(Intent(instrumentation.targetContext, PenPreviewActivity::class.java)).use { scenario ->
            var diagnostic = ""
            scenario.onActivity { it.ink.onDiagnostics = { message -> diagnostic = message } }
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) { scenario.onActivity { ready = it.ink.lowLatencyReady }; Thread.sleep(50) }
            assertTrue("Front surface not ready: $diagnostic", ready)
            val location = IntArray(2)
            scenario.onActivity { it.ink.getLocationOnScreen(location); it.ink.predictionEnabled = false }
            var down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                scenario.onActivity { host ->
                    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                    val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                    try { host.ink.onTouchEvent(event) } finally { event.recycle() }
                }
            }
            fun assertVisible(x: Int, y: Int) {
                val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val pixel = screen.getPixel(x + location[0], y + location[1])
                    assertTrue("Missing ink at $x,$y: ${Integer.toHexString(pixel)}", Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80)
                } finally { screen.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, 100f, 300f)
            repeat(12) { step ->
                send(MotionEvent.ACTION_MOVE, 200f + step * 15, 300f)
                Thread.sleep(30)
                assertVisible(110, 300)
            }
            send(MotionEvent.ACTION_UP, 380f, 300f)
            repeat(10) { step ->
                down = SystemClock.uptimeMillis()
                val y = 360f + step * 20
                send(MotionEvent.ACTION_DOWN, 100f, y)
                send(MotionEvent.ACTION_MOVE, 250f, y)
                Thread.sleep(30)
                assertVisible(110, y.toInt()); assertVisible(110, 300)
                send(MotionEvent.ACTION_UP, 250f, y)
            }
            Thread.sleep(100)
            scenario.onActivity { host -> assertEquals(11, host.ink.strokes.size) }
            assertVisible(110, 540)
        }
    }
}
