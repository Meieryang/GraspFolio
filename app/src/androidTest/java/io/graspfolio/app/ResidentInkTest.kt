package io.graspfolio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ResidentInkTest {
    @org.junit.Before fun foregroundBenchmark() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W -n io.graspfolio.app/.ReaderToolsPreviewActivity"
        )).use { it.readBytes() }
    }
    @Test fun fullBookLoadsOnceAndEverySubsequentPageLookupUsesMemory() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "resident-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val journal = AnnotationJournal(File(root, "state.json"), "pdf")
            val source = object : AbstractList<InkStroke>() {
                override val size = 2000
                override fun get(index: Int) = InkStroke("p-$index", index, (0 until 512).map { InkPoint(it.toFloat(), 10f, .5f, it.toLong()) })
            }
            journal.importRemote(source, "original")
            val marks = mutableListOf<DocumentLoading>()
            val started = System.nanoTime()
            var peakHeap = 0L
            val runtime = Runtime.getRuntime()
            val beforeHeap = runtime.totalMemory() - runtime.freeMemory()
            val cached = ResidentInk.load(journal.state.strokes, onProgress = {
                marks += it
                peakHeap = maxOf(peakHeap, runtime.totalMemory() - runtime.freeMemory())
            })
            val loadedMs = (System.nanoTime() - started) / 1_000_000.0
            assertEquals(0, marks.first().completed)
            assertEquals(2000, marks.last().completed)
            assertTrue(marks.zipWithNext().all { it.first.completed <= it.second.completed })
            val reads = InkPerformance.decodedBlobReads.get()
            val hotStart = System.nanoTime()
            repeat(400) { i ->
                val page = (i * 97) % 1999
                assertEquals(1024, cached.window(setOf(page, page + 1)).sumOf { it.points.size })
            }
            val hotMs = (System.nanoTime() - hotStart) / 1_000_000.0
            assertEquals(reads, InkPerformance.decodedBlobReads.get())
            val edited = cached.window(setOf(600)).single().copy(color = 0xff567896.toInt())
            cached.replace(setOf(600), listOf(edited))
            cached.replace(setOf(1999), emptyList())
            journal.replacePages(setOf(600), listOf(edited))
            journal.replacePages(setOf(1999), emptyList())
            assertEquals(edited, cached.window(setOf(600)).single())
            val recovered = AnnotationJournal(File(root, "state.json"), "pdf")
            assertEquals(edited, recovered.window(setOf(600)).single())
            assertTrue(recovered.window(setOf(1999)).isEmpty())
            assertEquals(512, recovered.window(setOf(0)).single().points.size)
            android.util.Log.i("GraspFolioPerf", "resident_benchmark pages=2000 points=1024000 preloadMs=$loadedMs hot400Ms=$hotMs heapBefore=$beforeHeap sampledPeakHeap=$peakHeap")
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationAndInsufficientMemoryNeverPublishPartialBooks() {
        val source = (0 until 10).map { InkStroke("$it", it, listOf(InkPoint(1f, 1f, 1f, 0))) }
        assertThrows(java.util.concurrent.CancellationException::class.java) { ResidentInk.load(source, cancelled = { true }) }
        assertThrows(InsufficientInkMemory::class.java) { ResidentInk.load(source, memoryAvailable = { false }) }
        assertEquals(1f, DocumentLoading("empty", 0, 0).fraction)
        assertNull(DocumentLoading("checking").fraction)
        assertTrue(ResidentInk.load(emptyList()).window(setOf(99)).isEmpty())
    }
}
