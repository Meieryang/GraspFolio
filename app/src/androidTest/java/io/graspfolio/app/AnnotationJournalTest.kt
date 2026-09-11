package io.graspfolio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AnnotationJournalTest {
    private fun stroke(id: String) = InkStroke(id, 0, listOf(InkPoint(1f, 2f, .5f, 10)))
    private fun inDirectory(block: (File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "journal-test-" + UUID.randomUUID())
        check(directory.mkdir())
        try { block(File(directory, "recovery.json")) } finally { directory.deleteRecursively() }
    }
    @Test fun editsEraseAndOldAcknowledgementRecoverWithoutLosingNewInk() = inDirectory { file ->
        val journal = AnnotationJournal(file, "pdf")
        val a = stroke("a"); val b = stroke("b")
        journal.replace(listOf(a))
        val syncingRevision = journal.state.revision
        journal.replace(listOf(a, b))
        journal.acknowledge(syncingRevision, "remote-old")
        var restored = AnnotationJournal(file, "pdf")
        assertEquals(listOf(a, b), restored.state.strokes); assertTrue(restored.state.dirty)
        restored.replace(listOf(b))
        restored.acknowledge(restored.state.revision, "remote-new")
        restored = AnnotationJournal(file, "pdf")
        assertEquals(listOf(b), restored.state.strokes); assertFalse(restored.state.dirty)
        assertEquals("remote-new", restored.state.base)
    }
    @Test fun legacyCheckpointMigratesWithoutChangingOriginalData() = inDirectory { file ->
        val a = stroke("legacy")
        val original = JSONObject().put("data", InkCodec.encode("pdf", listOf(a))).put("base", "known").put("dirty", false).toString()
        file.writeText(original)
        val journal = AnnotationJournal(file, "pdf")
        journal.replace(listOf(a, stroke("new")))
        assertEquals(original, file.readText())
        val restored = AnnotationJournal(file, "pdf")
        assertEquals(2, restored.state.strokes.size); assertTrue(restored.state.dirty)
        assertEquals("known", restored.state.base)
    }
    @Test fun compactionAndRemoteImportAreRecoverable() = inDirectory { file ->
        var journal = AnnotationJournal(file, "pdf")
        journal.replace(listOf(stroke("a"))); journal.compact()
        journal = AnnotationJournal(file, "pdf")
        assertEquals("a", journal.state.strokes.single().id)
        journal.importRemote(listOf(stroke("imported")), "hash")
        journal = AnnotationJournal(file, "pdf")
        assertFalse(journal.state.dirty); assertEquals("imported", journal.state.strokes.single().id)
        journal.replace(emptyList())
        assertTrue(AnnotationJournal(file, "pdf").state.strokes.isEmpty())
    }
    @Test fun corruptOrWrongIdentityRecordsAreNotOverwritten() = inDirectory { file ->
        val journal = AnnotationJournal(file, "pdf")
        journal.replace(listOf(stroke("a")))
        assertThrows(IllegalArgumentException::class.java) { AnnotationJournal(file, "different-pdf") }
        val record = File(file.parentFile, file.name + ".journal").listFiles()!!.single { it.name.endsWith(".json") }
        record.writeText("broken")
        assertThrows(Exception::class.java) { AnnotationJournal(file, "pdf") }
        assertEquals("broken", record.readText())
    }
    @Test fun failedLocalWriteDoesNotAdvanceDurableState() = inDirectory { file ->
        val journal = AnnotationJournal(file, "pdf")
        File(file.parentFile, file.name + ".journal").writeText("not a directory")
        assertThrows(Exception::class.java) { journal.replace(listOf(stroke("a"))) }
        assertEquals(0L, journal.state.revision); assertTrue(journal.state.strokes.isEmpty())
    }
    @Test fun deltaSizeDoesNotGrowWithExistingPointCount() = inDirectory { file ->
        val many = InkStroke("large", 0, (1..10000).map { InkPoint(it.toFloat(), 2f, .5f, it.toLong()) })
        val journal = AnnotationJournal(file, "pdf")
        journal.replace(listOf(many))
        val bytes = journal.replace(listOf(many, stroke("small")))
        assertTrue("One small edit should not reserialize the large stroke: $bytes", bytes < 1024)
        assertEquals(10000, AnnotationJournal(file, "pdf").state.strokes.first().points.size)
    }

    @Test fun readingOnlyEditsRecoverAndOldAcknowledgementDoesNotLoseNewPosition() = inDirectory { file ->
        val journal = AnnotationJournal(file, "pdf")
        journal.replace(listOf(stroke("a")), ReadingProgress(7, false))
        val old = journal.state.revision
        journal.replace(journal.state.strokes, ReadingProgress(19, true))
        journal.acknowledge(old, "remote-old")
        var restored = AnnotationJournal(file, "pdf")
        assertEquals(ReadingProgress(19, true), restored.state.progress)
        assertTrue(restored.state.dirty)
        restored.compact()
        restored = AnnotationJournal(file, "pdf")
        assertEquals(ReadingProgress(19, true), restored.state.progress)
        restored.importRemote(listOf(stroke("remote")), "remote-new", ReadingProgress(23, false))
        restored = AnnotationJournal(file, "pdf")
        assertEquals(ReadingProgress(23, false), restored.state.progress)
        assertFalse(restored.state.dirty)
    }
}
