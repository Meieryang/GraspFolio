package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class PredictionTailTest {
    private val straight = listOf(InkPoint(0f, 0f, .5f, 0), InkPoint(4f, 0f, .5f, 4), InkPoint(8f, 0f, .5f, 8))
    @Test fun overlongPredictionIsCappedWithoutChangingRealPoints() {
        val tail = guardedPrediction(straight, InkPoint(100f, 0f, 1f, 9), 1f, 1f)!!
        assertEquals(14f, tail.x, .001f); assertEquals(.5f, tail.pressure, 0f)
        assertEquals(8f, straight.last().x, 0f)
    }
    @Test fun reversePredictionIsRejected() { assertNull(guardedPrediction(straight, InkPoint(2f, 0f, 1f, 9), 1f, 1f)) }
    @Test fun lateralSpikesAreRejected() { assertNull(guardedPrediction(straight, InkPoint(10f, 50f, 1f, 9), 1f, 1f)) }
    @Test fun cornerDoesNotExtendOldDirection() {
        val corner = straight + InkPoint(8f, 4f, .5f, 12)
        assertNull(guardedPrediction(corner, InkPoint(8f, 10f, .5f, 12), 1f, 1f))
    }
    @Test fun stationaryAndStaleEventsDoNotPredict() {
        assertNull(guardedPrediction(straight + straight.last().copy(time = 12), straight.last().copy(x = 30f), 1f, 1f))
        assertNull(guardedPrediction(straight + straight.last().copy(x = 12f, time = 100), straight.last().copy(x = 30f), 1f, 1f))
    }
    @Test fun capIsInScreenUnitsAcrossZoomLevels() {
        for (scale in listOf(.5f, 1f, 2f)) {
            val tail = guardedPrediction(straight, InkPoint(100f, 0f, 1f, 9), scale, 1f)!!
            assertTrue((tail.x - 8f) * scale <= 6.001f)
        }
    }
    @Test fun unavailableOrInvalidSdkPointFallsBackToRealInk() {
        assertNull(guardedPrediction(straight, null, 1f, 1f))
        assertNull(guardedPrediction(straight, InkPoint(Float.NaN, 0f, 1f, 9), 1f, 1f))
    }
}
