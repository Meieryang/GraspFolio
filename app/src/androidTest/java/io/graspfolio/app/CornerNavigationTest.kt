package io.graspfolio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CornerNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rightHoldSurvivesPageRecompositionAndStopsOnRelease() = checkHold(1)

    @Test fun leftHoldSurvivesPageRecompositionAndStopsOnRelease() = checkHold(-1)

    private fun checkHold(direction: Int) {
        var page by mutableIntStateOf(10)
        var continuous by mutableStateOf(false)
        var startCount = 0
        compose.setContent {
            // Intentionally capture a value from each composition: the modifier must call the
            // latest callback while preserving the ongoing pointer coroutine across page changes.
            val displayedPage = page
            Box(Modifier.size(400.dp).testTag("reader").cornerNavigationInput(
                documentKey = "same-document",
                onNavigate = { page = displayedPage + it },
                onContinuousStart = { _, _ -> startCount++; continuous = true },
                onContinuousMove = {},
                onContinuousEnd = { continuous = false }
            )) {
                // Exercise the native ink overlay accepting the same finger stream.
                AndroidView(factory = { StylusInkView(it) }, modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(if (direction > 0) width - 10f else 10f, 10f))
        }
        // Real pointer timeouts must run between separate injected down/up events.
        compose.waitUntil(timeoutMillis = 5_000) { (page - 10) * direction >= 3 }
        compose.runOnIdle {
            assertTrue(continuous)
            assertEquals(1, startCount)
        }
        compose.onNodeWithTag("reader").performTouchInput { up() }
        var stoppedPage = 0
        compose.runOnIdle {
            assertEquals(false, continuous)
            stoppedPage = page
        }
        // Wait beyond the default repeat interval to detect a leaked repeat timer or extra tap.
        Thread.sleep(800)
        compose.runOnIdle { assertEquals(stoppedPage, page) }
    }
}
