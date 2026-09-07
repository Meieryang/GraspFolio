package io.graspfolio.app

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.vivo.penengine.impl.VivoAlgorithmManagerImpl
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Native SDK load/call smoke test, not a physical stylus latency measurement. */
class VivoSdkSmokeTest {
    @Test fun vivoTabletCanInitializeAndCallPredictionEngine() {
        assumeTrue(Build.BRAND == "vivo")
        withNativeInkTestHost { activity ->
            val engine = VivoAlgorithmManagerImpl(activity)
            try {
                assumeTrue(engine.isTablet)
                assertTrue("vivo prediction engine did not initialize", engine.isEstimateEnable)
                val start = SystemClock.uptimeMillis()
                val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                val coordinates = arrayOf(MotionEvent.PointerCoords().apply { y = 100f; pressure = .5f })
                for (i in 0..5) {
                    coordinates[0].x = 100f + 4f * i
                    val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 5 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val event = MotionEvent.obtain(start, start + i * 8L, action, 1, properties, coordinates,
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                    try {
                        // Preserve the application's default overload/prediction rate for this comparison.
                        val point = engine.computeEstimatePoint(event)
                        assertTrue("SDK returned no finite point", point != null && point.x.isFinite() && point.y.isFinite())
                    } finally { event.recycle() }
                }
            } finally { engine.release() }
        }
    }
}
