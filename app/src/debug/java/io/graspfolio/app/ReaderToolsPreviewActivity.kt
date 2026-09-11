package io.graspfolio.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.graspfolio.app.ui.theme.GraspFolioTheme

/** Screenshot fixture, excluded from release. All changes are disposable in-memory state. */
class ReaderToolsPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val landscape = intent.getBooleanExtra("landscape", false)
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val panel = intent.getStringExtra("panel")?.takeIf { it == "brush" || it == "settings" }
        enableEdgeToEdge()
        setContent {
            GraspFolioTheme(darkTheme = false, dynamicColor = false) {
                ImmersiveReading()
                var style by remember { mutableStateOf(BrushStyle("highlighter", 0xfff1ce58.toInt(), 12f)) }
                var doubleTapEnabled by remember { mutableStateOf(true) }
                var lasso by remember { mutableStateOf(false) }
                var eraser by remember { mutableStateOf(false) }
                var page by remember { mutableIntStateOf(5) }
                Box(Modifier.fillMaxSize().background(Color(0xfff8f4ec))) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(if (landscape) 2 else 1) { index ->
                            Column(Modifier.weight(1f).fillMaxHeight().background(Color.White).padding(44.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                Spacer(Modifier.height(60.dp))
                                Text("掌页 · 阅读与思考", color = Color(0xff333333), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.headlineMedium)
                                Text("UI PREVIEW  /  ${(page + index + 1).toString().padStart(2, '0')}", color = Color(0xff89949f), style = MaterialTheme.typography.labelSmall)
                                repeat(8) { Text("在书页上留下思考，让每一次阅读都有迹可循。\n工具轻轻浮现，内容始终是阅读的主角。\n\nGraspFolio · A quiet space for your ideas.", color = Color(0xff666666), style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                    }
                    ReaderTools(style, eraser, { style = it }, { style = BrushStyle(it, if (it == "highlighter") 0xfff1ce58.toInt() else 0xff111111.toInt(), BrushStyle.defaultWidth(it)) },
                        { lasso = false; eraser = it }, { finish() }, page, 128, { page = it }, landscape, true, {}, true, {}, true, {}, true, {},
                        "独立 UI 预览 · 不访问用户 PDF 或批注", "已同步 · UI 预览状态", {}, {}, { finish() }, initialPanel = panel, lasso = lasso, onLasso = { lasso = true; eraser = false },
                        doubleTapEnabled = doubleTapEnabled, onDoubleTapEnabled = { doubleTapEnabled = it })
                }
            }
        }
    }
}
