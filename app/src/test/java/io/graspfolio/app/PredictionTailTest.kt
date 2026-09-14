package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class PredictionTailTest {
    private val straight = listOf(InkPoint(0f, 0f, .5f, 0), InkPoint(4f, 0f, .5f, 4), InkPoint(8f, 0f, .5f, 8))
    @Test fun overlongPredictionIsCappedWithoutChangingRealPoints() {
        val tail = guardedPrediction(straight, InkPoint(100f, 0f, 1f, 9), 1f, 1f)!!
        assertEquals(20f, tail.x, .001f); assertEquals(.5f, tail.pressure, 0f)
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
            assertTrue((tail.x - 8f) * scale <= 12.001f)
        }
    }
    @Test fun unavailableOrInvalidSdkPointFallsBackToRealInk() {
        assertNull(guardedPrediction(straight, null, 1f, 1f))
        assertNull(guardedPrediction(straight, InkPoint(Float.NaN, 0f, 1f, 9), 1f, 1f))
    }
    @Test fun strongModeAmplifiesValidSdkTailWithinItsCaps() {
        val raw = InkPoint(100f, 0f, 1f, 9)
        assertEquals(26f, guardedPrediction(straight, raw, 1f, 1f, PredictionMode.STRONG)!!.x, .001f)
        for (mode in PredictionMode.entries) {
            assertEquals(8f + mode.gain, guardedPrediction(straight, raw.copy(x = 9f), 1f, 1f, mode)!!.x, .001f)
            assertEquals(.5f, guardedPrediction(straight, raw, 1f, 1f, mode)!!.pressure, 0f)
        }
    }
    @Test fun defaultsAreStableAndDecelerationTapersPrediction() {
        assertEquals(PredictionMode.STABLE, PredictionMode.fromKey(null))
        assertEquals(PredictionMode.STABLE, PredictionMode.fromKey("future"))
        for (mode in PredictionMode.entries) assertEquals(mode, PredictionMode.fromKey(mode.key))
        val slowing = straight + InkPoint(9f, 0f, .5f, 12)
        val tail = guardedPrediction(slowing, InkPoint(100f, 0f, 1f, 13), 1f, 1f, PredictionMode.STRONG)!!
        assertTrue(tail.x - 9f <= 1.126f)
        assertEquals(9f, slowing.last().x, 0f)
    }
    @Test fun diagnosticsDistinguishSdkAndGuardRejection() {
        assertEquals(PredictionRejection.UNAVAILABLE, evaluatePrediction(straight, null, 1f, 1f, PredictionMode.STABLE).reason)
        assertEquals(PredictionRejection.REVERSE, evaluatePrediction(straight, InkPoint(0f, 0f, 1f, 9), 1f, 1f, PredictionMode.STRONG).reason)
    }
}
