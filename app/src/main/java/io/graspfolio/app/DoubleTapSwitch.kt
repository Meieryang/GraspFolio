package io.graspfolio.app

/** Gates an already-recognized SDK gesture. No timing or pressure filtering. */
internal class DoubleTapSwitch {
    private var revision = 0L
    var enabled = true
        set(value) {
            if (field != value) { field = value; revision++ }
        }

    fun dispatch(canToggle: () -> Boolean, enqueue: (() -> Unit) -> Boolean, toggle: () -> Unit): Boolean {
        if (!enabled || !canToggle()) return false
        val acceptedRevision = revision
        return enqueue {
            // A queued gesture must not survive disabling, even if re-enabled before dispatch.
            if (enabled && revision == acceptedRevision && canToggle()) toggle()
        }
    }
}
