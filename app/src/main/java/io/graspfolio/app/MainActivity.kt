package io.graspfolio.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect as AndroidRect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.graspfolio.app.ui.theme.GraspFolioTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GraspFolioTheme(darkTheme = false, dynamicColor = false) {
                GraspFolioApp(initialUri = intent.data)
            }
        }
    }
}

@Composable
private fun GraspFolioApp(initialUri: Uri?) {
    val context = LocalContext.current
    var documentPath by rememberSaveable { mutableStateOf(initialUri?.toString()) }
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Some providers only grant temporary access. */ }
            documentPath = it.toString()
        }
    }
    if (documentPath == null) WelcomeScreen { openPdf.launch(arrayOf("application/pdf")) }
    else PdfReader(Uri.parse(documentPath!!), onOpenAnother = { openPdf.launch(arrayOf("application/pdf")) }, onExit = { documentPath = null })
}

@Composable
private fun WelcomeScreen(onOpen: () -> Unit) = Box(
    Modifier.fillMaxSize().background(Paper).safeDrawingPadding(), contentAlignment = Alignment.Center
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Text("GraspFolio", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("掌页", style = MaterialTheme.typography.titleLarge, color = MutedInk)
        Spacer(Modifier.height(18.dp))
        Text("握住书页，自在阅读。", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("手指负责握住书。书角负责翻页。", color = MutedInk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Ink), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
            Text("打开本地 PDF")
        }
    }
}

@Composable
private fun PdfReader(uri: Uri, onOpenAnother: () -> Unit, onExit: () -> Unit) {
    ImmersiveReading()
    val context = LocalContext.current
    val annotations = remember(uri) { AnnotationStore.obtain(context, uri) }
    val readerLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(annotations, readerLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) annotations.flush()
        }
        readerLifecycle.addObserver(observer)
        onDispose { readerLifecycle.removeObserver(observer); annotations.flush() }
    }
    var menu by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var eraser by rememberSaveable { mutableStateOf(false) }
    val penSettings = remember { context.getSharedPreferences("pen_settings", Context.MODE_PRIVATE) }
    var brushStyle by remember { mutableStateOf(penSettings.loadBrush()) }
    val selectStyle: (BrushStyle) -> Unit = { brushStyle = it; penSettings.saveBrush(it) }
    var writingVibration by rememberSaveable { mutableStateOf(penSettings.getBoolean("writing_vibration", true)) }
    var predictionEnabled by rememberSaveable { mutableStateOf(penSettings.getBoolean("prediction", true)) }
    var frontBufferEnabled by rememberSaveable { mutableStateOf(penSettings.getBoolean("front_buffer", true)) }
    var penDiagnostics by remember { mutableStateOf("写几笔后显示 SDK 状态和绘制耗时") }
    var penContact by remember { mutableStateOf(false) }
    BackHandler { menu = !menu }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(annotations::authorize) }
    val progressStore = remember(context) { ReadingProgressStore(context) }
    val savedProgress = remember(uri) { progressStore.load(uri.toString()) }
    val spread = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var cover by remember(uri) { mutableStateOf(savedProgress.cover) }
    var document by remember(uri) { mutableStateOf<PdfDocument?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var page by remember(uri) { mutableIntStateOf(savedProgress.page) }
    var rendered by remember(uri) { mutableStateOf<RenderedPages?>(null) }
    val bitmap = rendered?.bitmap
    var renderWidth by remember { mutableIntStateOf(0) }
    var holdPosition by remember { mutableStateOf<Offset?>(null) }
    var holdOrigin by remember { mutableStateOf<Offset?>(null) }
    var holdDirection by remember { mutableStateOf(0) }
    LaunchedEffect(uri) { runCatching { PdfDocument(context, uri) }.onSuccess { page = page.coerceIn(0, it.pageCount - 1); document = it }.onFailure { error = "无法打开这个 PDF：${it.message ?: "文件可能已损坏或不可访问"}" } }
    DisposableEffect(document) {
        // Capture this composition's instance. Reading the mutable state from onDispose would
        // otherwise close the newly opened document while disposing the initial null effect.
        val documentToClose = document
        onDispose { documentToClose?.close() }
    }
    LaunchedEffect(document, page, renderWidth, spread, cover) {
        document?.takeIf { renderWidth > 0 }?.let { active ->
            val pages = readingPages(page, active.pageCount, spread, cover)
            try {
                rendered = withContext(Dispatchers.Default) { active.render(pages, renderWidth) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "页面渲染失败：${failure.message ?: "未知错误"}" }
        }
    }
    val navigate: (Int) -> Unit = { direction ->
        document?.let { active ->
            page = turnPage(page, active.pageCount, spread, cover, direction)
            progressStore.save(uri.toString(), ReadingProgress(page, cover))
        }
    }
    // Hidden system bars occupy no layout space. Only physical cutouts and a system-owned
    // desktop caption (if present) reduce the usable area; page fit and corner input share it.
    Box(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.captionBar))) {
        when {
            error != null -> ErrorScreen(error!!, onOpenAnother)
            document == null -> LoadingScreen()
            else -> BoxWithConstraints(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val viewport = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
                val pageBounds = bitmap?.let { fittedPageBounds(viewport, Size(it.width.toFloat(), it.height.toFloat())) } ?: Rect.Zero
                val currentPages = readingPages(page, document!!.pageCount, spread, cover)
                val renderReady = rendered?.indices == currentPages
                val inkPages = rendered?.let { result ->
                    val fit = pageBounds.width / result.bitmap.width
                    result.placements.map { p -> p.copy(left = pageBounds.left + p.left * fit, top = pageBounds.top + p.top * fit, scale = p.scale * fit) }
                } ?: emptyList()
                Box(Modifier.fillMaxSize().cornerNavigationInput(
                    documentKey = Triple(Pair(document!!, spread), penContact, menu),
                    enabled = !penContact && !menu,
                    pageBounds = pageBounds,
                    physicalBounds = inkPages.map { Rect(it.left, it.top, it.left + it.width * it.scale, it.top + it.height * it.scale) },
                    onNavigate = navigate,
                    onContinuousStart = { position, direction -> holdOrigin = position; holdPosition = position; holdDirection = direction; vibrate(context) },
                    onContinuousMove = { holdPosition = it },
                    onContinuousEnd = { holdOrigin = null; holdPosition = null; holdDirection = 0 }
                )) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val targetWidth = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
                    LaunchedEffect(targetWidth) { renderWidth = targetWidth }
                    bitmap?.let { Image(it.asImageBitmap(), "PDF 第 ${page + 1} 页", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                AndroidView(
                    factory = { StylusInkView(it) },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.placements = inkPages
                        view.strokes = annotations.strokes
                        view.eraser = eraser
                        view.brushStyle = brushStyle
                        view.writingVibration = writingVibration
                        view.onDiagnostics = { penDiagnostics = it }
                        view.predictionEnabled = predictionEnabled
                        view.frontBufferEnabled = frontBufferEnabled
                        view.enabledForWriting = annotations.ready && renderReady && !menu
                        view.onChange = annotations::replace
                        view.onContact = { penContact = it }
                        view.onToggle = { eraser = !eraser }
                    }
                )
                val label = readingPages(page, document!!.pageCount, spread, cover).filterNotNull().joinToString("–") { (it + 1).toString() }
                if (!menu) PageNumber(label, document!!.pageCount, Modifier.align(Alignment.BottomCenter))
                holdOrigin?.let { origin ->
                    ContinuousTurnIndicator(origin, holdPosition ?: origin, holdDirection)
                }
                if (!menu && !annotations.status.startsWith("已同步")) Text(annotations.status, color = Ink,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = .8f)).padding(6.dp),
                    style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (menu) ReaderTools(
            style = brushStyle, eraser = eraser, onStyle = selectStyle,
            onBrush = { selectStyle(penSettings.loadBrush(it)) }, onEraser = { eraser = it }, onClose = { menu = false },
            page = page, pageCount = document?.pageCount ?: 0,
            onPage = { target -> document?.let { doc -> page = readingPages(target, doc.pageCount, spread, cover).filterNotNull().first(); progressStore.save(uri.toString(), ReadingProgress(page, cover)) } },
            spread = spread, cover = cover, onCover = { cover = !cover; progressStore.save(uri.toString(), ReadingProgress(page, cover)) },
            writingVibration = writingVibration, onVibration = { writingVibration = !writingVibration; penSettings.edit().putBoolean("writing_vibration", writingVibration).apply() },
            prediction = predictionEnabled, onPrediction = { predictionEnabled = !predictionEnabled; penSettings.edit().putBoolean("prediction", predictionEnabled).apply() },
            frontBuffer = frontBufferEnabled, onFrontBuffer = { frontBufferEnabled = !frontBufferEnabled; penSettings.edit().putBoolean("front_buffer", frontBufferEnabled).apply() },
            diagnostics = penDiagnostics, saveStatus = annotations.status, onAuthorize = { folderPicker.launch(null) }, onRetry = annotations::retry, onExit = onExit
        )
    }
}

@Composable
internal fun Modifier.cornerNavigationInput(
    documentKey: Any,
    enabled: Boolean = true,
    pageBounds: Rect? = null,
    physicalBounds: List<Rect> = emptyList(),
    onNavigate: (Int) -> Unit,
    onContinuousStart: (Offset, Int) -> Unit,
    onContinuousMove: (Offset) -> Unit,
    onContinuousEnd: () -> Unit
): Modifier {
    // Page changes must not cancel a held gesture. Keep callbacks fresh without restarting it.
    val navigate by rememberUpdatedState(onNavigate)
    val start by rememberUpdatedState(onContinuousStart)
    val move by rememberUpdatedState(onContinuousMove)
    val end by rememberUpdatedState(onContinuousEnd)
    val bounds by rememberUpdatedState(pageBounds)
    val physical by rememberUpdatedState(physicalBounds)
    return pointerInput(documentKey, enabled) {
        if (!enabled) return@pointerInput
        cornerNavigation({ physical.ifEmpty { listOf(bounds ?: Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())) } }, { navigate(it) }, { position, direction -> start(position, direction) }, { move(it) }, { end() })
    }
}

internal fun fittedPageBounds(viewport: Size, page: Size): Rect {
    if (viewport.width <= 0 || viewport.height <= 0 || page.width <= 0 || page.height <= 0) return Rect.Zero
    val scale = minOf(viewport.width / page.width, viewport.height / page.height)
    val width = page.width * scale
    val height = page.height * scale
    val left = (viewport.width - width) / 2
    val top = (viewport.height - height) / 2
    return Rect(left, top, left + width, top + height)
}

internal fun cornerDirection(position: Offset, bounds: Rect, corner: Float): Int {
    if (!bounds.contains(position)) return 0
    val width = minOf(corner, bounds.width / 2)
    val height = minOf(corner, bounds.height / 2)
    if (position.y > bounds.top + height) return 0
    return when {
        position.x < bounds.left + width -> -1
        position.x >= bounds.right - width -> 1
        else -> 0
    }
}

private fun controlLength(origin: Offset, height: Float, density: Float): Float =
    minOf(260f * density, (height - origin.y - 16f * density).coerceAtLeast(1f))

private suspend fun PointerInputScope.cornerNavigation(pageBounds: () -> List<Rect>, onNavigate: (Int) -> Unit, onContinuousStart: (Offset, Int) -> Unit, onContinuousMove: (Offset) -> Unit, onContinuousEnd: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val corner = 92f * density
        val direction = pageBounds().firstNotNullOfOrNull { cornerDirection(down.position, it, corner).takeIf { value -> value != 0 } } ?: 0
        if (direction == 0) return@awaitEachGesture
        var position = down.position
        val released = withTimeoutOrNull(600L) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.pressed && it.type != PointerType.Touch }) return@withTimeoutOrNull false
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                position = change.position
                if (change.changedToUpIgnoreConsumed()) return@withTimeoutOrNull true
            }
        }
        if (released == false) return@awaitEachGesture
        if (released == true) { onNavigate(direction); return@awaitEachGesture }
        val origin = position
        val travel = controlLength(origin, size.height.toFloat(), density)
        onContinuousStart(origin, direction); onNavigate(direction)
        try {
            var pressed = true
            while (pressed) {
                val progress = ((position.y - origin.y) / travel).coerceIn(0f, 1f)
                val releasedDuringTurn = withTimeoutOrNull((600f - progress * 480f).toLong()) {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.pressed && it.type != PointerType.Touch }) return@withTimeoutOrNull false
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        position = change.position; onContinuousMove(position)
                        if (change.changedToUpIgnoreConsumed()) return@withTimeoutOrNull true
                    }
                }
                if (releasedDuringTurn != null) pressed = false else onNavigate(direction)
            }
        } finally { onContinuousEnd() }
    }
}

@Composable
private fun PageNumber(page: String, pageCount: Int, modifier: Modifier = Modifier) = Text("$page / $pageCount", color = MutedInk, style = MaterialTheme.typography.labelMedium, modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .72f)).padding(horizontal = 10.dp, vertical = 5.dp))

@Composable
private fun ContinuousTurnIndicator(origin: Offset, position: Offset, direction: Int) = Canvas(Modifier.fillMaxSize()) {
    // Anchor to the long-press location, not the moving finger; both track and speed use
    // the same travel distance. Place it toward the page interior on either side.
    val radius = 12.dp.toPx()
    val x = (origin.x - direction * 30.dp.toPx()).coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
    val travel = controlLength(origin, size.height, density)
    val progress = ((position.y - origin.y) / travel).coerceIn(0f, 1f)
    val top = Offset(x, origin.y)
    val bottom = Offset(x, origin.y + travel)
    drawRoundRect(Color.White.copy(alpha = .66f), Offset(x - radius, origin.y - radius), Size(radius * 2, travel + radius * 2), CornerRadius(radius))
    drawLine(Ink.copy(alpha = .18f), top, bottom, 3.dp.toPx(), StrokeCap.Round)
    val thumb = Offset(x, origin.y + travel * progress)
    drawLine(Ink.copy(alpha = .55f), top, thumb, 3.dp.toPx(), StrokeCap.Round)
    drawCircle(Ink.copy(alpha = .75f), 5.dp.toPx(), thumb)
}

@Composable private fun LoadingScreen() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在打开书页…", color = MutedInk) }
@Composable private fun ErrorScreen(message: String, onOpenAnother: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) { Text(message, color = Ink, textAlign = TextAlign.Center); Spacer(Modifier.height(18.dp)); Button(onClick = onOpenAnother) { Text("选择其他 PDF") } } }

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator.vibrate(25)
}

private data class RenderedPages(val bitmap: Bitmap, val placements: List<PagePlacement>, val indices: List<Int?>)

private class PdfDocument(context: Context, uri: Uri) : AutoCloseable {
    private val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("无法读取文件")
    private val renderer = try { PdfRenderer(descriptor) } catch (failure: Exception) { descriptor.close(); throw failure }
    val pageCount = renderer.pageCount
    init { if (pageCount == 0) { renderer.close(); error("PDF 没有页面") } }
    @Synchronized
    fun render(indices: List<Int?>, targetWidth: Int): RenderedPages {
        val dimensions = indices.map { index -> index?.let { renderer.openPage(it).use { p -> Size(p.width.toFloat(), p.height.toFloat()) } } }
        val fallback = dimensions.first { it != null }!!
        val sizes = dimensions.map { it ?: fallback }
        val totalWidth = sizes.sumOf { it.width.toDouble() }.toFloat()
        val totalHeight = sizes.maxOf { it.height }
        // Cap either dimension and total pixels so continuous spreads have bounded memory.
        val scale = minOf(targetWidth.coerceAtMost(4096) / totalWidth, 4096f / totalHeight,
            kotlin.math.sqrt(6_000_000f / (totalWidth * totalHeight)))
        val bitmap = Bitmap.createBitmap((totalWidth * scale).roundToInt().coerceAtLeast(1), (totalHeight * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        var x = 0f
        val placements = mutableListOf<PagePlacement>()
        try {
            indices.forEachIndexed { slot, index ->
                val width = sizes[slot].width * scale
                if (index != null) renderer.openPage(index).use { p ->
                    placements += PagePlacement(index, x, 0f, p.width.toFloat(), p.height.toFloat(), scale)
                    val matrix = Matrix().apply { setScale(scale, scale); postTranslate(x, 0f) }
                    val clip = AndroidRect(x.roundToInt(), 0, (x + width).roundToInt().coerceAtMost(bitmap.width), (sizes[slot].height * scale).roundToInt().coerceAtMost(bitmap.height))
                    if (clip.width() > 0 && clip.height() > 0) p.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                x += width
            }
            return RenderedPages(bitmap, placements, indices)
        } catch (failure: Exception) { bitmap.recycle(); throw failure }
    }
    @Synchronized
    override fun close() { renderer.close(); descriptor.close() }
}

private val Paper = Color(0xFFF8F4EC)
private val Ink = Color(0xFF22201D)
private val MutedInk = Color(0xFF716C65)
