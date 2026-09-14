package io.graspfolio.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val ToolInk = GlassInk
private val ToolMuted = GlassMuted
private val Swatches = listOf(0xff111111.toInt(), 0xff567896.toInt(), 0xffc57968.toInt(),
    0xfff1ce58.toInt(), 0xff83b09a.toInt(), 0xffa994c1.toInt())
private val OfferedBrushes = listOf("pressure", "highlighter")
internal fun brushName(brush: String) = when (brush) { "fineliner" -> "等宽笔"; "highlighter" -> "荧光笔"; else -> "压感笔" }

@Composable
private fun Modifier.glass(radius: Int = 30): Modifier = liquidGlass(radius, if (radius == 24) .60f else .24f)

@Composable
internal fun ReaderTools(
    style: BrushStyle, eraser: Boolean, onStyle: (BrushStyle) -> Unit, onBrush: (String) -> Unit,
    onEraser: (Boolean) -> Unit, onClose: () -> Unit, page: Int, pageCount: Int, onPage: (Int) -> Unit,
    spread: Boolean, cover: Boolean, onCover: () -> Unit,
    writingVibration: Boolean, onVibration: () -> Unit, prediction: Boolean, onPrediction: () -> Unit,
    frontBuffer: Boolean, onFrontBuffer: () -> Unit, diagnostics: String,
    saveStatus: String, onAuthorize: () -> Unit, onRetry: () -> Unit, onExit: () -> Unit,
    initialPanel: String? = null,
    dismissBrushRequest: Int = 0,
    lasso: Boolean = false, onLasso: () -> Unit = {},
    immersiveBar: Boolean = false,
    brushColor: (String) -> Int = { if (it == "highlighter") Swatches[3] else Swatches[0] },
    audioUi: ReaderAudioUi? = null
) {
    var panel by rememberSaveable { mutableStateOf(initialPanel) }
    var lastDismissRequest by remember { mutableIntStateOf(dismissBrushRequest) }
    LaunchedEffect(dismissBrushRequest) {
        if (lastDismissRequest != dismissBrushRequest && panel == "brush") panel = null
        lastDismissRequest = dismissBrushRequest
    }
    LaunchedEffect(audioUi?.interactionVersion) { if (audioUi != null) panel = null }
    LaunchedEffect(panel) { if (panel != null) audioUi?.collapse?.invoke() }
    BackHandler(enabled = panel != null) { panel = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val compact = maxWidth < 420.dp
        val audioOnOwnRow = audioUi != null && maxWidth < 700.dp
        val audioOffset = if (audioOnOwnRow) 56.dp else 0.dp
        if (audioUi != null) Box(Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 16.dp, end = 16.dp)) {
            audioUi.entries(if (audioOnOwnRow) availableWidth - 32.dp else ((availableWidth - 440.dp) / 2 - 24.dp).coerceIn(48.dp, 200.dp))
        }
        val panelHeight = minOf(maxHeight * .7f, (maxHeight - 160.dp).coerceAtLeast(96.dp))
        Row(Modifier.align(Alignment.TopCenter).padding(top = if (immersiveBar) 0.dp else 16.dp + audioOffset, start = if (compact) 8.dp else 12.dp, end = if (compact) 8.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp), verticalAlignment = Alignment.Top) {
            Box {
                // Separate the tray surface from its contents so the tips are not clipped.
                Box(Modifier.matchParentSize().padding(bottom = 18.dp).glass(22))
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    for (type in OfferedBrushes) RealisticTool(type, brushName(type), !eraser && !lasso && style.brush == type,
                        if (type == style.brush) Color(style.opaqueColor) else Color(brushColor(type)),
                        compact) {
                        val alreadySelected = !eraser && !lasso && style.brush == type
                        onBrush(type); onEraser(false)
                        panel = if (alreadySelected && panel != "brush") "brush" else null
                    }
                    RealisticTool("eraser", "整笔橡皮", eraser && !lasso, Color(0xffd79489), compact) { onEraser(true); panel = null }
                    RealisticTool("lasso", "套索笔", lasso, Color(0xff799790), compact) { onLasso(); panel = null }
                    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Swatches.take(3).forEach { color ->
                            QuickColorButton(color, style.color == color && !eraser && !lasso, compact) {
                                onStyle(style.copy(color = color)); onEraser(false)
                            }
                        }
                    }
                }
            }
            if (!immersiveBar) {
            Box(Modifier.glass()) { ToolButton("settings", "阅读设置与保存", panel == "settings") { panel = if (panel == "settings") null else "settings" } }
            }
            Box(Modifier.glass()) { ToolButton(if (immersiveBar) "close" else "exit", if (immersiveBar) "关闭沉浸画笔栏" else "退出阅读", false, if (immersiveBar) onClose else onExit) }
        }

        if (audioUi != null && panel == null) Box(Modifier.align(Alignment.TopStart).padding(top = 76.dp + audioOffset, start = 16.dp, end = 16.dp)) {
            audioUi.controls()
        }
        if (panel == "brush") Column(Modifier.align(Alignment.TopCenter).padding(top = if (immersiveBar) 68.dp else 84.dp + audioOffset, start = 16.dp, end = 16.dp)
            .widthIn(max = 272.dp).fillMaxWidth().heightIn(max = panelHeight).glass(24)
            .verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Swatches.forEach { color -> ColorButton(color, style.color == color) { onStyle(style.copy(color = color)); onEraser(false) } }
            }
            val range = if (style.brush == "highlighter") 6f..32f else .5f..8f
            Text("粗细  ${String.format(java.util.Locale.ROOT, "%.1f", style.width)} pt", color = ToolInk, style = MaterialTheme.typography.labelLarge)
            SlimSlider(value = style.width.coerceIn(range), onValueChange = { onStyle(style.copy(width = it)); onEraser(false) },
                valueRange = range, modifier = Modifier.semantics { contentDescription = "画笔粗细（PDF 点）" },
            )

        }

        if (panel == "settings") Column(Modifier.align(Alignment.TopEnd).padding(top = 84.dp + audioOffset, start = 16.dp, end = 16.dp)
            .widthIn(max = 360.dp).fillMaxWidth().heightIn(max = panelHeight).glass(24)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("阅读设置", color = ToolInk, style = MaterialTheme.typography.titleMedium)
            if (spread) SettingSwitch("封面单独显示", cover, onCover)
            SettingSwitch("书写振动", writingVibration, onVibration)
            SettingSwitch("笔迹预测", prediction, onPrediction)
            SettingSwitch("低延迟前缓冲", frontBuffer, onFrontBuffer)
            HorizontalDivider(color = ToolMuted.copy(alpha = .15f))
            Text("批注保存", color = ToolInk, fontWeight = FontWeight.Medium)
            Text(saveStatus, color = ToolMuted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAuthorize, modifier = Modifier.liquidGlass(18)) { Text("授权 PDF 所在目录", color = ToolInk) }
            TextButton(onClick = onRetry, modifier = Modifier.liquidGlass(18)) { Text("重试同步", color = ToolInk) }
            var details by remember { mutableStateOf(false) }
            TextButton(onClick = { details = !details }) { Text(if (details) "收起书写诊断" else "书写诊断", color = ToolInk) }
            if (details) Text(diagnostics + "\n荧光笔使用单次透明合成，避免抬笔变深。", color = ToolMuted, style = MaterialTheme.typography.labelSmall)
        }

        if (!immersiveBar && pageCount > 0) {
            var scrub by remember(page) { mutableFloatStateOf(page.toFloat()) }
            Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 920.dp).fillMaxWidth().glass()
                .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SlimSlider(value = scrub.coerceIn(0f, (pageCount - 1).coerceAtLeast(1).toFloat()), onValueChange = { scrub = it },
                    onValueChangeFinished = { onPage(scrub.roundToInt().coerceIn(0, pageCount - 1)) }, enabled = pageCount > 1,
                    valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f).semantics { contentDescription = "阅读进度" })
                Text("${scrub.roundToInt() + 1} / $pageCount", color = ToolInk, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlimSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier, onValueChangeFinished: () -> Unit = {}, enabled: Boolean = true) {
    Slider(value, onValueChange, modifier, enabled = enabled, valueRange = valueRange, onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(18.dp).liquidGlass(9).padding(3.dp).background(if (enabled) Color(0xff567896) else ToolMuted, CircleShape)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val end = Offset(size.width, size.height / 2)
                val start = Offset(0f, size.height / 2)
                drawLine(ToolMuted.copy(alpha = .2f), start, end, size.height, StrokeCap.Round)
                val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                drawLine(ToolInk, start, end.copy(x = size.width * fraction), size.height, StrokeCap.Round)
            }
        })
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = ToolInk, style = MaterialTheme.typography.bodyMedium)
        GlassToggle(label, checked) { onClick() }
    }
}

@Composable
private fun ColorButton(color: Int, selected: Boolean, onClick: () -> Unit) {
    val name = when (color) { Swatches[0] -> "墨黑"; Swatches[1] -> "雾蓝"; Swatches[2] -> "陶粉"; Swatches[3] -> "暖黄"; Swatches[4] -> "鼠尾草绿"; Swatches[5] -> "淡紫"; else -> "当前颜色" }
    Box(Modifier.size(38.dp).clip(CircleShape).glassClickable(selected, Role.RadioButton, onClick)
        .semantics { contentDescription = name; this.selected = selected }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (selected) 28.dp else 22.dp).border(if (selected) 2.dp else 0.dp, if (selected) Color.Black else Color.Transparent, CircleShape)
            .padding(4.dp).background(Color(color), CircleShape))
    }
}

@Composable
private fun ToolButton(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).glassClickable(selected, Role.Button, onClick)
        .semantics { contentDescription = label; this.selected = selected }.padding(4.dp)
        .background(if (selected) ToolInk.copy(alpha = .11f) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            fun p(x: Float, y: Float) = Offset(x * size.width / 24, y * size.height / 24)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(ToolInk, p(x, y), p(x2, y2), 1.7.dp.toPx(), StrokeCap.Round)
            when (icon) {
                "exit" -> {
                    line(12f, 4f, 4f, 4f); line(4f, 4f, 4f, 20f); line(4f, 20f, 12f, 20f)
                    line(12f, 4f, 12f, 8f); line(12f, 16f, 12f, 20f)
                    line(9f, 12f, 21f, 12f); line(17f, 8f, 21f, 12f); line(21f, 12f, 17f, 16f)
                }
                "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                "chevron" -> { line(7f, 10f, 12f, 15f); line(12f, 15f, 17f, 10f) }
                "settings" -> { for (y in listOf(6f, 12f, 18f)) line(4f, y, 20f, y); for ((x, y) in listOf(9f to 6f, 15f to 12f, 8f to 18f)) { drawCircle(Color(0xfff5f6f5), 3.dp.toPx(), p(x, y)); drawCircle(ToolInk, 2.dp.toPx(), p(x, y), style = Stroke(1.5.dp.toPx())) } }
                "eraser" -> { val path = Path().apply { moveTo(p(4f, 14f).x, p(4f, 14f).y); lineTo(p(13f, 4f).x, p(13f, 4f).y); lineTo(p(21f, 11f).x, p(21f, 11f).y); lineTo(p(12f, 21f).x, p(12f, 21f).y); lineTo(p(10f, 21f).x, p(10f, 21f).y); close() }; drawPath(path, ToolInk, style = Stroke(1.7.dp.toPx())); line(8f, 10f, 17f, 17f) }
                else -> { line(7f, 16f, 16f, 5f); line(16f, 5f, 20f, 8f); line(20f, 8f, 11f, 19f); line(11f, 19f, 5f, 21f); line(5f, 21f, 7f, 16f); line(7f, 16f, 11f, 19f); if (icon == "highlighter") line(5f, 23f, 19f, 23f) }
            }
        }
    }
}

/** The quick palette uses the existing exact swatches, with a black selection ring. */
@Composable
private fun QuickColorButton(color: Int, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val label = when (color) { Swatches[0] -> "墨黑"; Swatches[1] -> "雾蓝"; else -> "陶粉" }
    Box(Modifier.width(if (compact) 24.dp else 32.dp).height(40.dp)
        .glassClickable(selected, Role.RadioButton, onClick)
        .semantics { contentDescription = label; this.selected = selected }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).border(if (selected) 2.dp else 0.dp, if (selected) Color.Black else Color.Transparent, CircleShape)
            .padding(5.dp).background(Color(color), CircleShape))
    }
}
