package io.graspfolio.app

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeDoubleTapToolbarTest {
    @Test fun portraitSwitch() = checkToolbar(false)
    @Test fun landscapeSwitch() = checkToolbar(true)

    private fun checkToolbar(landscape: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity --ez landscape $landscape"
        )).use { it.readBytes() }
        automation.waitForIdle(500, 5000)
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun awaitNode(label: String, checked: Boolean? = null): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 7000
            while (SystemClock.uptimeMillis() < deadline) {
                val node = find(automation.rootInActiveWindow, label)
                if (node != null && node.isVisibleToUser && (checked == null || node.isChecked == checked)) return node
                Thread.sleep(50)
            }
            error("Missing toolbar control: $label ($checked)")
        }
        val label = "轻敲切换笔刷与橡皮"
        val toggle = awaitNode(label, true)
        assertTrue(toggle.isCheckable)
        val toggleBounds = Rect().also { toggle.getBoundsInScreen(it) }
        val brushBounds = Rect().also { awaitNode("画笔颜色与粗细").getBoundsInScreen(it) }
        val settingsBounds = Rect().also { awaitNode("阅读设置与保存").getBoundsInScreen(it) }
        assertTrue(brushBounds.right <= toggleBounds.left)
        assertTrue(toggleBounds.right <= settingsBounds.left)
        assertEquals(toggleBounds.width(), toggleBounds.height())
        fun screenshot(state: String) {
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, "tap-${if (landscape) "landscape" else "portrait"}-$state.png")
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        screenshot("on")
        assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val off = awaitNode(label, false)
        if (android.os.Build.VERSION.SDK_INT >= 30) assertEquals("已关闭", off.stateDescription?.toString())
        screenshot("off")
        assertTrue(off.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val on = awaitNode(label, true)
        if (android.os.Build.VERSION.SDK_INT >= 30) assertEquals("已开启", on.stateDescription?.toString())
        awaitNode("返回沉浸阅读").performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
