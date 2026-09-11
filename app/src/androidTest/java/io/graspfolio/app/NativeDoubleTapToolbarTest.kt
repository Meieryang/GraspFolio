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

    @Test fun compactToolbar() = checkToolbar(false, true)

    private fun checkToolbar(landscape: Boolean, narrow: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity --ez landscape $landscape --ez narrow $narrow"
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
        for (tool in listOf("压感笔", "荧光笔", "整笔橡皮", "套索笔", "墨黑", "雾蓝", "陶粉")) awaitNode(tool)
        assertNull(find(automation.rootInActiveWindow, "等宽笔"))
        val label = "轻敲切换笔刷与橡皮"
        val toggle = awaitNode(label, true)
        assertTrue(toggle.isCheckable)
        val toggleBounds = Rect().also { toggle.getBoundsInScreen(it) }
        val brushBounds = Rect().also { awaitNode("陶粉").getBoundsInScreen(it) }
        val settingsBounds = Rect().also { awaitNode("阅读设置与保存").getBoundsInScreen(it) }
        assertTrue(brushBounds.right <= toggleBounds.left)
        assertTrue(toggleBounds.right <= settingsBounds.left)
        assertEquals(toggleBounds.width(), toggleBounds.height())
        fun screenshot(state: String) {
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, "tap-${if (narrow) "compact" else if (landscape) "landscape" else "portrait"}-$state.png")
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
        if (!landscape && !narrow) {
            fun tap(label: String) {
                val bounds = Rect().also { awaitNode(label).getBoundsInScreen(it) }
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                    "input tap ${bounds.centerX()} ${bounds.centerY()}"
                )).use { it.readBytes() }
                automation.waitForIdle(350, 5000)
            }
            for ((index, choice) in listOf("雾蓝" to 0x567896, "陶粉" to 0xc57968, "雾蓝" to 0x567896).withIndex()) {
                val (name, rgb) = choice
                if (index == 2) tap("荧光笔")
                val swatch = Rect().also { awaitNode(name).getBoundsInScreen(it) }
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                    "input tap ${swatch.centerX()} ${swatch.centerY()}"
                )).use { it.readBytes() }
                automation.waitForIdle(350, 5000)
                if (index == 2) tap("压感笔")
                val bounds = Rect().also { awaitNode(if (index == 2) "荧光笔" else "压感笔").getBoundsInScreen(it) }
                // UiAutomation idle does not wait for Compose drawing/colour animation frames.
                val deadline = SystemClock.uptimeMillis() + 5000
                var matchingTipPixels = 0
                while (SystemClock.uptimeMillis() < deadline && matchingTipPixels <= 5) {
                    val bitmap = checkNotNull(automation.takeScreenshot())
                    matchingTipPixels = 0
                    // Only the bottom quarter: a coloured barrel band cannot satisfy this check.
                    for (y in bounds.top + bounds.height() * 3 / 4 until bounds.bottom.coerceAtMost(bitmap.height)) {
                        for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                            val pixel = bitmap.getPixel(x, y)
                            if (kotlin.math.abs(android.graphics.Color.red(pixel) - ((rgb shr 16) and 255)) < 12 &&
                                kotlin.math.abs(android.graphics.Color.green(pixel) - ((rgb shr 8) and 255)) < 12 &&
                                kotlin.math.abs(android.graphics.Color.blue(pixel) - (rgb and 255)) < 12) matchingTipPixels++
                        }
                    }
                    bitmap.recycle()
                    if (matchingTipPixels <= 5) Thread.sleep(50)
                }
                assertTrue("$name must colour the actual pen tip", matchingTipPixels > 5)
                screenshot(name)
            }
        }
        awaitNode("退出阅读").performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
