package io.graspfolio.app

/** UI-thread state: at most one renderer request in flight, plus one coalesced refresh. */
internal class FrontFrameGate {
    @Volatile var generation = 0L; private set
    private var inFlight = false
    private var dirty = false
    fun request(): Boolean {
        dirty = true
        if (inFlight) return false
        inFlight = true; dirty = false
        return true
    }
    fun complete(frameGeneration: Long): Boolean {
        if (frameGeneration != generation) return false
        inFlight = false
        return if (dirty) request() else false
    }
    fun reset() { generation++; inFlight = false; dirty = false }
}
