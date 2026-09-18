package io.graspfolio.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class SidecarBundleTest {
    @Test fun audioAndBlankPagesTravelInOneFileAndRestoreWithoutExternalAttachments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = AudioAssets(context)
        val folder = File(context.cacheDir, "bundle-test-${UUID.randomUUID()}").apply { mkdirs() }
        val bytes = UUID.randomUUID().toString().repeat(100000).toByteArray()
        val note = AudioNote("one", 2, "test audio", inkDigest(bytes), 1000, bytes.size.toLong())
        val source = assets.file(note)
        try {
            source.writeBytes(bytes)
            val progress = ReadingProgress().insertAfter(0, 2)
            val stroke = InkStroke("ink", 2, listOf(InkPoint(1f, 2f, 1f, 1)))
            val bundle = File(folder, "book.graspfolio")
            bundle.outputStream().use { SidecarBundle.write(it, "pdf", InkDocument(listOf(stroke), progress, listOf(note, note.copy(id = "two"))), assets) }
            ZipFile(bundle).use { assertEquals(2, it.size()) }
            source.delete()
            val decoded = bundle.inputStream().use { SidecarBundle.read(it, "pdf", File(folder, "blobs"), assets) }
            assertEquals(listOf(stroke), decoded.strokes)
            assertEquals(progress, decoded.progress)
            assertEquals(2, decoded.audio.size)
            assertArrayEquals(bytes, source.readBytes())
            assertThrows(IllegalArgumentException::class.java) { bundle.inputStream().use { SidecarBundle.read(it, "wrong-pdf", File(folder, "wrong"), assets) } }
        } finally { source.delete(); folder.deleteRecursively() }
    }
}
