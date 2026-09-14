package io.graspfolio.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioNoteTest {
    private val first = AudioNote("one", 5, "课堂.mp3", "a".repeat(64), 192000, 100)
    private val second = first.copy(id = "two", page = 6)
    @Test fun audioAndPageOrderRoundTripWithInkAndReadingState() {
        val ink = listOf(InkStroke("ink", 5, listOf(InkPoint(1f, 2f, .5f, 1))))
        val text = InkCodec.encodeDocument("pdf", ink, ReadingProgress(5, false), listOf(second, first))
        assertEquals(3, JSONObject(text).getInt("version"))
        val restored = InkCodec.decodeDocument(text, "pdf")
        assertEquals(ink, restored.strokes)
        assertEquals(listOf(second, first), restored.audio)
        assertEquals(listOf(first), pageAudio(restored.audio, listOf(5)))
        assertEquals(listOf(second, first), pageAudio(restored.audio, listOf(5, 6)))
        assertTrue(pageAudio(restored.audio, listOf(0)).isEmpty())
        assertThrows(Exception::class.java) { InkCodec.decodeDocument(text, "another-pdf") }
    }
    @Test fun legacyDocumentsRemainReadableWithoutAudio() {
        assertTrue(InkCodec.decodeDocument(InkCodec.encode("pdf", emptyList()), "pdf").audio.isEmpty())
        assertEquals(2, JSONObject(InkCodec.encodeDocument("pdf", emptyList(), null)).getInt("version"))
    }
    @Test fun malformedPathsDuplicateIdsAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { first.copy(hash = "../../a") }
        assertThrows(IllegalArgumentException::class.java) { first.copy(bytes = MAX_AUDIO_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { first.copy(durationMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { readAudio(audioJson(listOf(first, first))) }
        val item = first.toJson().put("page", Int.MAX_VALUE.toLong() + 1)
        assertThrows(IllegalArgumentException::class.java) { readAudio(JSONArray().put(item)) }
    }
    @Test fun formatsElapsedTimeAndHours() {
        assertEquals("0:00", audioTime(-100))
        assertEquals("3:12", audioTime(192000))
        assertEquals("1:01:01", audioTime(3661000))
    }
}
