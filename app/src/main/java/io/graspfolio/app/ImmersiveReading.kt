package io.graspfolio.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Own system-bar visibility only while the reader is in composition. */
@Composable
internal fun ImmersiveReading() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context.findActivity()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val previousBehavior = controller?.systemBarsBehavior
        fun hideBars() {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        hideBars()
        // Returning from a picker or another app should resume immersive reading.
        val observer = view.viewTreeObserver
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) hideBars()
        }
        observer.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.findActivity()
    else -> null
}
