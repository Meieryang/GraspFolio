package io.graspfolio.app

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry

/** Isolated native View tests: no window, SDK lifecycle binding or Compose idle synchronization. */
internal fun withNativeInkTestHost(block: (Activity) -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        val host = object : ComponentActivity() {
            init { attachBaseContext(instrumentation.targetContext) }
        }
        block(host)
    }
}
