package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class FrontFrameGateTest {
    @Test fun burstHasOnlyOneInflightAndOneFollowingRefresh() {
        val gate = FrontFrameGate()
        assertTrue(gate.request())
        repeat(10000) { assertFalse(gate.request()) }
        assertTrue(gate.complete(gate.generation))
        assertFalse(gate.complete(gate.generation))
        assertTrue(gate.request())
    }
    @Test fun obsoleteCompletionCannotReleaseNewStrokeRequest() {
        val gate = FrontFrameGate()
        val old = gate.generation
        gate.request(); gate.reset(); gate.request()
        assertFalse(gate.complete(old))
        assertFalse(gate.request())
        assertTrue(gate.complete(gate.generation))
    }
}
