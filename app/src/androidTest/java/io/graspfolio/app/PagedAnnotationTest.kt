package io.graspfolio.app

import android.util.JsonReader
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PagedAnnotationTest {
    private fun fixture(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "paged-ink-${UUID.randomUUID()}")
        check(root.mkdir())
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun stroke(page: Int, points: Int = 8) = InkStroke("page-$page", page,
        (0 until points).map { InkPoint(it.toFloat(), page.toFloat(), .5f, it.toLong()) })

    @Test fun thousandPagesStayOnDiskAndWindowEditsPreserveOtherPagesAndSyncSnapshot() = fixture { root ->
        val file = File(root, "journal.json")
        // Generate lazily: the test itself also never allocates a book of point arrays.
        val source = object : AbstractList<InkStroke>() {
            override val size = 2000
            override fun get(index: Int) = stroke(index, 512)
        }
        val sidecar = File(root, "book.graspfolio")
        sidecar.bufferedWriter().use { StreamingInk.write(it, "pdf", source, ReadingProgress(1000, false), emptyList()) }
        val imported = JsonReader(sidecar.bufferedReader()).use { StreamingInk.read(it, "pdf", File(root, "import")) }
        assertTrue(imported.strokes is PagedInk)
        var journal = AnnotationJournal(file, "pdf")
        journal.importRemote(imported.strokes, "original", imported.progress)
        journal = AnnotationJournal(file, "pdf")
        assertTrue(journal.state.strokes is PagedInk)
        assertEquals(2000, journal.state.strokes.size)
        assertFalse(file.readText().contains("points"))
        val pages = setOf(999, 1000, 1001)
        val nearby = journal.window(pages)
        assertEquals(pages, nearby.map { it.page }.toSet())
        assertEquals(1536, nearby.sumOf { it.points.size })
        val oldSync = journal.state
        val changed = nearby.filterNot { it.page == 1000 } + stroke(1000, 17).copy(id = "replacement")
        journal.replacePages(pages, changed)
        journal.acknowledge(oldSync.revision, "original")
        assertTrue(journal.state.dirty)
        assertEquals(512, oldSync.strokes[1000].points.size)
        journal = AnnotationJournal(file, "pdf")
        assertEquals(changed, journal.window(pages))
        assertEquals(stroke(0, 512), journal.window(setOf(0)).single())
        assertEquals(stroke(1999, 512), journal.window(setOf(1999)).single())
        val exported = File(root, "export.graspfolio")
        exported.bufferedWriter().use { StreamingInk.write(it, "pdf", journal.state.strokes, journal.state.progress, journal.state.audio) }
        val roundTrip = JsonReader(exported.bufferedReader()).use { StreamingInk.read(it, "pdf", File(root, "round-trip")) }
        assertEquals(2000, roundTrip.strokes.size)
        assertEquals(changed, (roundTrip.strokes as PagedInk).window(pages))
        assertEquals(ReadingProgress(1000, false), roundTrip.progress)
        android.util.Log.i("GraspFolioPerf", "paged_fixture pages=2000 points=1024000 residentPoints=1536 sidecarBytes=${sidecar.length()} indexBytes=${file.length()}")
    }

    @Test fun reopeningDoesNotDecodeOffscreenPointsAndCorruptPageFailsWhenRequested() = fixture { root ->
        val file = File(root, "journal.json")
        val journal = AnnotationJournal(file, "pdf")
        journal.importRemote(listOf(stroke(0), stroke(800)), "hash")
        val far = (journal.state.strokes as PagedInk).refs.single { it.page == 800 }
        File((journal.state.strokes as PagedInk).directory, far.blob).writeText("corrupt offscreen payload")
        val reopened = AnnotationJournal(file, "pdf")
        assertEquals(listOf(stroke(0)), reopened.window(setOf(0, 1)))
        assertThrows(IllegalArgumentException::class.java) { reopened.window(setOf(800)) }
    }

    @Test fun oldEscapedCheckpointAndDeltaMigrateAndKeepReadingAndUnicodeIdentifiers() = fixture { root ->
        val file = File(root, "journal.json")
        val first = stroke(9).copy(id = "引号\"与反斜杠\\及换行\n")
        val data = InkCodec.encodeDocument("pdf", listOf(first), ReadingProgress(9, false))
        file.writeText(JSONObject().put("journalVersion", 3).put("sequence", 0).put("revision", 4)
            .put("syncedRevision", 4).put("dirty", false).put("base", "remote").put("data", data).toString())
        val records = File(root, "journal.json.journal").apply { mkdir() }
        File(records, "00000000000000000001.json").writeText(JSONObject().put("version", 3).put("document", "pdf")
            .put("sequence", 1).put("revision", 5).put("syncedRevision", 4).put("base", "remote")
            .put("removed", org.json.JSONArray()).put("added", InkCodec.encode("pdf", listOf(stroke(700)))).toString())
        val journal = AnnotationJournal(file, "pdf")
        assertEquals(listOf(first), journal.window(setOf(9)))
        assertEquals(listOf(stroke(700)), journal.window(setOf(700)))
        assertEquals(ReadingProgress(9, false), journal.state.progress)
        assertTrue(journal.state.dirty)
        assertEquals(4, JSONObject(file.readText()).getInt("journalVersion"))
        assertFalse(file.readText().contains("points"))
        assertEquals(journal.state, AnnotationJournal(file, "pdf").state)
    }
}
