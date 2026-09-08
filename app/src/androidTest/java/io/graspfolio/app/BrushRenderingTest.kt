package io.graspfolio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class BrushRenderingTest {
    private val page = PagePlacement(0, 0f, 0f, 160f, 160f, 1f)
    private fun p(x: Float, y: Float) = InkPoint(x, y, .65f, 1)
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    @Test fun allBrushesMatchFrontAndCommittedRendering() {
        for (type in BrushStyle.types) {
            val style = BrushStyle(type, 0xff567896.toInt(), if (type == "highlighter") 16f else 3f)
            val points = listOf(p(20f, 20f), p(130f, 100f), p(20f, 100f), p(130f, 20f))
            val actual = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            val expected = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            val cache = InkRasterCache()
            try {
                cache.resize(160, 160); cache.stroke(page, InkStroke("a", 0, points, style.color, style.width, type)); cache.show(Canvas(actual))
                val scene = FrontInkScene().apply { begin(page, style); append(points, null) }
                scene.draw(Canvas(expected), 160, 160)
                assertArrayEquals(type, pixels(expected), pixels(actual))
            } finally { cache.release(); actual.recycle(); expected.recycle() }
        }
    }
    @Test fun highlighterSelfOverlapHasOneOpacityButSeparateStrokesAccumulate() {
        val cache = InkRasterCache(); val output = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val stroke = InkStroke("h", 0, listOf(p(20f, 80f), p(140f, 80f), p(20f, 80f)), 0xfff1ce58.toInt(), 16f, "highlighter")
        try {
            cache.resize(160, 160); cache.stroke(page, stroke); cache.show(Canvas(output))
            assertEquals(76, Color.alpha(output.getPixel(70, 80)))
            cache.stroke(page, stroke.copy(id = "another")); output.eraseColor(0); cache.show(Canvas(output))
            assertTrue(Color.alpha(output.getPixel(70, 80)) > 76)
        } finally { cache.release(); output.recycle() }
    }
    @Test fun translucentEraseRepairAndCancelMatchFullRedraw() {
        val strokes = listOf(
            InkStroke("pen", 0, listOf(p(5f, 70f), p(155f, 70f))),
            InkStroke("h", 0, listOf(p(30f, 65f), p(100f, 65f), p(30f, 65f)), 0xfff1ce58.toInt(), 20f, "highlighter"),
            InkStroke("blue", 0, listOf(p(15f, 30f), p(120f, 100f)), 0xff567896.toInt(), 3f, "fineliner"))
        val cache = CommittedInkCache(); val index = InkSpatialIndex()
        try {
            for (hidden in listOf(emptySet(), setOf("pen"), emptySet(), setOf("h"))) {
                cache.update(160, 160, listOf(page), strokes, hidden, index)
                val actual = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
                val expected = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
                val baseline = InkRasterCache()
                try {
                    cache.show(Canvas(actual)); baseline.resize(160, 160)
                    strokes.filter { it.id !in hidden }.forEach { baseline.stroke(page, it) }; baseline.show(Canvas(expected))
                    assertArrayEquals(pixels(expected), pixels(actual))
                } finally { baseline.release(); actual.recycle(); expected.recycle() }
            }
        } finally { cache.release() }
    }
    @Test fun brushIsCapturedAtDownAndFallbackMatchesCommittedOpacity() = withNativeInkTestHost { activity ->
        val view = StylusInkView(activity).apply {
            enabledForWriting = true; predictionEnabled = false; frontBufferEnabled = false
            placements = listOf(page); layout(0, 0, 160, 160)
            brushStyle = BrushStyle("highlighter", 0xfff1ce58.toInt(), 16f)
        }
        fun send(action: Int, x: Float) {
            val property = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; y = 80f; pressure = .65f }
            val event = MotionEvent.obtain(0, 10, action, 1, arrayOf(property), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        val live = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val completed = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        try {
            send(MotionEvent.ACTION_DOWN, 20f); send(MotionEvent.ACTION_MOVE, 140f); send(MotionEvent.ACTION_MOVE, 20f)
            view.draw(Canvas(live))
            view.brushStyle = BrushStyle("fineliner", 0xff567896.toInt(), 1f)
            send(MotionEvent.ACTION_UP, 20f); view.draw(Canvas(completed))
            assertEquals("highlighter", view.strokes.single().brush)
            assertEquals(16f, view.strokes.single().width, 0f)
            assertEquals(76, Color.alpha(live.getPixel(70, 80)))
            assertArrayEquals(pixels(live), pixels(completed))
        } finally { view.cancelStroke(); live.recycle(); completed.recycle() }
    }
}
