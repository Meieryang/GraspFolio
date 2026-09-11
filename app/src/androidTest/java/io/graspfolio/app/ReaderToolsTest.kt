package io.graspfolio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.graspfolio.app.ui.theme.GraspFolioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderToolsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun portraitToolsAndSettingsFitAndSelectBrush() = checkTools(false)
    @Test fun landscapeToolsAndSettingsFitAndSelectBrush() = checkTools(true)

    private fun checkTools(landscape: Boolean) {
        var style by mutableStateOf(BrushStyle())
        var eraser by mutableStateOf(false)
        var closed = false
        compose.setContent {
            GraspFolioTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.size(if (landscape) 660.dp else 360.dp, if (landscape) 400.dp else 660.dp)
                    .background(Color(0xfff8f4ec)).testTag("preview")) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).background(Color.White).padding(30.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Spacer(Modifier.height(50.dp))
                        Text("掌页 · 阅读与思考", style = MaterialTheme.typography.headlineSmall)
                        repeat(8) { Text("在书页上留下思考，让每一次阅读都有迹可循。\nGraspFolio · A quiet space for your ideas.", color = Color(0xff777777)) }
                    }
                    ReaderTools(style, eraser, { style = it }, { style = BrushStyle(it, width = BrushStyle.defaultWidth(it)) },
                        { eraser = it }, { closed = true }, 5, 128, {}, landscape, true, {}, true, {}, true, {}, true, {}, "测试诊断",
                        "已同步 · 原 PDF 保持不变", {}, {}, {})
                }
            }
        }
        fun capture(suffix: String) {
            val bitmap = compose.onNodeWithTag("preview").captureToImage().asAndroidBitmap()
            val folder = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            File(folder, "mvp4-${if (landscape) "landscape" else "portrait"}-$suffix.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithContentDescription("返回沉浸阅读").assertIsDisplayed()
        capture("toolbar")
        compose.onNodeWithContentDescription("压感笔").performClick()
        compose.onNodeWithText("荧光笔").performClick()
        compose.onNodeWithContentDescription("暖黄").performClick()
        compose.runOnIdle { assertEquals("highlighter", style.brush); assertEquals(0xfff1ce58.toInt(), style.color); assertFalse(eraser) }
        capture("palette")
        compose.onNodeWithContentDescription("阅读设置与保存").performClick()
        compose.onNodeWithText("阅读设置").assertIsDisplayed()
        capture("settings")
        compose.onNodeWithContentDescription("返回沉浸阅读").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
