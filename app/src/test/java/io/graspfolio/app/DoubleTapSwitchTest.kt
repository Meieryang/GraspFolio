package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class DoubleTapSwitchTest {
    @Test fun binarySwitchAndImmediateRepeatedGestures() {
        val gate = DoubleTapSwitch()
        var count = 0
        fun gesture() = gate.dispatch({ true }, { it(); true }, { count++ })
        assertTrue(gesture()); assertTrue(gesture())
        assertEquals(2, count)
        gate.enabled = false
        assertFalse(gesture()); assertEquals(2, count)
        gate.enabled = true
        assertTrue(gesture()); assertEquals(3, count)
    }

    @Test fun pendingGestureIsCancelledByDisablingOrLeavingWritingState() {
        val gate = DoubleTapSwitch()
        var allowed = true
        var pending: (() -> Unit)? = null
        var count = 0
        fun gesture() = gate.dispatch({ allowed }, { pending = it; true }, { count++ })
        assertTrue(gesture())
        gate.enabled = false; gate.enabled = true
        pending!!()
        assertEquals(0, count)
        assertTrue(gesture())
        allowed = false
        pending!!()
        assertEquals(0, count)
        assertFalse(gesture())
        allowed = true
        assertFalse(gate.dispatch({ true }, { false }, { count++ }))
        assertEquals(0, count)
    }
}
