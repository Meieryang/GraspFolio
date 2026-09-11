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
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val ToolInk = Color(0xff3c5368)
private val ToolMuted = Color(0xff778591)
private val Swatches = listOf(0xff111111.toInt(), 0xff567896.toInt(), 0xffc57968.toInt(),
    0xfff1ce58.toInt(), 0xff83b09a.toInt(), 0xffa994c1.toInt())
internal fun brushName(brush: String) = when (brush) { "fineliner" -> "等宽笔"; "highlighter" -> "荧光笔"; else -> "压感笔" }

/** A lightweight glass-like surface, without a full-screen blur pass over the PDF/ink. */
private fun Modifier.glass(radius: Int = 30): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return shadow(10.dp, shape, ambientColor = ToolInk.copy(alpha = .12f), spotColor = ToolInk.copy(alpha = .16f))
        .clip(shape).background(Brush.verticalGradient(listOf(Color(0xf5fffefa), Color(0xdce9edf0))))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = .4f))), shape)
}

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
    doubleTapEnabled: Boolean = true, onDoubleTapEnabled: (Boolean) -> Unit = {}
) {
    var panel by rememberSaveable { mutableStateOf(initialPanel) }
    var lastDismissRequest by remember { mutableIntStateOf(dismissBrushRequest) }
    LaunchedEffect(dismissBrushRequest) {
        if (lastDismissRequest != dismissBrushRequest && panel == "brush") panel = null
        lastDismissRequest = dismissBrushRequest
    }
    BackHandler(enabled = panel != null) { panel = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 420.dp
        val panelHeight = minOf(maxHeight * .7f, (maxHeight - 160.dp).coerceAtLeast(96.dp))
        Row(Modifier.align(Alignment.TopCenter).padding(top = 16.dp, start = if (compact) 8.dp else 12.dp, end = if (compact) 8.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.glass(18).padding(horizontal = 2.dp), verticalAlignment = Alignment.Top) {
                for (type in BrushStyle.types) RealisticTool(type, brushName(type), !eraser && !lasso && style.brush == type,
                    if (type == style.brush) Color(style.opaqueColor) else if (type == "highlighter") Color(0xffebc751) else ToolInk,
                    compact) {
                    val alreadySelected = !eraser && !lasso && style.brush == type
                    onBrush(type); onEraser(false)
                    panel = if (alreadySelected && panel != "brush") "brush" else null
                }
                RealisticTool("eraser", "整笔橡皮", eraser && !lasso, Color(0xffd79489), compact) { onEraser(true); panel = null }
                RealisticTool("lasso", "套索笔", lasso, Color(0xff799790), compact) { onLasso(); panel = null }
                Box(Modifier.padding(top = 7.dp)) {
                    ToolButton("chevron", "画笔颜色与粗细", panel == "brush") { panel = if (panel == "brush") null else "brush" }
                }
            }
            DoubleTapButton(doubleTapEnabled, onDoubleTapEnabled)
            Box(Modifier.glass()) { ToolButton("settings", "阅读设置与保存", panel == "settings") { panel = if (panel == "settings") null else "settings" } }
            Box(Modifier.glass()) { ToolButton("close", "返回沉浸阅读", false, onClose) }
        }

        if (panel == "brush") Column(Modifier.align(Alignment.TopCenter).padding(top = 92.dp, start = 16.dp, end = 16.dp)
            .widthIn(max = 380.dp).fillMaxWidth().heightIn(max = panelHeight).glass(24)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("书写工具", color = ToolInk, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrushStyle.types.forEach { type ->
                    FilterChip(selected = !eraser && !lasso && style.brush == type, onClick = { onBrush(type); onEraser(false) }, label = { Text(brushName(type)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ToolInk.copy(alpha = .12f), selectedLabelColor = ToolInk, labelColor = ToolMuted))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceBetween) {
                Swatches.forEach { color -> ColorButton(color, style.color == color) { onStyle(style.copy(color = color)); onEraser(false) } }
            }
            val range = if (style.brush == "highlighter") 6f..32f else .5f..8f
            Text("粗细  ${String.format(java.util.Locale.ROOT, "%.1f", style.width)} pt", color = ToolInk, style = MaterialTheme.typography.labelLarge)
            SlimSlider(value = style.width.coerceIn(range), onValueChange = { onStyle(style.copy(width = it)); onEraser(false) },
                valueRange = range, modifier = Modifier.semantics { contentDescription = "画笔粗细（PDF 点）" },
            )
            Canvas(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .65f))) {
                val color = Color(style.opaqueColor).copy(alpha = style.alpha / 255f)
                // One path gives the translucent preview one opacity, including joins.
                val path = Path().apply { moveTo(size.width * .1f, size.height * .65f); cubicTo(size.width * .35f, -size.height * .1f, size.width * .6f, size.height * 1.1f, size.width * .9f, size.height * .35f) }
                drawPath(path, color, style = Stroke(width = style.width.dp.toPx().coerceAtMost(size.height * .7f), cap = StrokeCap.Round))
            }
            Text(if (style.brush == "highlighter") "半透明标记 · 同一笔交叉不加深" else if (style.brush == "pressure") "随手写笔压力变化的自然线条" else "稳定等宽 · 适合勾画与标注",
                color = ToolMuted, style = MaterialTheme.typography.bodySmall)
        }

        if (panel == "settings") Column(Modifier.align(Alignment.TopEnd).padding(top = 92.dp, start = 16.dp, end = 16.dp)
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
            TextButton(onClick = onAuthorize) { Text("授权 PDF 所在目录", color = ToolInk) }
            TextButton(onClick = onRetry) { Text("重试同步", color = ToolInk) }
            var details by remember { mutableStateOf(false) }
            TextButton(onClick = { details = !details }) { Text(if (details) "收起书写诊断" else "书写诊断", color = ToolInk) }
            if (details) Text(diagnostics + "\n荧光笔使用单次透明合成，避免抬笔变深。", color = ToolMuted, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onExit) { Text("退出阅读", color = ToolInk) }
        }

        if (pageCount > 0) {
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
private fun SlimSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier, onValueChangeFinished: () -> Unit = {}, enabled: Boolean = true) {
    Slider(value, onValueChange, modifier, enabled = enabled, valueRange = valueRange, onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(16.dp).shadow(2.dp, CircleShape).background(if (enabled) ToolInk else ToolMuted, CircleShape)) },
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
        Switch(checked, { onClick() }, modifier = Modifier.semantics { contentDescription = label }, colors = SwitchDefaults.colors(checkedTrackColor = ToolInk))
    }
}

@Composable
private fun ColorButton(color: Int, selected: Boolean, onClick: () -> Unit) {
    val name = when (color) { Swatches[0] -> "墨黑"; Swatches[1] -> "雾蓝"; Swatches[2] -> "陶粉"; Swatches[3] -> "暖黄"; Swatches[4] -> "鼠尾草绿"; Swatches[5] -> "淡紫"; else -> "当前颜色" }
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = name; this.selected = selected }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (selected) 28.dp else 22.dp).border(if (selected) 2.dp else 0.dp, if (selected) ToolInk else Color.Transparent, CircleShape)
            .padding(4.dp).background(Color(color), CircleShape))
    }
}

@Composable
private fun ToolButton(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected }.padding(4.dp)
        .background(if (selected) ToolInk.copy(alpha = .11f) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            fun p(x: Float, y: Float) = Offset(x * size.width / 24, y * size.height / 24)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(ToolInk, p(x, y), p(x2, y2), 1.7.dp.toPx(), StrokeCap.Round)
            when (icon) {
                "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                "chevron" -> { line(7f, 10f, 12f, 15f); line(12f, 15f, 17f, 10f) }
                "settings" -> { for (y in listOf(6f, 12f, 18f)) line(4f, y, 20f, y); for ((x, y) in listOf(9f to 6f, 15f to 12f, 8f to 18f)) { drawCircle(Color(0xfff5f6f5), 3.dp.toPx(), p(x, y)); drawCircle(ToolInk, 2.dp.toPx(), p(x, y), style = Stroke(1.5.dp.toPx())) } }
                "eraser" -> { val path = Path().apply { moveTo(p(4f, 14f).x, p(4f, 14f).y); lineTo(p(13f, 4f).x, p(13f, 4f).y); lineTo(p(21f, 11f).x, p(21f, 11f).y); lineTo(p(12f, 21f).x, p(12f, 21f).y); lineTo(p(10f, 21f).x, p(10f, 21f).y); close() }; drawPath(path, ToolInk, style = Stroke(1.7.dp.toPx())); line(8f, 10f, 17f, 17f) }
                else -> { line(7f, 16f, 16f, 5f); line(16f, 5f, 20f, 8f); line(20f, 8f, 11f, 19f); line(11f, 19f, 5f, 21f); line(5f, 21f, 7f, 16f); line(7f, 16f, 11f, 19f); if (icon == "highlighter") line(5f, 23f, 19f, 23f) }
            }
        }
    }
}

/** Stylus with two tap waves; a slash and muted colour indicate disabled. */
@Composable
private fun DoubleTapButton(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.size(40.dp).glass().toggleable(enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = "轻敲切换笔刷与橡皮"; stateDescription = if (enabled) "已开启" else "已关闭" }
        .padding(4.dp).background(if (enabled) ToolInk.copy(alpha = .11f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val ink = if (enabled) ToolInk else ToolMuted
            fun p(x: Float, y: Float) = Offset(x * size.width / 24, y * size.height / 24)
            fun line(x: Float, y: Float, x2: Float, y2: Float) =
                drawLine(ink, p(x, y), p(x2, y2), 1.7.dp.toPx(), StrokeCap.Round)
            val pen = Path().apply {
                moveTo(p(4f, 20f).x, p(4f, 20f).y)
                lineTo(p(6f, 14f).x, p(6f, 14f).y)
                lineTo(p(15f, 5f).x, p(15f, 5f).y)
                lineTo(p(19f, 9f).x, p(19f, 9f).y)
                lineTo(p(10f, 18f).x, p(10f, 18f).y)
                close()
            }
            drawPath(pen, ink, style = Stroke(1.7.dp.toPx()))
            line(6f, 14f, 10f, 18f)
            for (shift in listOf(0f, 4f)) {
                val wave = Path().apply {
                    moveTo(p(3f + shift, 3f).x, p(3f + shift, 3f).y)
                    quadraticTo(p(6f + shift, 5f).x, p(6f + shift, 5f).y, p(6f + shift, 8f).x, p(6f + shift, 8f).y)
                }
                drawPath(wave, ink, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
            if (!enabled) {
                drawLine(Color(0xfff1f3f2), p(3f, 3f), p(21f, 21f), 4.dp.toPx(), StrokeCap.Round)
                line(3f, 3f, 21f, 21f)
            }
        }
    }
}
