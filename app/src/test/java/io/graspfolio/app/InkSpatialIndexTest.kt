package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class InkSpatialIndexTest {
    private fun p(x: Float, y: Float) = InkPoint(x, y, .5f, 1)
    @Test fun indexedHitsMatchFullScanIncludingFastSweepsAndDots() {
        val random = Random(23)
        val strokes = (0..299).map { i -> InkStroke("$i", i % 2,
            List(if (i % 3 == 0) 1 else 6) { p(random.nextFloat() * 1000, random.nextFloat() * 1000) }, width = 1f + random.nextFloat() * 8) }
        val index = InkSpatialIndex(); index.sync(strokes)
        repeat(100) {
            val a = p(random.nextFloat() * 1000, random.nextFloat() * 1000)
            val b = if (it % 3 == 0) a else p(random.nextFloat() * 1000, random.nextFloat() * 1000)
            val page = it % 2; val radius = 12f
            val expected = strokes.filter { s -> s.page == page && strokeHit(s, a, b, radius) }.map { s -> s.id }
            val actual = index.candidates(page, a, b, radius).filter { s -> strokeHit(s, a, b, radius) }.map { s -> s.id }
            assertEquals(expected, actual)
        }
    }
    @Test fun nearbyQueryAvoidsThousandsOfUnrelatedStrokesAndUpdatesAfterErase() {
        val strokes = (0 until 3000).map { i -> val x = (i % 60) * 30f; val y = (i / 60) * 30f
            InkStroke("$i", 0, listOf(p(x, y), p(x + 5, y + 5))) }
        val index = InkSpatialIndex(); index.sync(strokes)
        val candidates = index.candidates(0, p(3f, 3f), p(5f, 5f), 12f)
        assertEquals(listOf("0"), candidates.map { it.id })
        index.sync(strokes.drop(1))
        assertTrue(index.candidates(0, p(3f, 3f), p(5f, 5f), 12f).isEmpty())
        index.sync(strokes)
        assertEquals(1, index.candidates(0, p(3f, 3f), p(5f, 5f), 12f).size)
    }
    @Test fun oversizedBoundsUseSafeFallbackAndPreservePageIsolation() {
        val stroke = InkStroke("long", 3, listOf(p(-1000000f, 0f), p(1000000f, 0f)))
        val index = InkSpatialIndex(); index.sync(listOf(stroke))
        assertEquals(listOf(stroke), index.candidates(3, p(0f, -2f), p(0f, 2f), 1f))
        assertTrue(index.candidates(2, p(0f, -2f), p(0f, 2f), 1f).isEmpty())
    }
}
