package io.graspfolio.app

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ImmersiveToolbarTest {
    @Test fun menuRoundTripsAndClose() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            automation.waitForIdle(350, 5000)
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun node(label: String) = find(automation.rootInActiveWindow, label)
        fun await(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                node(label)?.takeIf { it.isVisibleToUser }?.let { return it }
                Thread.sleep(50)
            }
            error("Missing $label")
        }
        shell("am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity --ez chromeCycle true --ez narrow true")
        assertNull(node("压感笔"))
        repeat(2) {
            shell("input keyevent 4")
            await("阅读设置与保存")
            assertNull(node("关闭沉浸画笔栏"))
            shell("input keyevent 4")
            val close = await("关闭沉浸画笔栏")
            val bounds = Rect().also { close.getBoundsInScreen(it) }
            assertEquals("Toolbar must touch the screen top", 0, bounds.top)
            assertNull(node("阅读设置与保存"))
            assertNull(node("阅读进度"))
            await("压感笔")
            val red = Rect().also { await("陶粉").getBoundsInScreen(it) }
            assertTrue(red.right <= bounds.left)
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (node("压感笔") != null && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            assertNull(node("压感笔"))
        }
        // Re-enter the menu while the writing toolbar is still visible: only one toolbar remains.
        shell("input keyevent 4")
        await("阅读设置与保存")
        shell("input keyevent 4")
        await("关闭沉浸画笔栏")
        shell("input keyevent 4")
        await("阅读设置与保存")
        assertNull(node("关闭沉浸画笔栏"))
    }
}
