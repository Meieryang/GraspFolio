package io.graspfolio.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class PageCornerTest {
    @Test fun menuEnlargesBothDimensionsWithoutActivatingSpine() {
        val bounds = Rect(0f, 0f, 1000f, 800f)
        for ((point, direction) in listOf(Offset(85f, 85f) to -1, Offset(915f, 85f) to 1)) {
            assertEquals(0, cornerDirection(point, bounds, PageCornerSizeDp))
            assertEquals(direction, cornerDirection(point, bounds, PageCornerSizeDp * 1.5f))
        }
        assertEquals(0, cornerDirection(Offset(500f, 10f), bounds, 138f))
        assertEquals(0, cornerDirection(Offset(49f, 1f), Rect(0f, 0f, 100f, 80f), 138f))
    }

    @Test fun reducedOuterBoundaryDoesNotKeepOldHitArea() {
        val bounds = Rect(0f, 0f, 1000f, 800f)
        assertEquals(-1, cornerDirection(Offset(68f, 68f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(70f, 70f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(105f, 105f), bounds, PageCornerSizeDp * 1.5f))
    }

    @Test fun squarePageHasCornersBelowPortraitScreenTop() {
        val bounds = fittedPageBounds(Size(1000f, 1600f), Size(500f, 500f))
        assertEquals(Rect(0f, 300f, 1000f, 1300f), bounds)
        assertEquals(-1, cornerDirection(Offset(10f, 310f), bounds, PageCornerSizeDp))
        assertEquals(1, cornerDirection(Offset(990f, 310f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(990f, 10f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(10f, 1310f), bounds, PageCornerSizeDp))
    }

    @Test fun portraitPageHasInsetCornersOnWideScreen() {
        val bounds = fittedPageBounds(Size(1600f, 1000f), Size(500f, 1000f))
        assertEquals(Rect(550f, 0f, 1050f, 1000f), bounds)
        assertEquals(-1, cornerDirection(Offset(560f, 10f), bounds, PageCornerSizeDp))
        assertEquals(1, cornerDirection(Offset(1040f, 10f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(1590f, 10f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(800f, 500f), bounds, PageCornerSizeDp))
    }

    @Test fun spreadHasOnlyOuterCornersIncludingBlankCoverAndEndSlots() {
        // Identical spread bounds for two pages, blank+cover, and last-page+blank.
        val bounds = Rect(100f, 50f, 1100f, 850f)
        assertEquals(-1, cornerDirection(Offset(110f, 60f), bounds, PageCornerSizeDp))
        assertEquals(1, cornerDirection(Offset(1090f, 60f), bounds, PageCornerSizeDp))
        for (x in listOf(510f, 590f, 600f, 610f, 690f))
            assertEquals(0, cornerDirection(Offset(x, 60f), bounds, PageCornerSizeDp))
    }

    @Test fun pendingPressCancelsOnExitOrDragButAllowsSmallJitter() {
        val bounds = Rect(0f, 0f, 1000f, 800f)
        val origin = Offset(990f, 10f)
        assertEquals(true, cornerPressRemainsValid(origin, Offset(988f, 12f), bounds, 92f, 1, 8f))
        assertEquals(false, cornerPressRemainsValid(origin, Offset(1001f, 10f), bounds, 92f, 1, 20f))
        assertEquals(false, cornerPressRemainsValid(origin, Offset(970f, 10f), bounds, 92f, 1, 8f))
        assertEquals(false, cornerPressRemainsValid(origin, Offset(900f, 10f), bounds, 92f, 1, 200f))
        assertEquals(false, cornerPressRemainsValid(origin, Offset(10f, 10f), bounds, 92f, 1, 2000f))
    }

    @Test fun narrowSpreadStillKeepsSpineInactive() {
        val bounds = Rect(0f, 0f, 100f, 80f)
        assertEquals(-1, cornerDirection(Offset(1f, 1f), bounds, PageCornerSizeDp))
        assertEquals(1, cornerDirection(Offset(99f, 1f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(49f, 1f), bounds, PageCornerSizeDp))
        assertEquals(0, cornerDirection(Offset(51f, 1f), bounds, PageCornerSizeDp))
    }
}
