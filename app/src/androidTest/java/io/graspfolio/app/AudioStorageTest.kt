package io.graspfolio.app

import android.provider.DocumentsContract as DC
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal fun testAudioWav(seconds: Int = 2): ByteArray {
    val size = 8000 * seconds * 2
    return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(size)
        // Unique, effectively silent fixture; never shares the hash of a user's audio.
        put(UUID.randomUUID().toString().toByteArray())
    }.array()
}

class AudioStorageTest {
    @Test fun importedCopySurvivesOriginalRemovalAndMigratesWithSidecar() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "storage-provider-tests/$id").apply { check(mkdirs()) }
        val folder = DC.buildTreeDocumentUri(StorageTestProvider.AUTHORITY, id)
        val source = File(root, "lesson.wav").apply { writeBytes(testAudioWav()) }
        val sourceUri = DC.buildDocumentUriUsingTree(folder, "$id/lesson.wav")
        val pdf = File(root, "Book.pdf").apply { writeText("immutable test PDF $id") }
        val pdfUri = DC.buildDocumentUriUsingTree(folder, "$id/Book.pdf")
        val assets = AudioAssets(context)
        var note: AudioNote? = null
        val destination = File(context.cacheDir, "storage-provider-tests/$id-copy").apply { check(mkdirs()) }
        try {
            val imported = assets.import(sourceUri, 5); note = imported
            assertTrue(imported.durationMs in 1900..2100)
            val bytes = source.readBytes()
            check(source.delete())
            assertArrayEquals(bytes, assets.file(imported).readBytes())
            val identity = pdfDigest(context, pdfUri)
            val snapshot = DurableInk(revision = 1, progress = ReadingProgress(5), audio = listOf(imported))
            val sidecar = AnnotationSidecar(context, pdfUri, identity)
            val synced = sidecar.sync(folder, snapshot, false)
            val side = File(root, "Book.graspfolio")
            assertEquals(listOf(imported), InkCodec.decodeDocument(side.readText(), identity).audio)
            assertTrue(side.length() < 2048) // Audio bytes are not serialized into the ink JSON.
            root.listFiles()!!.filter { it.name == "Book.pdf" || it.name == "Book.graspfolio" || it.name.endsWith(".audio") }
                .forEach { it.copyTo(File(destination, it.name)) }
            check(assets.file(imported).delete())
            val otherFolder = DC.buildTreeDocumentUri(StorageTestProvider.AUTHORITY, "$id-copy")
            val otherPdf = DC.buildDocumentUriUsingTree(otherFolder, "$id-copy/Book.pdf")
            val restored = AnnotationSidecar(context, otherPdf, identity).sync(otherFolder, DurableInk(), true)
            assertEquals(listOf(imported), restored.audio)
            assertArrayEquals(bytes, assets.file(imported).readBytes())
            val journal = AnnotationJournal(File(root, "local.json"), identity)
            journal.importRemote(emptyList(), synced.hash, snapshot.progress, listOf(imported))
            journal.replace(listOf(InkStroke("ink", 5, listOf(InkPoint(1f, 1f, .5f, 1)))))
            assertEquals(listOf(imported), AnnotationJournal(File(root, "local.json"), identity).state.audio)
            journal.replace(journal.state.strokes, audio = emptyList())
            journal.compact()
            assertTrue(AnnotationJournal(File(root, "local.json"), identity).state.audio.isEmpty())
            check(assets.file(imported).delete())
            destination.listFiles()!!.single { it.name.endsWith(".audio") }.writeText("corrupt")
            assertThrows(Exception::class.java) { AnnotationSidecar(context, otherPdf, identity).sync(otherFolder, DurableInk(), true) }
            assertFalse(assets.file(imported).exists())
        } finally {
            note?.let { assets.file(it).delete() }
            root.deleteRecursively(); destination.deleteRecursively()
            val identity = inkDigest("immutable test PDF $id".toByteArray())
            File(context.filesDir, "sidecar-transactions/${inkDigest("$pdfUri|$folder|$identity".toByteArray())}.json").delete()
        }
    }
    @Test fun invalidAudioDoesNotCreateAReference() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "storage-provider-tests/$id").apply { check(mkdirs()) }
        val folder = DC.buildTreeDocumentUri(StorageTestProvider.AUTHORITY, id)
        File(root, "broken.wav").writeText("not an audio file")
        try {
            try { AudioAssets(context).import(DC.buildDocumentUriUsingTree(folder, "$id/broken.wav"), 0); fail("Invalid audio accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
}
