package io.graspfolio.app

import android.net.Uri
import android.provider.DocumentsContract as DC
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryRepositoryTest {
    @Test fun recursiveScanRefreshAndRecentHistoryAreIndependentAndDurable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "library-${UUID.randomUUID()}"
        val root = File(context.cacheDir, "storage-provider-tests/$id").apply { mkdirs() }
        val prefs = context.getSharedPreferences(id, 0)
        val repository = LibraryRepository(context, id)
        val tree = DC.buildTreeDocumentUri(StorageTestProvider.AUTHORITY, id)
        val nested = File(root, "课程/教材").apply { mkdirs() }
        val a = File(root, "A.pdf").apply { writeText("test fixture, scanning only") }
        val b = File(nested, "B.PdF").apply { writeText("test fixture") }
        File(root, "notes.graspfolio").writeText("not a PDF")
        File(root, "audio.audio").writeText("not a PDF")
        fun uri(file: File) = DC.buildDocumentUriUsingTree(tree, "$id/${file.relativeTo(root).path}")
        try {
            repository.folder = tree
            assertEquals(tree, LibraryRepository(context, id).folder)
            val initial = repository.scan(tree)
            assertNull(initial.warning)
            assertEquals(setOf("A", "B"), initial.books.map { it.title }.toSet())
            assertTrue(repository.recent().isEmpty()) // Scanning is not opening.
            repository.recordOpened(uri(a)); repository.recordOpened(uri(b)); repository.recordOpened(uri(a))
            val restored = LibraryRepository(context, id).recent()
            assertEquals(listOf("A", "B"), restored.map { it.book.title })
            assertTrue(restored[0].opened > restored[1].opened)
            val direct = DC.buildDocumentUri(StorageTestProvider.AUTHORITY, "$id/A.pdf")
            assertEquals(libraryKey(direct), libraryKey(uri(a)))
            repository.recordOpened(direct)
            assertEquals(2, repository.recent().size)
            a.delete()
            File(nested, "C.pdf").writeText("new fixture")
            assertEquals(setOf("B", "C"), repository.scan(tree).books.map { it.title }.toSet())
            repository.folder = null
            assertNull(LibraryRepository(context, id).folder)
            assertTrue(b.exists())
            assertEquals(2, repository.recent().size)
            assertNotNull(repository.scan(Uri.parse("content://invalid/path")).warning)
        } finally { prefs.edit().clear().commit(); root.deleteRecursively() }
    }
}
