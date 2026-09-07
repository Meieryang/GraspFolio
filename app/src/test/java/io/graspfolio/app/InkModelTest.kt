package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class InkModelTest {
    private fun point(x: Float, y: Float, pressure: Float = .5f) = InkPoint(x, y, pressure, 123)
    @Test fun coordinatesSurvivePortraitAndSpread() {
        val portrait = PagePlacement(2, 0f, 100f, 600f, 800f, 1.5f)
        val spread = PagePlacement(2, 600f, 40f, 600f, 800f, .7f)
        val original = point(130f, 270f)
        for (page in listOf(portrait, spread)) {
            val restored = page.toPage(page.left + original.x * page.scale, page.top + original.y * page.scale, original.pressure, original.time)
            assertEquals(original.x, restored.x, .001f); assertEquals(original.y, restored.y, .001f)
        }
    }
    @Test fun blankSlotsAndMarginsAreNotPages() {
        val page = PagePlacement(0, 500f, 30f, 500f, 700f, 1f)
        assertFalse(page.contains(20f, 50f)); assertFalse(page.contains(600f, 10f)); assertTrue(page.contains(600f, 50f))
    }
    @Test fun pressureIsBoundedAndMonotonic() {
        assertTrue(pressureWidth(2f, .1f) < pressureWidth(2f, .9f))
        assertEquals(pressureWidth(2f, 0f), pressureWidth(2f, -1f), 0f)
        assertEquals(pressureWidth(2f, 1f), pressureWidth(2f, 2f), 0f)
    }
    @Test fun eraserPathIsClippedToTheStartingPage() {
        assertNull(clipToPage(point(110f, 20f), point(120f, 40f), 100f, 100f))
        val clipped = clipToPage(point(-10f, 50f), point(110f, 50f), 100f, 100f)!!
        assertEquals(0f, clipped.first.x, .001f)
        assertEquals(100f, clipped.second.x, .001f)
    }
    @Test fun fastEraserCrossingDeletesWholeStroke() {
        val line = InkStroke("a", 0, listOf(point(0f, 50f), point(100f, 50f)))
        assertTrue(strokeHit(line, point(50f, 0f), point(50f, 100f), 2f))
        assertFalse(strokeHit(line, point(150f, 0f), point(150f, 100f), 2f))
    }
    @Test fun eraserHandlesSinglePointsAndStationaryContact() {
        val dot = InkStroke("a", 0, listOf(point(50f, 50f)))
        assertTrue(strokeHit(dot, point(50f, 50f), point(50f, 50f), 2f))
        assertFalse(strokeHit(dot, point(60f, 60f), point(60f, 60f), 2f))
    }
    @Test fun codecRoundTripPreservesEveryField() {
        val data = listOf(InkStroke("unique", 5, listOf(point(1.25f, 8.5f), point(10f, 20f, .9f))))
        assertEquals(data, InkCodec.decode(InkCodec.encode("pdf-hash", data), "pdf-hash"))
        assertEquals(emptyList<InkStroke>(), InkCodec.decode(InkCodec.encode("pdf-hash", emptyList()), "pdf-hash"))
    }
    @Test fun differentPdfIdentityNeverLoadsSameNamedAnnotation() {
        assertThrows(IllegalArgumentException::class.java) { InkCodec.decode(InkCodec.encode("first-pdf", emptyList()), "second-pdf") }
    }
    @Test fun corruptedAndUnsupportedFilesAreRejected() {
        assertThrows(Exception::class.java) { InkCodec.decode("broken", "pdf") }
        assertThrows(IllegalArgumentException::class.java) { InkCodec.decode("{\"version\":2,\"document\":\"pdf\",\"strokes\":[]}", "pdf") }
    }
}
