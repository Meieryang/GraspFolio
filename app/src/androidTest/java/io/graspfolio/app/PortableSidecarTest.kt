package io.graspfolio.app

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PortableSidecarTest {
    @Test fun realProviderRenameAndNewDeviceImportPreserveInkAndReadingState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "storage-provider-tests/$id").apply { check(mkdirs()) }
        val pdf = File(root, "Book.pdf").apply { writeBytes("same immutable PDF bytes".toByteArray()) }
        val folder = DocumentsContract.buildTreeDocumentUri(StorageTestProvider.AUTHORITY, id)
        val uri = DocumentsContract.buildDocumentUriUsingTree(folder, "$id/Book.pdf")
        val identity = pdfDigest(context, uri)
        val sidecar = AnnotationSidecar(context, uri, identity)
        val ink = listOf(InkStroke("a", 0, listOf(InkPoint(1f, 2f, .5f, 10))))
        val v1 = InkCodec.encode(identity, ink).toByteArray()
        val side = File(root, "Book.graspfolio").apply { writeBytes(v1) }
        var state = DurableInk(ink, inkDigest(v1), 1, 0, ReadingProgress(27, false))
        try {
            val first = sidecar.sync(folder, state, false)
            assertEquals(first.hash, inkDigest(side.readBytes()))
            assertEquals(ReadingProgress(27, false), InkCodec.decodeDocument(side.readText(), identity).progress)
            assertTrue(root.listFiles()!!.any { it.name.contains(".backup-") })
            // Simulate process death before the local journal acknowledged a completed rename.
            val recovered = AnnotationSidecar(context, uri, identity).sync(folder, state, true)
            assertEquals(1L, recovered.acknowledgedRevision)
            state = state.acknowledged(1, recovered.hash)
            sidecar.sync(folder, state, true)
            assertEquals(setOf("Book.pdf", "Book.graspfolio"), root.listFiles()!!.map { it.name }.toSet())

            // A different folder/URI represents another device with no local journal or preferences.
            val destination = File(context.cacheDir, "storage-provider-tests/$id-copy").apply { check(mkdirs()) }
            try {
                pdf.copyTo(File(destination, "Book.pdf"))
                side.copyTo(File(destination, "Book.graspfolio"))
                val otherFolder = DocumentsContract.buildTreeDocumentUri(StorageTestProvider.AUTHORITY, "$id-copy")
                val otherUri = DocumentsContract.buildDocumentUriUsingTree(otherFolder, "$id-copy/Book.pdf")
                val imported = AnnotationSidecar(context, otherUri, identity).sync(otherFolder, DurableInk(), true)
                assertEquals(ink, imported.imported)
                assertEquals(ReadingProgress(27, false), imported.progress)
            } finally { destination.deleteRecursively() }
            val external = InkCodec.encodeDocument(identity, ink, ReadingProgress(99, true)).toByteArray()
            side.writeBytes(external)
            val localChanged = state.copy(revision = state.revision + 1, progress = ReadingProgress(30, true))
            assertThrows(Exception::class.java) { sidecar.sync(folder, localChanged, true) }
            assertArrayEquals(external, side.readBytes())
            assertEquals(ReadingProgress(30, true), localChanged.progress)
            assertArrayEquals("same immutable PDF bytes".toByteArray(), pdf.readBytes())
        } finally {
            root.deleteRecursively()
            File(context.filesDir, "sidecar-transactions/${inkDigest("$uri|$folder|$identity".toByteArray())}.json").delete()
        }
    }
}
