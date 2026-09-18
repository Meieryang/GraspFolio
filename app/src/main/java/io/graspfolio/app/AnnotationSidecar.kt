package io.graspfolio.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import android.provider.OpenableColumns
import java.security.MessageDigest
import java.io.File

internal interface AnnotationBackend {
    fun openJournal(): AnnotationJournal
    fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean): SidecarResult
    fun afterAcknowledged(folder: Uri, snapshot: DurableInk) {}
}

internal class SafAnnotationBackend(private val context: Context, private val uri: Uri) : AnnotationBackend {
    private lateinit var sidecar: AnnotationSidecar
    override fun openJournal(): AnnotationJournal {
        val identity = pdfDigest(context, uri)
        val dir = File(context.filesDir, "annotations").apply { check(exists() || mkdirs()) }
        val journalFile = File(dir, inkDigest(uri.toString().toByteArray()) + ".json")
        sidecar = AnnotationSidecar(context, uri, identity, File(dir, journalFile.name + ".pages"))
        return AnnotationJournal(journalFile, identity, pruneOnOpen = true)
    }
    override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean) = sidecar.sync(folder, snapshot, allowLoad)
    override fun afterAcknowledged(folder: Uri, snapshot: DurableInk) = sidecar.afterAcknowledged(folder, snapshot)
}

internal fun inkDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun pdfDigest(context: Context, target: Uri): String {
    val hash = MessageDigest.getInstance("SHA-256")
    context.contentResolver.openInputStream(target)!!.use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal data class SidecarResult(val hash: String, val imported: List<InkStroke>? = null,
    val progress: ReadingProgress? = null, val acknowledgedRevision: Long? = null, val audio: List<AudioNote> = emptyList(), val needsUpgrade: Boolean = false)

/** Only the external-sync thread accesses this object. No access to mutable local-writer state. */
internal class AnnotationSidecar(private val context: Context, private val uri: Uri, private val identity: String,
    private val blobs: File = File(context.filesDir, "sidecar-imports/$identity")) {
    private val audioAssets = AudioAssets(context)
    private var validatedFolder: Uri? = null
    private var lastTransaction: SidecarTransaction? = null
    private var transactionFolder: Uri? = null
    private var canonicalName: String? = null
    private var validatedPdfStamp: String? = null
    fun afterAcknowledged(folder: Uri, snapshot: DurableInk) {
        if (transactionFolder != folder) return
        lastTransaction?.recover(snapshot.base, snapshot.revision, snapshot.syncedRevision)
        val name = canonicalName ?: return
        val files = SafSidecarFiles(context, folder)
        if (!snapshot.dirty && files.open(name)?.use(::streamInkDigest) == snapshot.base &&
            files.open(name)?.use(SidecarBundle::isBundle) == true) audioAssets.retireLegacyAttachments(folder, snapshot.audio)
    }
    fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean): SidecarResult {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("无法读取 PDF 文件名")
        val sideName = name.substringBeforeLast('.', name) + ".graspfolio"
        val children = DC.buildChildDocumentsUriUsingTree(folder, DC.getTreeDocumentId(folder))
        val matches = mutableListOf<Pair<String, Uri>>()
        resolver.query(children, arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name || c.getString(1) == sideName)
                matches += c.getString(1) to DC.buildDocumentUriUsingTree(folder, c.getString(0))
        }
        val pdf = matches.filter { it.first == name }.singleOrNull()?.second ?: error("请选择包含当前 PDF 的目录")
        val stamp = resolver.query(pdf, arrayOf(DC.Document.COLUMN_LAST_MODIFIED, DC.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1) && c.getLong(0) > 0) "$pdf:${c.getLong(0)}:${c.getLong(1)}" else null
        }
        if (allowLoad || validatedFolder != folder || stamp == null || validatedPdfStamp != stamp) {
            require(pdfDigest(context, pdf) == identity) { "所选目录中的同名 PDF 内容不同" }
            validatedFolder = folder; validatedPdfStamp = stamp
        }
        val files = SafSidecarFiles(context, folder)
        val transactionDir = File(context.filesDir, "sidecar-transactions").apply { check(exists() || mkdirs()) }
        val key = inkDigest("$uri|$folder|$identity".toByteArray())
        val transaction = SidecarTransaction(files, AtomicSidecarTransactionLog(File(transactionDir, "$key.json")), sideName)
        lastTransaction = transaction; transactionFolder = folder; canonicalName = sideName
        transaction.recover(snapshot.base, snapshot.revision, snapshot.syncedRevision)?.let {
            return SidecarResult(it.hash, acknowledgedRevision = it.revision)
        }
        val remoteHash = files.open(sideName)?.use(::streamInkDigest)
        if (shouldLoadRemote(snapshot.base, remoteHash, !snapshot.canImportRemote)) {
            require(allowLoad) { "旁文件已变化，请通过菜单重试同步以载入" }
            val bundled = files.open(sideName)!!.use(SidecarBundle::isBundle)
            val document = files.open(sideName)!!.use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val stream = java.security.DigestInputStream(input, digest)
                val decoded = if (bundled) SidecarBundle.read(stream, identity, blobs, audioAssets)
                    else android.util.JsonReader(stream.reader().buffered()).use { reader ->
                        StreamingInk.read(reader, identity, blobs).also { require(reader.peek() == android.util.JsonToken.END_DOCUMENT) }
                    }
                require(files.open(sideName)!!.use(::streamInkDigest) == remoteHash) { "旁文件在载入期间发生变化，请重试" }
                decoded
            }
            if (!bundled) audioAssets.download(folder, document.audio)
            return SidecarResult(remoteHash!!, document.strokes, document.progress, audio = document.audio, needsUpgrade = !bundled)
        }
        val bundled = remoteHash != null && files.open(sideName)!!.use(SidecarBundle::isBundle)
        if (bundled && !audioAssets.hasVerified(snapshot.audio)) {
            files.open(sideName)!!.use { SidecarBundle.read(it, identity, blobs, audioAssets) }
            require(files.open(sideName)!!.use(::streamInkDigest) == remoteHash) { "旁文件在读取期间发生变化" }
        } else if (!bundled) audioAssets.download(folder, snapshot.audio)
        if (!snapshot.dirty && bundled) return SidecarResult(remoteHash!!)

        val content = File.createTempFile("ink-sync-", ".json", context.cacheDir)
        return try {
            InkPerformance.measure("sidecar_encode") {
                content.outputStream().use { SidecarBundle.write(it, identity, InkDocument(snapshot.strokes, snapshot.progress ?: ReadingProgress(), snapshot.audio), audioAssets) }
            }
            SidecarResult(transaction.write(remoteHash, content, snapshot.revision))
        }
        catch (failure: Exception) {
            // If the provider is still accessible, restore the old name immediately. A process
            // death follows the same recovery path on the next open/retry instead.
            try {
                transaction.recover(snapshot.base, snapshot.revision, snapshot.syncedRevision)?.let {
                    return SidecarResult(it.hash, acknowledgedRevision = it.revision)
                }
            } catch (recoveryFailure: Exception) { failure.addSuppressed(recoveryFailure) }
            throw failure
        } finally { content.delete() }
    }
}

internal class SafSidecarFiles(private val context: Context, private val folder: Uri) : SidecarFiles {
    private val resolver get() = context.contentResolver
    private val parent get() = DC.buildDocumentUriUsingTree(folder, DC.getTreeDocumentId(folder))
    private fun find(name: String): Uri? {
        val children = DC.buildChildDocumentsUriUsingTree(folder, DC.getTreeDocumentId(folder))
        val found = mutableListOf<Uri>()
        resolver.query(children, arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) found += DC.buildDocumentUriUsingTree(folder, c.getString(0))
        } ?: error("无法读取保存目录")
        require(found.size <= 1) { "目录中有多个同名文件：$name" }
        return found.singleOrNull()
    }
    override fun open(name: String): java.io.InputStream? = find(name)?.let { target ->
        resolver.openInputStream(target) ?: error("无法读取保存文件")
    }
    override fun createFrom(name: String, input: java.io.InputStream) {
        require(find(name) == null) { "保存临时文件已存在" }
        val target = DC.createDocument(resolver, parent, "application/octet-stream", name) ?: error("无法创建保存临时文件")
        resolver.openOutputStream(target, "w")?.use { input.copyTo(it, 65536) } ?: error("无法写入保存临时文件")
    }
    override fun read(name: String): ByteArray? = find(name)?.let { target ->
        resolver.openInputStream(target)?.use { it.readBytes() } ?: error("无法读取保存文件")
    }
    override fun create(name: String, bytes: ByteArray) {
        require(find(name) == null) { "保存临时文件已存在" }
        val target = DC.createDocument(resolver, parent, "application/octet-stream", name) ?: error("无法创建保存临时文件")
        resolver.openOutputStream(target, "w")?.use { it.write(bytes) } ?: error("无法写入保存临时文件")
    }
    override fun canRename(name: String): Boolean {
        val target = find(name) ?: return false
        return resolver.query(target, arrayOf(DC.Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) and DC.Document.FLAG_SUPPORTS_RENAME != 0
        } == true
    }
    override fun rename(from: String, to: String) {
        require(find(to) == null) { "保存目标已存在，未覆盖" }
        val source = find(from) ?: error("保存源文件不见了")
        check(DC.renameDocument(resolver, source, to) != null) { "存储位置未能重命名保存文件" }
    }
    override fun delete(name: String) { find(name)?.let { check(DC.deleteDocument(resolver, it)) { "无法清理保存临时文件" } } }
}

internal class AtomicSidecarTransactionLog(file: File) : SidecarTransactionLog {
    private val atomic = android.util.AtomicFile(file)
    override fun load(): PendingSidecar? {
        val text = try { atomic.openRead().bufferedReader().use { it.readText() } }
            catch (_: java.io.FileNotFoundException) { return null }
        val root = org.json.JSONObject(text)
        require(root.getInt("version") == 1)
        val pending = PendingSidecar(root.optString("old").ifEmpty { null }, root.getString("new"),
            root.getLong("revision"), root.getString("staged"), root.getString("backup"))
        require(pending.revision >= 0 && pending.newHash.matches(Regex("[0-9a-f]{64}")))
        require(pending.oldHash == null || pending.oldHash.matches(Regex("[0-9a-f]{64}")))
        return pending
    }
    override fun save(value: PendingSidecar) {
        val text = org.json.JSONObject().put("version", 1).put("old", value.oldHash ?: "").put("new", value.newHash)
            .put("revision", value.revision).put("staged", value.staged).put("backup", value.backup).toString()
        val output = atomic.startWrite()
        try { output.write(text.toByteArray()); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    override fun clear() = atomic.delete()
}
