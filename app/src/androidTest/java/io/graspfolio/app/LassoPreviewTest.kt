package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test

class LassoPreviewTest {
    @Test fun largeViewportPreservesPixelsForStationaryAndMovedInk() {
        // Exceeds the previous 1.5M-pixel budget; thin strokes expose preview downsampling.
        val width = 2048; val height = 1600
        val page = PagePlacement(0, 13f, 17f, 1400f, 1100f, 1.3f)
        fun stroke(id: String, x: Float, y: Float, brush: String) = InkStroke(id, 0,
            List(70) { i -> InkPoint(x + i * .65f, y + (i % 9) * .7f, .2f + i % 6 * .1f, i.toLong()) },
            color = 0xff567896.toInt(), width = if (brush == "highlighter") 8f else .7f, brush = brush)
        val strokes = listOf(stroke("background", 100f, 150f, "fineliner"), stroke("marker", 200f, 190f, "highlighter"),
            stroke("selected", 900f, 600f, "pressure"))
        val preview = LassoPreview.build(width, height, listOf(page), strokes, setOf("selected"))
        assertTrue("Crop should avoid two full-screen bitmaps", preview.pixelCount < width.toLong() * height)
        val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val baseline = InkRasterCache()
        try {
            baseline.resize(width, height)
            for (shift in listOf(0f, 52f)) {
                baseline.clear(); expected.eraseColor(0); actual.eraseColor(0)
                strokes.forEach { s -> baseline.stroke(if (s.id == "selected") page.copy(left = page.left + shift, top = page.top + shift) else page, s) }
                baseline.show(Canvas(expected)); preview.draw(Canvas(actual), shift, shift)
                val a = IntArray(width * height); val b = IntArray(width * height)
                expected.getPixels(a, 0, width, 0, 0, width, height)
                actual.getPixels(b, 0, width, 0, 0, width, height)
                assertArrayEquals("Full-resolution preview changed pixels at translation $shift", a, b)
            }
        } finally { baseline.release(); preview.release(); expected.recycle(); actual.recycle() }
    }
    @Test fun emptyLayerAllocatesNoPixels() {
        val preview = LassoPreview.build(2048, 1600, emptyList(), emptyList(), emptySet())
        assertEquals(0L, preview.pixelCount)
        preview.release()
    }
}
