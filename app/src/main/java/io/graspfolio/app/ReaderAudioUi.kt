package io.graspfolio.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ReaderAudioUi(val entries: @Composable (Dp) -> Unit, val controls: @Composable () -> Unit,
    val hasNotes: Boolean, val interactionVersion: Int, val collapse: () -> Unit)

@Composable
internal fun readerAudioUi(store: AnnotationStore, pages: List<Int>, menu: Boolean): ReaderAudioUi {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(store) { PageAudioPlayer(context) }
    val recorder = remember(store) { PageAudioRecorder(context.applicationContext, store) }
    val assets = remember(context) { AudioAssets(context) }
    val scope = rememberCoroutineScope()
    var busy by remember(store) { mutableStateOf(false) }
    var message by remember(store) { mutableStateOf<String?>(null) }
    var pendingPage by rememberSaveable { mutableIntStateOf(-1) }
    var choosePage by remember { mutableStateOf(false) }
    var addChoices by remember { mutableStateOf(false) }
    var recordingAction by rememberSaveable { mutableStateOf(false) }
    var recordingPage by rememberSaveable { mutableIntStateOf(-1) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && recordingPage >= 0) { player.pause(); recorder.start(recordingPage) }
        else if (!granted) message = "麦克风权限未开启，可在系统设置中允许后再录音"
        recordingPage = -1
    }
    var deleteNote by remember { mutableStateOf<AudioNote?>(null) }
    var interactionVersion by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetPage = pendingPage
        pendingPage = -1
        if (uri != null && targetPage >= 0) scope.launch {
            busy = true; message = "正在复制音频到第 ${targetPage + 1} 页…"
            try {
                val note = assets.import(uri, targetPage)
                store.addAudio(note)
                message = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = "插入失败：${failure.message ?: "无法读取此音频"}" }
            finally { busy = false }
        }
    }
    fun importAt(page: Int) { pendingPage = page; picker.launch(arrayOf("audio/*")) }
    fun startAt(page: Int) {
        if (!recordingAction) importAt(page)
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            player.pause(); recorder.start(page)
        } else { recordingPage = page; microphone.launch(Manifest.permission.RECORD_AUDIO) }
    }
    fun chooseAction(recording: Boolean) {
        addChoices = false; recordingAction = recording
        if (pages.size > 1) choosePage = true else pages.firstOrNull()?.let(::startAt)
    }
    DisposableEffect(player, recorder, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player.setForeground(false); recorder.stopAndSave() }
            if (event == Lifecycle.Event.ON_START) player.setForeground(true)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.close(); recorder.close() }
    }
    LaunchedEffect(pages, store.audio) {
        player.visiblePages(pages)
        if (player.selected != null && store.audio.none { it.id == player.selected?.id }) player.clear()
    }
    LaunchedEffect(store.ready) { if (store.ready) recorder.recoverPending() }
    LaunchedEffect(recorder.recording) { while (recorder.recording) { recorder.tick(); delay(200) } }
    LaunchedEffect(player.playing) { while (player.playing) { player.tick(); delay(200) } }
    val notes = pageAudio(store.audio, pages)
    deleteNote?.let { note -> AlertDialog(onDismissRequest = { deleteNote = null }, title = { Text("移除这条音频笔记？") },
        text = { Text(note.name) }, confirmButton = { TextButton({
            store.removeAudio(note.id); if (player.selected?.id == note.id) player.clear(); deleteNote = null
        }, enabled = store.ready) { Text("移除") } }, dismissButton = { TextButton({ deleteNote = null }) { Text("取消") } }) }
    return ReaderAudioUi(entries = { width ->
        Box {
            AudioEntries(notes, player.selected?.id, player.playing, menu, !busy && !recorder.recording && !recorder.saving && store.ready && pages.isNotEmpty(), width,
                onImport = { interactionVersion++; addChoices = !addChoices },
                onSelect = { interactionVersion++; addChoices = false; player.select(it) }, canPlay = !recorder.recording)
            if (addChoices || choosePage) {
                val offset = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.unit.IntOffset(0, 48.dp.roundToPx()) }
                androidx.compose.ui.window.Popup(alignment = Alignment.TopStart, offset = offset,
                    onDismissRequest = { addChoices = false; choosePage = false },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                    Column(Modifier.width(168.dp).liquidGlass(18, .85f).padding(6.dp)) {
                        if (choosePage) pages.forEach { page ->
                            TextButton({ choosePage = false; startAt(page) }, Modifier.fillMaxWidth().height(38.dp)) {
                                Text("插入第 ${page + 1} 页", color = GlassInk, fontSize = 13.sp)
                            }
                        } else {
                            TextButton({ chooseAction(false) }, Modifier.fillMaxWidth().height(38.dp)) { Text("导入音频", color = GlassInk, fontSize = 13.sp) }
                            TextButton({ chooseAction(true) }, Modifier.fillMaxWidth().height(38.dp)) { Text("开始录音", color = GlassInk, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }, controls = {
        if (recorder.recording || recorder.saving || recorder.error != null) {
            Row(Modifier.widthIn(max = 400.dp).fillMaxWidth().liquidGlass(24, .5f).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recorder.recording) {
                    Text("●", color = androidx.compose.ui.graphics.Color(0xffc57968), modifier = Modifier.padding(8.dp))
                    Text("第 ${recorder.page + 1} 页 · 录音 ${audioTime(recorder.elapsedMs)}", color = GlassInk, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    AudioIconButton("stop", "结束录音并保存", glass = false, onClick = recorder::stopAndSave)
                } else {
                    Text(if (recorder.saving) "正在保存录音…" else recorder.error.orEmpty(), color = GlassInk, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    if (!recorder.saving) {
                        AudioIconButton("play", "重试恢复录音", glass = false, onClick = { recorder.recoverPending() })
                        AudioIconButton("close", "收起录音提示", glass = false, onClick = recorder::dismissError)
                    }
                }
            }
        } else if (busy || message != null) Row(Modifier.widthIn(max = 380.dp).fillMaxWidth().liquidGlass(22, .6f).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = GlassInk, strokeWidth = 2.dp)
            Text(message.orEmpty(), color = GlassInk, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 12.sp)
            if (!busy) AudioIconButton("close", "关闭音频提示", onClick = { message = null })
        } else if (player.expanded) player.selected?.let { note ->
            AudioPlaybackControls(note, player.playing, player.preparing, player.position, player.duration, player.error,
                onToggle = player::toggle, onSeek = player::seek, onClose = { player.expanded = false },
                onRemove = if (menu) ({ deleteNote = note }) else null)
        }
    }, hasNotes = notes.isNotEmpty() || message != null || busy || recorder.recording || recorder.saving || recorder.error != null, interactionVersion = interactionVersion,
        collapse = { player.expanded = false; addChoices = false; choosePage = false })
}

@Composable
internal fun AudioEntries(notes: List<AudioNote>, selectedId: String?, playing: Boolean, showImport: Boolean,
    canImport: Boolean, width: Dp, onImport: () -> Unit, onSelect: (AudioNote) -> Unit, canPlay: Boolean = true) {
    Row(Modifier.widthIn(max = width).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showImport) AudioIconButton("import", "插入音频", enabled = canImport, onClick = onImport)
        notes.forEach { note -> key(note.id) {
            AudioIconButton("wave", "播放音频：${note.name}，第 ${note.page + 1} 页", selected = note.id == selectedId,
                onClick = { onSelect(note) }, enabled = canPlay, playing = playing && note.id == selectedId)
        } }
    }
}

@Composable
internal fun AudioPlaybackControls(note: AudioNote, playing: Boolean, preparing: Boolean, position: Int, duration: Int,
    error: String?, onToggle: () -> Unit, onSeek: (Int) -> Unit, onClose: () -> Unit, onRemove: (() -> Unit)? = null) {
    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().liquidGlass(24, .42f).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (preparing) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "正在加载音频" }, color = GlassInk, strokeWidth = 2.dp)
            } else AudioIconButton(if (playing) "pause" else "play", "播放或暂停音频", glass = false, onClick = onToggle, state = if (playing) "正在播放" else "已暂停")
            var dragging by remember(note.id) { mutableStateOf(false) }
            var seek by remember(note.id) { mutableFloatStateOf(position.toFloat()) }
            SlimSlider(if (dragging) seek else position.toFloat(), { dragging = true; seek = it },
                0f..duration.coerceAtLeast(1).toFloat(), Modifier.weight(1f).semantics { contentDescription = "音频播放进度" },
                onValueChangeFinished = { onSeek(seek.toInt()); dragging = false }, enabled = !preparing && error == null)
            Text("${audioTime(if (dragging) seek.toInt() else position)} / ${audioTime(duration)}", color = GlassInk,
                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
            AudioIconButton("collapse", "收起音频控制", glass = false, onClick = onClose)
        }
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error ?: "第 ${note.page + 1} 页 · ${note.name}", color = GlassMuted, fontSize = 11.sp,
                maxLines = if (error != null) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onRemove != null) AudioIconButton("delete", "移除音频笔记", glass = false, onClick = onRemove)
        }
    }
}

@Composable
internal fun AudioIconButton(icon: String, label: String, selected: Boolean = false, enabled: Boolean = true,
    glass: Boolean = true, playing: Boolean = false, state: String? = null, onClick: () -> Unit) {
    val surface = if (glass) Modifier.liquidGlass(24) else Modifier
    Box(Modifier.size(40.dp).then(surface).then(if (enabled) Modifier.glassClickable(selected = selected, onClick = onClick) else Modifier.semantics { disabled() })
        .semantics { contentDescription = label; state?.let { stateDescription = it } }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(23.dp)) {
            val ink = GlassInk.copy(alpha = if (enabled) 1f else .35f)
            fun p(x: Float, y: Float) = Offset(x / 24 * size.width, y / 24 * size.height)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(ink, p(x,y), p(x2,y2), 1.8.dp.toPx(), StrokeCap.Round)
            when (icon) {
                "import" -> {
                    val arch = Path().apply { moveTo(p(4f,15f).x,p(4f,15f).y); lineTo(p(4f,10f).x,p(4f,10f).y)
                        cubicTo(p(4f,1f).x,p(4f,1f).y,p(17f,1f).x,p(17f,1f).y,p(17f,10f).x,p(17f,10f).y); lineTo(p(17f,15f).x,p(17f,15f).y) }
                    drawPath(arch, ink, style = Stroke(1.8.dp.toPx()))
                    line(4f,13f,7f,13f); line(7f,13f,7f,20f); line(7f,20f,4f,20f); line(4f,20f,4f,13f)
                    line(14f,13f,17f,13f); line(17f,13f,17f,20f); line(17f,20f,14f,20f); line(14f,20f,14f,13f)
                    line(20f,3f,20f,9f); line(17f,6f,23f,6f)
                }
                "wave" -> { for ((x,h) in listOf(4f to 4f, 9f to 9f, 14f to 6f, 19f to 3f)) line(x,12-h,x,12+h)
                    if (playing) drawCircle(ink, 1.5.dp.toPx(), p(22f,21f)) }
                "stop" -> drawRect(ink, p(6f, 6f), androidx.compose.ui.geometry.Size(size.width / 2, size.height / 2))
                "pause" -> { line(8f,6f,8f,18f); line(16f,6f,16f,18f) }
                "play" -> drawPath(Path().apply { moveTo(p(8f,5f).x,p(8f,5f).y); lineTo(p(19f,12f).x,p(19f,12f).y); lineTo(p(8f,19f).x,p(8f,19f).y); close() }, ink)
                "delete" -> { line(5f,6f,19f,6f); line(9f,3f,15f,3f); line(7f,8f,8f,21f); line(8f,21f,16f,21f); line(16f,21f,17f,8f); line(11f,10f,11f,18f); line(14f,10f,14f,18f) }
                "collapse" -> { line(6f,14f,12f,8f); line(12f,8f,18f,14f) }
                else -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            }
        }
    }
}

@Composable
internal fun ImmersiveAudio(ui: ReaderAudioUi, writingToolbar: Boolean) {
    if (!ui.hasNotes) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val top = if (writingToolbar && maxWidth < 700.dp) 64.dp else 16.dp
        Column(Modifier.align(Alignment.TopStart).padding(top = top, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.entries(if (writingToolbar && availableWidth >= 700.dp) ((availableWidth - 340.dp) / 2 - 24.dp).coerceAtMost(200.dp) else availableWidth - 32.dp)
            ui.controls()
        }
    }
}
