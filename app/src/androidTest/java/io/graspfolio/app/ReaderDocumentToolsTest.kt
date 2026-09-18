package io.graspfolio.app

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ReaderDocumentToolsTest {
    private fun ink(view: View): StylusInkView? = if (view is StylusInkView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { ink(view.getChildAt(it)) } else null
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label) }
    }
    private fun eventually(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!check()) { check(SystemClock.uptimeMillis() < end) { "UI timed out" }; Thread.sleep(30) }
    }
    @Test fun blankConfirmationAndFingerTextSelectionUseRealReader() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val auto = inst.uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity")).use { it.readBytes() }
        val id = "tools-${UUID.randomUUID()}"
        val dir = File(context.cacheDir, "storage-provider-tests/$id").apply { mkdirs() }
        val pdf = File(dir, "test.pdf")
        val document = PdfDocument()
        try {
            repeat(3) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                page.canvas.drawText("Hello PDF world", 100f, 160f, Paint().apply { textSize = 30f })
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val uri = DocumentsContract.buildDocumentUri(StorageTestProvider.AUTHORITY, "$id/test.pdf")
        val stateFile = File(dir, "state.json")
        AnnotationJournal(stateFile, "fixture").importRemote(emptyList(), "initial", ReadingProgress(0, false))
        val backend = object : AnnotationBackend {
            override fun openJournal() = AnnotationJournal(stateFile, "fixture")
            override fun sync(folder: android.net.Uri, snapshot: DurableInk, allowLoad: Boolean) = error("No folder")
        }
        lateinit var store: AnnotationStore
        inst.runOnMainSync { store = AnnotationStore.obtain(context, uri, backend) }
        try {
            ActivityScenario.launch<ReaderLoadingTestActivity>(Intent(context, ReaderLoadingTestActivity::class.java).setData(uri)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                eventually { var ready = false; scenario.onActivity { ready = ink(it.window.decorView)?.enabledForWriting == true }; ready }
                fun tap(label: String) {
                    eventually { find(auto.rootInActiveWindow, label) != null }
                    val bounds = android.graphics.Rect(); find(auto.rootInActiveWindow, label)!!.getBoundsInScreen(bounds)
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 20 else 0, action, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        try { auto.injectInputEvent(event, true) } finally { event.recycle() }
                    }
                    inst.waitForIdleSync()
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                auto.takeScreenshot()?.let { bitmap -> File(context.cacheDir, "document-tools-toolbar.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                tap("向后插入空白页")
                Thread.sleep(200)
                auto.takeScreenshot()?.let { bitmap -> File(context.cacheDir, "document-tools-confirm.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                tap("取消")
                inst.runOnMainSync { assertTrue(store.progress!!.blanks.isEmpty()) }
                tap("文字选择")
                fun touchWord(long: Boolean) {
                    val down = SystemClock.uptimeMillis()
                    fun send(action: Int) { scenario.onActivity { host ->
                        val view = requireNotNull(ink(host.window.decorView)); val page = view.placements.single()
                        val props = arrayOf(MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = page.left + 125f * page.scale; y = page.top + 148f * page.scale; pressure = 1f })
                        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                        try { view.onTouchEvent(e) } finally { e.recycle() }
                    } }
                    send(MotionEvent.ACTION_DOWN); if (long) Thread.sleep(700); send(MotionEvent.ACTION_UP)
                }
                touchWord(true); Thread.sleep(250)
                assertNull(find(auto.rootInActiveWindow, "复制"))
                touchWord(false)
                eventually { find(auto.rootInActiveWindow, "复制") != null }
                auto.takeScreenshot()?.let { bitmap -> File(context.cacheDir, "document-tools-selection.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                tap("复制")
                scenario.onActivity { host ->
                    val copied = host.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                    assertTrue(copied.contains("Hello"))
                }
                tap("文字选择")
                eventually { var disabled = false; scenario.onActivity { disabled = ink(it.window.decorView)?.textSelection == null }; disabled }
                touchWord(true); touchWord(false)
                inst.waitForIdleSync()
                assertNull(find(auto.rootInActiveWindow, "复制"))
                tap("向后插入空白页"); tap("确认插入")
                eventually { var ids = emptyList<Int>(); scenario.onActivity { ids = ink(it.window.decorView)?.placements?.map { p -> p.page }.orEmpty() }; ids == listOf(3) }
                eventually { AnnotationJournal(stateFile, "fixture").state.progress?.blanks?.size == 1 }
                assertEquals(listOf(0, 3, 1, 2), AnnotationJournal(stateFile, "fixture").state.progress!!.order(3))
            }
        } finally {
            inst.runOnMainSync { store.release() }
            // Leave asynchronous durable work to drain before removing this private fixture.
            eventually { store.isClosed }
            dir.deleteRecursively()
        }
    }
}
