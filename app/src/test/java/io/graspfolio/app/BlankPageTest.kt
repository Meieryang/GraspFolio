package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class BlankPageTest {
    @Test fun insertionsPreserveOriginalIdsAndCanFollowOtherBlanks() {
        val one = ReadingProgress().insertAfter(1, 4)
        assertEquals(listOf(0, 1, 4, 2, 3), one.order(4))
        val two = one.insertAfter(2, 4)
        assertEquals(listOf(0, 1, 4, 5, 2, 3), two.order(4))
        assertEquals(3, two.page)
        assertEquals(two, readProgress(JSONObject().put("reading", two.toJson())))
        val three = two.insertAfter(5, 4)
        assertEquals(listOf(0, 1, 4, 5, 2, 3, 6), three.order(4))
    }
    @Test fun brokenStructureIsRejectedAndOldReadingStateStillLoads() {
        assertEquals(ReadingProgress(2, false), readProgress(JSONObject("{\"reading\":{\"page\":2,\"cover\":false}}")))
        assertThrows(IllegalArgumentException::class.java) { ReadingProgress(blanks = listOf(BlankPage(4, 99))).order(4) }
        assertThrows(IllegalArgumentException::class.java) { ReadingProgress(blanks = listOf(BlankPage(7, 1))).order(4) }
    }
    @Test fun metadataCodecRetainsBlankPagesAndExistingInk() {
        val progress = ReadingProgress().insertAfter(0, 2)
        val stroke = InkStroke("blank-ink", 2, listOf(InkPoint(1f, 2f, .5f, 10)))
        val text = InkCodec.encodeDocument("pdf", listOf(stroke), progress)
        assertEquals(progress, InkCodec.decodeDocument(text, "pdf").progress)
        assertEquals(stroke, InkCodec.decodeDocument(text, "pdf").strokes.single())
    }
}
