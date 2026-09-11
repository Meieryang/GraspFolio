package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LassoPerformanceTest {
    @Test fun denseSelectionAndRepeatedDragKeepMainThreadResponsive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity"
        )).use { it.readBytes() }
        val data = List(1500) { i ->
            val x = 50f + (i % 30) * 25f; val y = 50f + (i / 30) * 20f
            InkStroke("stress-$i", 0, List(80) { j -> InkPoint(x + j * .2f, y + (j % 8) * .25f, .6f, j.toLong()) })
        }
        lateinit var view: StylusInkView
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var commits = 0
        withNativeInkTestHost { host ->
            view = StylusInkView(host).apply {
                layout(0, 0, 1200, 1800); enabledForWriting = true; lasso = true
                placements = listOf(PagePlacement(0, 0f, 0f, 1200f, 1800f, 1f))
                strokes = data; onChange = { commits++ }
            }
            view.draw(canvas)
        }
        fun event(action: Int, x: Float, y: Float) {
            val properties = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .6f }
            val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        var maxHeartbeatMs = 0L
        fun awaitWork() {
            val start = SystemClock.uptimeMillis()
            while (true) {
                var busy = false
                val before = SystemClock.uptimeMillis()
                instrumentation.runOnMainSync { busy = view.lassoBusy }
                maxHeartbeatMs = maxOf(maxHeartbeatMs, SystemClock.uptimeMillis() - before)
                if (!busy) break
                assertTrue("Background lasso work timed out", SystemClock.uptimeMillis() - start < 30000)
                Thread.sleep(10)
            }
        }
        try {
            val selectionStart = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                event(MotionEvent.ACTION_DOWN, 20f, 20f)
                event(MotionEvent.ACTION_MOVE, 900f, 20f)
                event(MotionEvent.ACTION_MOVE, 900f, 1200f)
                event(MotionEvent.ACTION_MOVE, 20f, 1200f)
                event(MotionEvent.ACTION_UP, 20f, 20f)
                assertTrue(view.lassoBusy)
            }
            awaitWork()
            val selectionMs = SystemClock.uptimeMillis() - selectionStart
            var drawNanos = 0L
            repeat(3) {
                instrumentation.runOnMainSync {
                    event(MotionEvent.ACTION_DOWN, 400f, 500f)
                    repeat(60) { frame ->
                        val start = System.nanoTime()
                        event(MotionEvent.ACTION_MOVE, 400f + frame / 3f, 500f + frame / 3f)
                        view.draw(canvas)
                        drawNanos += System.nanoTime() - start
                    }
                    event(MotionEvent.ACTION_UP, 420f, 520f)
                }
                awaitWork()
            }
            instrumentation.runOnMainSync {
                assertEquals(3, commits)
                assertEquals(data.size, view.strokes.size)
                assertEquals(data.first().points.first().x + 60f, view.strokes.first().points.first().x)
                // A queued selection must not reappear after a tool change.
                event(MotionEvent.ACTION_DOWN, 10f, 10f)
                event(MotionEvent.ACTION_MOVE, 1100f, 10f)
                event(MotionEvent.ACTION_MOVE, 1100f, 1600f)
                event(MotionEvent.ACTION_UP, 10f, 1600f)
                view.lasso = false
                assertFalse(view.lassoBusy)
            }
            assertTrue("UI heartbeat blocked: $maxHeartbeatMs ms", maxHeartbeatMs < 1000)
            val averageMs = drawNanos / 180 / 1_000_000.0
            assertTrue("Drag frames too slow: $averageMs ms", averageMs < 50)
            android.util.Log.i("GraspFolioLassoTest", "120000 points: selection=${selectionMs}ms, dragMean=${averageMs}ms, heartbeatMax=${maxHeartbeatMs}ms")
        } finally {
            instrumentation.runOnMainSync { view.lasso = false; view.cancelStroke() }
            bitmap.recycle()
        }
    }
}
