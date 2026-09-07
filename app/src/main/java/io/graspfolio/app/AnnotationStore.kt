package io.graspfolio.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract as DC
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

internal object InkCodec {
    fun encode(identity: String, strokes: List<InkStroke>): String = JSONObject().put("version", 1).put("document", identity)
        .put("strokes", JSONArray().apply { strokes.forEach { s -> put(JSONObject().put("id", s.id).put("page", s.page)
            .put("color", s.color).put("width", s.width.toDouble()).put("brush", s.brush).put("points", JSONArray().apply {
                s.points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.pressure.toDouble()).put(p.time)) }
            })) } }).toString()
    fun decode(text: String, identity: String): List<InkStroke> {
        val root = JSONObject(text)
        require(root.getInt("version") == 1 && root.getString("document") == identity) { "批注版本或 PDF 身份不匹配" }
        val items = root.getJSONArray("strokes")
        val result = (0 until items.length()).map { i ->
            val s = items.getJSONObject(i); val pts = s.getJSONArray("points")
            val points = (0 until pts.length()).map { j -> val p = pts.getJSONArray(j)
                InkPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat(), p.getLong(3)).also {
                    require(it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite())
                }
            }
            InkStroke(s.getString("id"), s.getInt("page"), points, s.getInt("color"), s.getDouble("width").toFloat(), s.getString("brush")).also {
                require(it.page >= 0 && it.points.isNotEmpty() && it.width.isFinite() && it.width > 0 && it.brush == "pressure")
            }
        }
        require(result.map { it.id }.distinct().size == result.size)
        return result
    }
}

/** One serial queue per document survives Activity recreation and prevents old writes winning. */
internal class AnnotationStore private constructor(private val context: Context, private val uri: Uri) {
    companion object {
        private val stores = mutableMapOf<String, AnnotationStore>()
        @Synchronized fun obtain(context: Context, uri: Uri) = stores.getOrPut(uri.toString()) { AnnotationStore(context.applicationContext, uri) }
    }
    var strokes by mutableStateOf<List<InkStroke>>(emptyList()); private set
    var ready by mutableStateOf(false); private set
    var status by mutableStateOf("正在加载批注…"); private set
    private val queue = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("annotation_folders", Context.MODE_PRIVATE)
    private lateinit var identity: String
    private lateinit var local: AtomicFile
    private var saved = emptyList<InkStroke>()
    private var base: String? = null
    private var dirty = false
    private var validatedFolder: Uri? = null
    private var validatedPdfStamp: String? = null
    private var tree: Uri? = prefs.getString(uri.toString(), null)?.let(Uri::parse)
    private fun publish(message: String) { main.post { status = message } }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pdfDigest(target: Uri): String {
        val hash = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(target)!!.use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    init { queue.execute {
        try {
            identity = pdfDigest(uri)
            val dir = File(context.filesDir, "annotations").apply { mkdirs() }
            local = AtomicFile(File(dir, digest(uri.toString().toByteArray()) + ".json"))
            if (local.baseFile.exists() || File(local.baseFile.path + ".bak").exists()) {
                val root = JSONObject(local.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                saved = InkCodec.decode(root.getString("data"), identity)
                base = root.optString("base").ifEmpty { null }; dirty = root.getBoolean("dirty")
            }
            sync()
            main.post { strokes = saved; ready = true }
        } catch (e: Exception) { publish("批注加载失败，已禁止书写以保护原数据：${e.message}") }
    } }
    fun replace(value: List<InkStroke>) {
        if (!ready) return
        strokes = value
        status = "正在保存…"
        queue.execute {
            saved = value; dirty = true
            try { persist(); sync() } catch (e: Exception) { publish("未同步：本地保存失败，请勿退出：${e.message}") }
        }
    }
    fun authorize(folder: Uri) {
        if (!ready) return
        try {
            context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ready = false
            queue.execute {
                tree = folder
                validatedFolder = null
                prefs.edit().putString(uri.toString(), folder.toString()).commit()
                sync()
                val loaded = saved
                main.post { strokes = loaded; ready = true }
            }
        } catch (e: Exception) { publish("未同步：目录授权失败：${e.message}") }
    }
    fun retry() {
        if (!ready) return
        ready = false
        queue.execute {
            try { persist(); validatedFolder = null; sync() } catch (e: Exception) { publish("未同步：${e.message}") }
            val loaded = saved
            main.post { strokes = loaded; ready = true }
        }
    }
    private fun persist() {
        val bytes = JSONObject().put("data", InkCodec.encode(identity, saved)).put("base", base ?: "").put("dirty", dirty).toString().toByteArray()
        val stream = local.startWrite()
        try { stream.write(bytes); local.finishWrite(stream) } catch (e: Exception) { local.failWrite(stream); throw e }
    }
    private fun sync() {
        val folder = tree ?: run { publish("未同步 · 本地保护中，请授权 PDF 所在目录"); return }
        try {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("无法读取 PDF 文件名")
            val sideName = name.substringBeforeLast('.', name) + ".graspfolio"
            val children = DC.buildChildDocumentsUriUsingTree(folder, DC.getTreeDocumentId(folder))
            val matches = mutableListOf<Pair<String, Uri>>()
            resolver.query(children, arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == name || c.getString(1) == sideName) matches += c.getString(1) to DC.buildDocumentUriUsingTree(folder, c.getString(0))
            }
            val pdf = matches.filter { it.first == name }.singleOrNull()?.second ?: error("请选择包含当前 PDF 的目录")
            val stamp = resolver.query(pdf, arrayOf(DC.Document.COLUMN_LAST_MODIFIED, DC.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1) && c.getLong(0) > 0) "$pdf:${c.getLong(0)}:${c.getLong(1)}" else null
            }
            if (validatedFolder != folder || stamp == null || validatedPdfStamp != stamp) {
                require(pdfDigest(pdf) == identity) { "所选目录中的同名 PDF 内容不同" }
                validatedFolder = folder
                validatedPdfStamp = stamp
            }
            val sides = matches.filter { it.first == sideName }; require(sides.size <= 1) { "发现多个同名批注文件" }
            var side = sides.singleOrNull()?.second
            val remote = side?.let { resolver.openInputStream(it)!!.use { input -> input.readBytes().toString(Charsets.UTF_8) } }
            if (remote != null) {
                val remoteStrokes = InkCodec.decode(remote, identity) // Validate before any overwrite.
                val hash = digest(remote.toByteArray())
                if (shouldLoadRemote(base, hash, dirty)) {
                    require(!ready) { "旁文件已变化，请通过菜单重试同步以载入" }
                    saved = remoteStrokes; base = hash; persist()
                    main.post { if (ready) strokes = remoteStrokes }
                }
            } else shouldLoadRemote(base, null, dirty)
            if (dirty || side == null) {
                val content = InkCodec.encode(identity, saved)
                // A generic MIME prevents providers from appending '.json' to '.graspfolio'.
                if (side == null) side = DC.createDocument(resolver, DC.buildDocumentUriUsingTree(folder, DC.getTreeDocumentId(folder)), "application/octet-stream", sideName) ?: error("无法创建旁文件")
                resolver.openOutputStream(side, "wt")!!.use { it.write(content.toByteArray()) }
                val verified = resolver.openInputStream(side)!!.use { it.readBytes() }
                require(digest(verified) == digest(content.toByteArray())) { "旁文件写入校验失败" }
                base = digest(verified); dirty = false; persist()
            }
            publish("已同步到 PDF 旁")
        } catch (e: Exception) { publish("未同步 · 本地保护中：${e.message}") }
    }
}
