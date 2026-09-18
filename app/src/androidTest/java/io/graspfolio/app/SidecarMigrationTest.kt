package io.graspfolio.app

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class SidecarMigrationTest {
    @Test fun legacyAudioMigratesAtomicallyAndNewDeviceNeedsOnlyBundle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "bundle-migration-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, "storage-provider-tests/$id").apply { mkdirs() }
        val bytes = UUID.randomUUID().toString().repeat(100).toByteArray()
        val note = AudioNote("audio", 0, "audio", inkDigest(bytes), 1000, bytes.size.toLong())
        val assets = AudioAssets(context)
        try {
            File(directory, "book.pdf").writeText("fixture-${UUID.randomUUID()}")
            val identity = inkDigest(File(directory, "book.pdf").readBytes())
            val stroke = InkStroke("a", 0, listOf(InkPoint(2f, 3f, .5f, 4)))
            val external = File(directory, "graspfolio-audio-${note.hash}.audio").apply { writeBytes(bytes) }
            val canonical = File(directory, "book.graspfolio").apply { writeText(InkCodec.encodeDocument(identity, listOf(stroke), ReadingProgress(), listOf(note))) }
            val folder = DocumentsContract.buildTreeDocumentUri(StorageTestProvider.AUTHORITY, id)
            val pdf = DocumentsContract.buildDocumentUriUsingTree(folder, "$id/book.pdf")
            val sidecar = AnnotationSidecar(context, pdf, identity, File(directory, "blobs"))
            val journal = AnnotationJournal(File(directory, "local.json"), identity)
            val imported = sidecar.sync(folder, journal.state, true)
            assertTrue(imported.needsUpgrade)
            journal.importRemote(imported.imported!!, imported.hash, imported.progress, imported.audio)
            val saved = sidecar.sync(folder, journal.state, true)
            journal.acknowledge(journal.state.revision, saved.hash)
            sidecar.afterAcknowledged(folder, journal.state)
            ZipFile(canonical).use { assertNotNull(it.getEntry("audio/${note.hash}.audio")) }
            assertFalse(external.exists())
            assertFalse(directory.listFiles()!!.any { it.name.contains(".graspfolio.backup-") })
            assets.file(note).delete()
            val other = AnnotationSidecar(context, pdf, identity, File(directory, "other-blobs"))
            val loaded = other.sync(folder, DurableInk(), true)
            assertEquals(listOf(stroke), loaded.imported)
            assertEquals(listOf(note), loaded.audio)
            assertArrayEquals(bytes, assets.file(note).readBytes())
        } finally {
            assets.file(note).delete()
            directory.deleteRecursively()
        }
    }
}
