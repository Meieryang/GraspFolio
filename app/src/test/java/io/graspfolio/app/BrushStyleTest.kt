package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class BrushStyleTest {
    @Test fun onlyPressurePenChangesWidthWithPressure() {
        val pen = BrushStyle(width = 4f)
        assertTrue(pen.widthAt(0f) < pen.widthAt(1f))
        for (type in listOf("fineliner", "highlighter")) {
            val style = BrushStyle(type, width = 12f)
            assertEquals(12f, style.widthAt(0f), 0f)
            assertEquals(12f, style.widthAt(1f), 0f)
        }
    }
    @Test fun mixedBrushesRoundTripAndLegacyPressureSurvives() {
        val strokes = BrushStyle.types.mapIndexed { i, type -> InkStroke("$i", i,
            listOf(InkPoint(25.5f, 35f, .4f, 7)), 0xff567896.toInt(), BrushStyle.defaultWidth(type), type) }
        assertEquals(strokes, InkCodec.decode(InkCodec.encode("doc", strokes), "doc"))
        val old = """{"version":1,"document":"doc","strokes":[{"id":"old","page":0,"color":-15658735,"width":2,"brush":"pressure","points":[[2,3,0.5,1]]}]}"""
        assertEquals("pressure", InkCodec.decode(old, "doc").single().brush)
    }
    @Test fun unknownBrushAndWrongDocumentAreRejectedWithoutDiscardingData() {
        val stroke = InkStroke("a", 0, listOf(InkPoint(1f, 2f, .5f, 1)))
        val text = InkCodec.encode("doc", listOf(stroke))
        assertThrows(IllegalArgumentException::class.java) { InkCodec.decode(text.replace("pressure", "future-brush"), "doc") }
        assertThrows(IllegalArgumentException::class.java) { InkCodec.decode(text, "another-doc") }
    }
    @Test fun highlighterBoundsAndFastEraseIncludeItsBroadEdge() {
        val stroke = InkStroke("h", 0, listOf(InkPoint(20f, 30f, 0f, 1), InkPoint(70f, 30f, 0f, 2)), width = 24f, brush = "highlighter")
        val point = InkPoint(40f, 40f, 0f, 3)
        assertTrue(strokeHit(stroke, point, point, 0f))
        val index = InkSpatialIndex().apply { sync(listOf(stroke)) }
        assertEquals(listOf(stroke), index.candidates(0, point, point, 0f))
    }
}
