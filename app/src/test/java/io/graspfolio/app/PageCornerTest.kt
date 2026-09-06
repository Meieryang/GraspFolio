package io.graspfolio.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class PageCornerTest {
    @Test fun squarePageHasCornersBelowPortraitScreenTop() {
        val bounds = fittedPageBounds(Size(1000f, 1600f), Size(500f, 500f))
        assertEquals(Rect(0f, 300f, 1000f, 1300f), bounds)
        assertEquals(-1, cornerDirection(Offset(10f, 310f), bounds, 92f))
        assertEquals(1, cornerDirection(Offset(990f, 310f), bounds, 92f))
        assertEquals(0, cornerDirection(Offset(990f, 10f), bounds, 92f))
        assertEquals(0, cornerDirection(Offset(10f, 1310f), bounds, 92f))
    }

    @Test fun portraitPageHasInsetCornersOnWideScreen() {
        val bounds = fittedPageBounds(Size(1600f, 1000f), Size(500f, 1000f))
        assertEquals(Rect(550f, 0f, 1050f, 1000f), bounds)
        assertEquals(-1, cornerDirection(Offset(560f, 10f), bounds, 92f))
        assertEquals(1, cornerDirection(Offset(1040f, 10f), bounds, 92f))
        assertEquals(0, cornerDirection(Offset(1590f, 10f), bounds, 92f))
        assertEquals(0, cornerDirection(Offset(800f, 500f), bounds, 92f))
    }
}
