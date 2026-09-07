package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test

class FrontInkSceneTest {
    private val page = PagePlacement(0, 10f, 10f, 80f, 80f, 1f)
    private fun point(x: Float, y: Float = 40f) = InkPoint(x, y, 1f, 1)
    @Test fun replacingPredictionRemovesOldTailButPreservesRealInk() {
        val scene = FrontInkScene()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            scene.begin(page); scene.append(listOf(point(10f), point(40f)), point(65f))
            scene.draw(canvas, 100, 100)
            assertNotEquals(0, bitmap.getPixel(70, 50))
            scene.append(listOf(point(45f)), point(45f, 60f)); scene.draw(canvas, 100, 100)
            assertEquals(0, bitmap.getPixel(70, 50))
            assertNotEquals(0, bitmap.getPixel(30, 50))
            scene.finish("a"); scene.draw(canvas, 100, 100)
            assertEquals(0, bitmap.getPixel(55, 65))
            assertNotEquals(0, bitmap.getPixel(30, 50))
        } finally { bitmap.recycle() }
    }
    @Test fun rapidStrokesSurviveUntilTheirOwnHandoffAndCancelOnlyDropsActive() {
        val scene = FrontInkScene()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            scene.begin(page); scene.append(listOf(point(20f)), null); scene.finish("a")
            scene.begin(page); scene.append(listOf(point(40f)), null); scene.finish("b")
            scene.begin(page); scene.append(listOf(point(60f)), null)
            scene.draw(canvas, 100, 100)
            scene.cancelActive(); scene.handoff(setOf("a")); scene.draw(canvas, 100, 100)
            assertEquals(1, scene.pendingCount)
            assertEquals(0, bitmap.getPixel(30, 50)); assertEquals(0, bitmap.getPixel(70, 50))
            assertNotEquals(0, bitmap.getPixel(50, 50))
            scene.handoff(setOf("b")); scene.draw(canvas, 100, 100)
            assertEquals(0, bitmap.getPixel(50, 50))
        } finally { bitmap.recycle() }
    }
    @Test fun pageClippingAndFullRedrawPreserveOnlyCurrentScene() {
        val scene = FrontInkScene()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            scene.begin(page); scene.append(listOf(point(-20f), point(100f)), null)
            scene.draw(canvas, 100, 100, true)
            assertEquals(0, bitmap.getPixel(5, 50)); assertEquals(0, bitmap.getPixel(95, 50))
            assertNotEquals(0, bitmap.getPixel(50, 50))
            scene.reset(); scene.draw(canvas, 100, 100, true)
            assertEquals(0, bitmap.getPixel(50, 50))
        } finally { bitmap.recycle() }
    }
}
