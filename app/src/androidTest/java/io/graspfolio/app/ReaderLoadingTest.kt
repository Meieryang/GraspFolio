package io.graspfolio.app

import android.app.UiAutomation
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReaderLoadingTest {
    @org.junit.Before fun initializeForegroundWindow() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity"
        )).use { it.readBytes() }
    }
    private fun eventually(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate()) { check(System.nanoTime() < end) { "Reader state timed out" }; Thread.sleep(25) }
    }
    private fun ink(node: View): StylusInkView? = if (node is StylusInkView) node else if (node is ViewGroup)
        (0 until node.childCount).firstNotNullOfOrNull { ink(node.getChildAt(it)) } else null
    private fun text(node: AccessibilityNodeInfo?, target: String): Boolean = node != null &&
        (node.text?.toString() == target || (0 until node.childCount).any { text(node.getChild(it), target) })

    @Test fun opensOnlyWhenCompleteAndTurnsWithoutDecodingInk() = verify(false)
    @Test fun backDuringLoadingDoesNotReopenDocument() = verify(true)
    private fun verify(cancel: Boolean) {
        val watchdog = Thread {
            try { Thread.sleep(12000) } catch (_: InterruptedException) { return@Thread }
            Thread.getAllStackTraces().filter { it.key.name == "main" || it.key.name.contains("Instr") }.forEach { (thread, stack) ->
                android.util.Log.w("ReaderFixture", "${thread.name}:\n${stack.joinToString("\n")}")
            }
        }.apply { isDaemon = true; start() }
        android.util.Log.i("ReaderFixture", "create PDF")
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val id = "reader-${UUID.randomUUID()}"
        val root = File(context.cacheDir, "storage-provider-tests/$id").apply { mkdirs() }
        val pdf = File(root, "test.pdf")
        val doc = PdfDocument()
        try {
            repeat(12) { page -> val p = doc.startPage(PdfDocument.PageInfo.Builder(600, 800, page + 1).create()); doc.finishPage(p) }
            pdf.outputStream().use(doc::writeTo)
        } finally { doc.close() }
        val uri = DocumentsContract.buildDocumentUri(StorageTestProvider.AUTHORITY, "$id/test.pdf")
        android.util.Log.i("ReaderFixture", "create journal")
        val state = File(root, "state.json")
        val strokes = (0 until 12).map { page -> InkStroke("p-$page", page,
            listOf(InkPoint(100f, 100f, .5f, 0), InkPoint(300f, 400f, .7f, 20)), width = 8f) }
        AnnotationJournal(state, "fixture").importRemote(strokes, "initial", ReadingProgress(0, false))
        val gate = CountDownLatch(1)
        val backend = object : AnnotationBackend {
            override fun openJournal(): AnnotationJournal { check(gate.await(30, TimeUnit.SECONDS)); return AnnotationJournal(state, "fixture") }
            override fun sync(folder: android.net.Uri, snapshot: DurableInk, allowLoad: Boolean) = error("No folder")
        }
        lateinit var store: AnnotationStore
        android.util.Log.i("ReaderFixture", "obtain store")
        inst.runOnMainSync { store = AnnotationStore.obtain(context, uri, backend) }
        var scenario: ActivityScenario<ReaderLoadingTestActivity>? = null
        var leaseReleased = false
        val automation = inst.uiAutomation
        try {
            android.util.Log.i("ReaderFixture", "rotate and launch")
            automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            scenario = ActivityScenario.launch(Intent(context, ReaderLoadingTestActivity::class.java).setData(uri))
            android.util.Log.i("ReaderFixture", "launched")
            val active = scenario
            inst.runOnMainSync { store.release() }; leaseReleased = true
            eventually { text(automation.rootInActiveWindow, "正在校验文档并恢复批注") }
            var nativePresent = false
            active.onActivity { nativePresent = ink(it.window.decorView) != null }
            assertFalse(nativePresent)
            automation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "reader-opening-progress.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            if (cancel) {
                active.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                eventually { active.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                gate.countDown()
                eventually { store.isClosed }
                inst.runOnMainSync { assertFalse(store.ready); assertTrue(store.strokes.isEmpty()) }
                return
            }
            gate.countDown()
            eventually { var enabled = false; active.onActivity { enabled = ink(it.window.decorView)?.enabledForWriting == true }; enabled }
            val reads = InkPerformance.decodedBlobReads.get()
            repeat(4) {
                var x = 0; var y = 0; var oldPages = emptyList<Int>()
                active.onActivity { activity ->
                    val view = checkNotNull(ink(activity.window.decorView)); val location = IntArray(2); view.getLocationOnScreen(location)
                    oldPages = view.placements.map { it.page }
                    val right = view.placements.maxBy { it.left + it.width * it.scale }
                    x = (location[0] + right.left + right.width * right.scale - 25).toInt()
                    y = (location[1] + right.top + 25).toInt()
                }
                val start = System.nanoTime()
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap $x $y")).use { it.readBytes() }
                eventually {
                    var done = false
                    active.onActivity { activity ->
                        ink(activity.window.decorView)?.let { view ->
                            // Even intermediate frames must pair placements with the same pages' ink.
                            assertEquals(view.placements.map { it.page }.toSet(), view.strokes.map { it.page }.toSet())
                            done = view.enabledForWriting && view.placements.map { it.page } != oldPages
                        }
                    }
                    done
                }
                android.util.Log.i("GraspFolioPerf", "reader_fixture_turn inputToReadyMs=${(System.nanoTime() - start) / 1_000_000.0}")
            }
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            eventually { var portrait = false; active.onActivity { portrait = ink(it.window.decorView)?.let { v -> v.enabledForWriting && v.placements.size == 1 } == true }; portrait }
            assertEquals(reads, InkPerformance.decodedBlobReads.get())
        } finally {
            watchdog.interrupt()
            gate.countDown(); scenario?.close()
            if (!leaseReleased) inst.runOnMainSync { store.release() }
            eventually { store.isClosed }
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            context.getSharedPreferences("reading_progress", 0).edit().remove("$uri:page").remove("$uri:cover").commit()
            root.deleteRecursively()
        }
    }
}
