package io.graspfolio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryScreen(onOpen: () -> Unit, onRead: (Uri) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { LibraryRepository(context.applicationContext) }
    var folder by remember { mutableStateOf(repository.folder) }
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(folder, refresh) {
        scanning = true
        try { snapshot = repository.load() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { message = "无法读取书库，请检查文件夹权限后重试" }
        finally { scanning = false }
    }
    val selectFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            repository.folder = uri; folder = uri; refresh++; message = null
        } catch (_: SecurityException) { message = "无法保留该文件夹的访问权限，请重新选择" }
    }
    val books = snapshot?.books
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("library") }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(section) { focus.clearFocus(force = true); keyboard?.hide() }
    val settings = section == "settings"
    val recent = section == "recent"
    val source = if (recent) snapshot?.recent.orEmpty() else books.orEmpty()
    val visible = source.filter { it.title.contains(query, true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LeatherBackground()
        val wide = maxWidth >= 700.dp
        val compact = maxWidth < 480.dp
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(if (compact) 14.dp else 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (wide) Column(Modifier.width(164.dp).fillMaxHeight().liquidGlass(32, .14f).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.library_mark), null, Modifier.padding(top = 8.dp).size(82.dp))
                Text("GraspFolio 掌页", color = GlassInk, fontWeight = FontWeight.SemiBold)
                Text("握住书页，自在阅读。", color = GlassMuted, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                GlassAction("图书库", { section = "library" }, Modifier.fillMaxWidth(), selected = section == "library")
                GlassAction("最近打开", { section = "recent" }, Modifier.fillMaxWidth(), selected = recent)
                GlassAction("打开 PDF", onOpen, Modifier.fillMaxWidth())
                GlassAction("设置", { section = "settings" }, Modifier.fillMaxWidth(), selected = settings)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(50.dp).liquidGlass().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                        BasicTextField(query, { query = it; if (settings) section = "library" }, singleLine = true,
                            textStyle = TextStyle(color = GlassInk, fontSize = 15.sp), cursorBrush = SolidColor(GlassInk),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索书籍" },
                            decorationBox = { field -> if (query.isEmpty()) Text("搜索书籍…", color = GlassMuted, fontSize = 15.sp); field() })
                    }
                    if (!compact) GlassAction("打开 PDF", onOpen)
                    if (!wide) GlassAction("最近打开", { section = "recent" }, selected = recent)
                    if (!wide) GlassAction(if (settings) "书库" else "设置", { section = if (settings) "library" else "settings" })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (settings) "设置" else if (recent) "最近打开" else "图书库", color = GlassInk, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold, fontSize = if (compact) 32.sp else 40.sp)
                    if (!settings) Text("${visible.size} 本书", color = GlassMuted, fontSize = 13.sp)
                }
                if (message != null || snapshot?.warning != null) {
                    Text(message ?: snapshot?.warning.orEmpty(), color = GlassInk, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().liquidGlass(18).padding(12.dp))
                }
                if (settings) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).liquidGlass(28, .42f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("图书库扫描文件夹", color = GlassInk, style = MaterialTheme.typography.titleMedium)
                        Text(folder?.let(repository::folderLabel) ?: "尚未设置扫描路径", color = GlassMuted,
                            modifier = Modifier.semantics { contentDescription = "图书库扫描路径" })
                        Text("自动扫描所选文件夹及子文件夹中的 PDF，原文件保留在原位置。", color = GlassMuted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassAction(if (folder == null) "选择文件夹" else "更换文件夹", { selectFolder.launch(folder) })
                            if (folder != null) GlassAction("移除路径", { repository.folder = null; folder = null; message = null })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!scanning) GlassAction("重新扫描", { message = null; refresh++ })
                            Text(if (scanning) "正在扫描…" else "书库共 ${books?.size ?: 0} 本书", color = GlassMuted, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = GlassMuted.copy(alpha = .15f))
                        Text("阅读与外观", color = GlassInk, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("降低透明度", color = GlassInk)
                            GlassToggle("降低透明度", LocalSolidGlass.current, LocalSetSolidGlass.current)
                        }
                        Text("在书页上落笔即可标注。点击已选画笔调整颜色和粗细；长按书角可连续翻页。", color = GlassMuted)
                        Text("PDF 与批注保存在本机或你授权的目录。", color = GlassMuted)
                        GlassAction("打开本地 PDF", onOpen)
                    }
                } else if (snapshot == null || visible.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f).liquidGlass(30, .12f).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Image(painterResource(R.drawable.library_mark), null, Modifier.size(100.dp))
                        Spacer(Modifier.height(20.dp))
                        Text(if (scanning && snapshot == null) "正在整理书库…" else if (query.isNotEmpty()) "没有找到这本书" else if (recent) "还没有打开记录" else "让阅读留下思考", color = GlassInk, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        Text(if (query.isNotEmpty()) "试试其他书名" else if (recent) "打开过的书籍会按最近时间显示在这里" else "打开 PDF，或在设置中选择图书库文件夹", color = GlassMuted)
                        Spacer(Modifier.height(24.dp))
                        GlassAction("打开本地 PDF", onOpen)
                    }
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(if (compact) 145.dp else 205.dp), Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(visible, key = { it.uri.toString() }) { book -> BookCard(book) { onRead(book.uri) } }
                    }
                    if (compact) GlassAction("打开 PDF", onOpen, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private val coverWorkers = Semaphore(2)

@Composable
private fun BookCard(book: LibraryBook, onClick: () -> Unit) {
    val context = LocalContext.current
    var pageCount by remember(book.uri) { mutableIntStateOf(0) }
    val cover by produceState<Bitmap?>(null, book.uri, book.modified, book.bytes) {
        val result = withContext(Dispatchers.IO) { coverWorkers.withPermit {
            try {
                context.contentResolver.openFileDescriptor(book.uri, "r")?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount == 0) return@use null
                        val count = renderer.pageCount
                        renderer.openPage(0).use { page ->
                            val scale = minOf(300f / page.width, 400f / page.height)
                            val image = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            image.eraseColor(android.graphics.Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image to count
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } }
        pageCount = result?.second ?: 0; value = result?.first
    }
    val progress = remember(book.uri) { ReadingProgressStore(context).load(book.uri.toString()) }
    Column(Modifier.fillMaxWidth().liquidGlass(26, .15f).glassClickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.76f).shadow(8.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
            .background(Color(0xfffffcf7)), contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("PDF", color = GlassMuted, fontFamily = FontFamily.Serif, fontSize = 28.sp)
        }
        Text(book.title, color = GlassInk, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val fraction = if (pageCount > 0) ((progress.page + 1f) / pageCount).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Color(0xff567896), trackColor = GlassMuted.copy(alpha = .18f))
        Text(if (pageCount > 0) "第 ${(progress.page + 1).coerceAtMost(pageCount)} / $pageCount 页" else "点击继续阅读", color = GlassMuted, fontSize = 12.sp)
    }
}
