package io.graspfolio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView

/** Isolated gesture fixture; no PDF, preferences or annotation coordinator. */
class CornerNavigationTestActivity : ComponentActivity() {
    companion object { @Volatile var current: CornerNavigationTestActivity? = null }
    @Volatile var viewport = Rect.Zero
    @Volatile var turns = 0
    @Volatile var starts = 0
    @Volatile var continuous = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        enableEdgeToEdge()
        setContent {
            Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInWindow() }
                .cornerNavigationInput("fixture", onNavigate = { turns += it },
                    onContinuousStart = { _, _ -> starts++; continuous = true },
                    onContinuousMove = {}, onContinuousEnd = { continuous = false })) {
                AndroidView(factory = { StylusInkView(it) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
