package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test

class EraserCacheTest {
    private fun p(x: Float, y: Float) = InkPoint(x, y, .8f, 1)
    @Test fun localEraseCancelAndCommitMatchFullRedrawWithOverlaps() {
        val pages = listOf(PagePlacement(0, 10f, 10f, 280f, 180f, 1f), PagePlacement(1, 310f, 10f, 280f, 180f, 1f))
        val strokes = (0 until 100).map { i -> val x = (i % 20) * 12f + 5; val y = (i / 20) * 25f + 10
            InkStroke("$i", 0, listOf(p(x, y), p(x + 5, y + 10))) } +
            InkStroke("cross", 0, listOf(p(0f, 15f), p(25f, 15f))) +
            InkStroke("other", 1, listOf(p(5f, 10f), p(10f, 20f)))
        val cache = CommittedInkCache(); val index = InkSpatialIndex()
        fun check(current: List<InkStroke>, hidden: Set<String>) {
            cache.update(600, 220, pages, current, hidden, index)
            val actual = Bitmap.createBitmap(600, 220, Bitmap.Config.ARGB_8888)
            val expected = Bitmap.createBitmap(600, 220, Bitmap.Config.ARGB_8888)
            val baseline = InkRasterCache()
            try {
                cache.show(Canvas(actual)); baseline.resize(600, 220)
                current.filter { it.id !in hidden }.forEach { stroke -> baseline.stroke(pages.first { it.page == stroke.page }, stroke) }
                baseline.show(Canvas(expected))
                val a = IntArray(600 * 220); val b = IntArray(a.size)
                actual.getPixels(a, 0, 600, 0, 0, 600, 220); expected.getPixels(b, 0, 600, 0, 0, 600, 220)
                assertArrayEquals(b, a)
            } finally { baseline.release(); actual.recycle(); expected.recycle() }
        }
        try {
            check(strokes, emptySet())
            check(strokes, setOf("0"))
            assertTrue("A small erasure must not rebuild the full viewport", cache.lastRepaintPixels in 1 until 600L * 220 / 10)
            check(strokes, emptySet()) // CANCEL restores the exact old pixels.
            check(strokes, setOf("0", "1"))
            check(strokes.filterNot { it.id == "0" || it.id == "1" }, emptySet()) // UP does not resurrect ink.
        } finally { cache.release() }
    }
    @Test fun layoutChangeRebuildsAtNativeResolution() {
        val cache = CommittedInkCache(); val index = InkSpatialIndex()
        val strokes = listOf(InkStroke("dot", 0, listOf(p(25f, 25f))))
        val a = listOf(PagePlacement(0, 0f, 0f, 100f, 100f, 1f))
        val b = listOf(PagePlacement(0, 100f, 0f, 100f, 100f, 1f))
        val output = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            cache.update(200, 100, a, strokes, emptySet(), index)
            cache.update(200, 100, b, strokes, emptySet(), index); cache.show(Canvas(output))
            assertEquals(0, output.getPixel(25, 25)); assertNotEquals(0, output.getPixel(125, 25))
        } finally { cache.release(); output.recycle() }
    }
}
