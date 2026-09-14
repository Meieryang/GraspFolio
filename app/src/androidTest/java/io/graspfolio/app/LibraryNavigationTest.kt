package io.graspfolio.app

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LibraryNavigationTest {
    @Test fun portraitLibrary() = verify(false)
    @Test fun landscapeLibrary() = verify(true)
    private fun verify(landscape: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun node(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) {
                find(automation.rootInActiveWindow, label)?.takeIf { it.isVisibleToUser }?.let { return it }
                Thread.sleep(50)
            }
            error("Missing library control: $label")
        }
        fun click(label: String) {
            val bounds = Rect().also { node(label).getBoundsInScreen(it) }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
            automation.waitForIdle(250, 5000)
        }
        try {
            automation.setRotation(if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n io.graspfolio.app/.MainActivity")).use { it.readBytes() }
            automation.waitForIdle(500, 5000)
            val library = Rect().also { node("图书库").getBoundsInScreen(it) }
            val recent = Rect().also { node("最近打开").getBoundsInScreen(it) }
            assertTrue(recent.top >= library.bottom)
            click("最近打开")
            click("搜索书籍")
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input text __missing_library_test_book__")).use { it.readBytes() }
            node("没有找到这本书")
            click("设置")
            node("图书库扫描路径"); node("图书库扫描文件夹")
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, "library-settings-${if (landscape) "landscape" else "portrait"}.png")
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            click("图书库")
        } finally { automation.setRotation(UiAutomation.ROTATION_UNFREEZE) }
    }
}
