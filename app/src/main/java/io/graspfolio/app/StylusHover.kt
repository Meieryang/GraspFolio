package io.graspfolio.app

import android.view.MotionEvent

/** Use the full hardware hover range; no artificial distance cutoff or post-hover grace time. */
internal fun DoubleTapSwitch.observeStylus(event: MotionEvent) {
    if (event.actionMasked == MotionEvent.ACTION_CANCEL) { enabled = false; return }
    val type = event.getToolType(event.actionIndex)
    if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER) return
    when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> enabled = true
        MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> enabled = false
    }
}
