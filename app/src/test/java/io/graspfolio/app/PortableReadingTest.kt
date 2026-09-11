package io.graspfolio.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableReadingTest {
    private val stroke = InkStroke("one", 7, listOf(InkPoint(1f, 2f, .5f, 10)))
    @Test fun versionTwoCarriesInkAndReadingSettings() {
        val reading = ReadingProgress(17, false)
        val text = InkCodec.encodeDocument("same-pdf", listOf(stroke), reading)
        assertEquals(2, JSONObject(text).getInt("version"))
        val imported = InkCodec.decodeDocument(text, "same-pdf")
        assertEquals(listOf(stroke), imported.strokes)
        assertEquals(reading, imported.progress)
        assertThrows(Exception::class.java) { InkCodec.decodeDocument(text, "different-pdf") }
    }
    @Test fun legacyDocumentStillLoadsWithoutInventingReadingProgress() {
        val imported = InkCodec.decodeDocument(InkCodec.encode("pdf", listOf(stroke)), "pdf")
        assertEquals(listOf(stroke), imported.strokes); assertNull(imported.progress)
    }
    @Test fun invalidMetadataOrFutureVersionIsRejected() {
        val root = JSONObject(InkCodec.encodeDocument("pdf", listOf(stroke), ReadingProgress()))
        for (bad in listOf(-1L, Int.MAX_VALUE.toLong() + 1)) {
            root.getJSONObject("reading").put("page", bad)
            assertThrows(Exception::class.java) { InkCodec.decodeDocument(root.toString(), "pdf") }
        }
        root.getJSONObject("reading").put("page", 0).put("cover", "false")
        assertThrows(Exception::class.java) { InkCodec.decodeDocument(root.toString(), "pdf") }
        root.put("version", 3)
        assertThrows(Exception::class.java) { InkCodec.decodeDocument(root.toString(), "pdf") }
    }
}
