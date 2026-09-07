package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class InkSharpnessTest {
    @Test fun completedCacheMatchesLiveInkAboveFourMillionPixels() {
        val width = 2100; val height = 2000
        val page = PagePlacement(0, 0f, 0f, width.toFloat(), height.toFloat(), 1f)
        val points = listOf(InkPoint(1000.25f, 1000.75f, .3f, 1), InkPoint(1070.5f, 1060.25f, .9f, 2))
        val cache = InkRasterCache()
        val actual = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try {
            cache.resize(width, height)
            cache.stroke(page, InkStroke("a", 0, points))
            val output = Canvas(actual).apply { translate(-980f, -980f) }
            cache.show(output)
            val scene = FrontInkScene().apply { begin(page); append(points, null) }
            scene.draw(Canvas(expected).apply { translate(-980f, -980f) }, width, height)
            val actualPixels = IntArray(120 * 120); val expectedPixels = IntArray(120 * 120)
            actual.getPixels(actualPixels, 0, 120, 0, 0, 120, 120)
            expected.getPixels(expectedPixels, 0, 120, 0, 0, 120, 120)
            assertArrayEquals("Completed ink must not be downsampled and enlarged", expectedPixels, actualPixels)
        } finally { cache.release(); actual.recycle(); expected.recycle() }
    }
}
