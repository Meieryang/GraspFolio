package io.graspfolio.app

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class GlassFeedbackTest {
    @Test fun touchLightAppearsAndCancellationRestoresSurface() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity"
        )).use { it.readBytes() }
        automation.waitForIdle(500, 5000)
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == "阅读设置与保存") return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val bounds = Rect().also { checkNotNull(find(automation.rootInActiveWindow)).getBoundsInScreen(it) }
        Thread.sleep(500)
        val before = checkNotNull(automation.takeScreenshot())
        val time = SystemClock.uptimeMillis()
        fun event(action: Int) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN)
        val pressed = try { Thread.sleep(250); checkNotNull(automation.takeScreenshot()) }
            finally { event(MotionEvent.ACTION_CANCEL) }
        Thread.sleep(600)
        val restored = checkNotNull(automation.takeScreenshot())
        try {
            var changed = 0
            var residual = 0
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                fun difference(a: Int, b: Int) = kotlin.math.abs((a and 255) - (b and 255)) +
                    kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
                    kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255))
                if (difference(before.getPixel(x, y), pressed.getPixel(x, y)) > 12) changed++
                if (difference(before.getPixel(x, y), restored.getPixel(x, y)) > 12) residual++
            }
            assertTrue("A held touch must visibly light the glass: $changed", changed > 20)
            assertTrue("Cancel must remove the touch light: $residual / $changed", residual < changed / 4)
        } finally { before.recycle(); pressed.recycle(); restored.recycle() }
    }
}
