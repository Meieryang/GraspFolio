package io.graspfolio.app

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioPlaybackTest {
    @Test fun portraitPlayback() = verify(false)
    @Test fun landscapePlayback() = verify(true)
    private fun verify(landscape: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val bytes = testAudioWav(12)
        val hash = inkDigest(bytes)
        val note = AudioNote("test", 0, "audio.wav", hash, 12000, bytes.size.toLong())
        val file = AudioAssets(instrumentation.targetContext).file(note).apply { writeBytes(bytes) }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 7000
            while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            var detail = ""
            instrumentation.runOnMainSync { ReaderToolsPreviewActivity.audioPlayerForTest?.let { detail = "selected=${it.selected?.id} playing=${it.playing} preparing=${it.preparing} position=${it.position} error=${it.error}" } }
            if (!condition()) {
                automation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.cacheDir, "audio-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
            assertTrue(detail, condition())
        }
        fun bounds(label: String): Rect {
            await { find(automation.rootInActiveWindow, label)?.isVisibleToUser == true }
            return Rect().also { find(automation.rootInActiveWindow, label)!!.getBoundsInScreen(it) }
        }
        fun tap(label: String) { val b = bounds(label); android.util.Log.i("AudioTest", "$label bounds=$b"); shell("input tap ${b.centerX()} ${b.centerY()}") }
        fun state(block: (PageAudioPlayer) -> Boolean): Boolean {
            var value = false
            instrumentation.runOnMainSync { ReaderToolsPreviewActivity.audioPlayerForTest?.let { value = block(it) } }
            return value
        }
        fun action(block: (PageAudioPlayer) -> Unit) = instrumentation.runOnMainSync { block(checkNotNull(ReaderToolsPreviewActivity.audioPlayerForTest)) }
        try {
            automation.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0)
            shell("am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity --ez landscape $landscape --ez narrow false --es audioHash $hash --el audioBytes ${bytes.size} --ei audioDuration 12000")
            automation.waitForIdle(500, 5000)
            val insert = bounds("插入音频")
            val brush = bounds("压感笔")
            assertTrue(insert.right <= brush.left)
            tap("播放音频：课堂讲解.wav，第 6 页")
            await { state { it.playing && it.position > 0 } }
            assertTrue(state { !it.expanded })
            tap("播放音频：课堂讲解.wav，第 6 页")
            await { state { it.expanded } }
            bounds("播放或暂停音频")
            val slider = bounds("音频播放进度")
            shell("input swipe ${slider.left + 5} ${slider.centerY()} ${slider.centerX()} ${slider.centerY()} 350")
            await { state { it.position > 3000 } }
            tap("播放或暂停音频")
            await { state { !it.playing } }
            action { it.seek(6000) }
            tap("播放或暂停音频")
            await { state { it.playing } }
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, "audio-${if (landscape) "landscape" else "portrait"}.png")
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            tap("播放音频：补充说明.wav，第 7 页")
            await { state { it.selected?.id == "preview-right" && it.playing } }
            action { it.setForeground(false) }
            assertTrue(state { !it.playing })
            action { it.visiblePages(listOf(9)) }
            assertTrue(state { it.selected == null && !it.playing })
            tap("退出阅读")
            await { ReaderToolsPreviewActivity.audioPlayerForTest == null }
        } finally {
            instrumentation.runOnMainSync { ReaderToolsPreviewActivity.audioPlayerForTest?.clear() }
            file.delete()
            automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
