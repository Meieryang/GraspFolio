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
}

internal class SafAnnotationBackend(private val context: Context, private val uri: Uri) : AnnotationBackend {
    private lateinit var sidecar: AnnotationSidecar
    override fun openJournal(): AnnotationJournal {
        val identity = pdfDigest(context, uri)
        val dir = File(context.filesDir, "annotations").apply { check(exists() || mkdirs()) }
        sidecar = AnnotationSidecar(context, uri, identity)
        return AnnotationJournal(File(dir, inkDigest(uri.toString().toByteArray()) + ".json"), identity)
    }
    override fun sync(folder: Uri, snapshot: DurableInk, allowLoad: Boolean) = sidecar.sync(folder, snapshot, allowLoad)
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

internal data class SidecarResult(val hash: String, val imported: List<InkStroke>? = null)

/** Only the external-sync thread accesses this object. No access to mutable local-writer state. */
internal class AnnotationSidecar(private val context: Context, private val uri: Uri, private val identity: String) {
    private var validatedFolder: Uri? = null
    private var validatedPdfStamp: String? = null
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
        val sides = matches.filter { it.first == sideName }
        require(sides.size <= 1) { "发现多个同名批注文件" }
        var side = sides.singleOrNull()?.second
        val remote = side?.let { resolver.openInputStream(it)!!.use { input -> input.readBytes() } }
        val remoteHash = remote?.let(::inkDigest)
        if (shouldLoadRemote(snapshot.base, remoteHash, snapshot.dirty)) {
            require(allowLoad) { "旁文件已变化，请通过菜单重试同步以载入" }
            return SidecarResult(remoteHash!!, InkCodec.decode(remote!!.toString(Charsets.UTF_8), identity))
        }
        // An unchanged hash is proof that this is the previously validated file: no JSON parse.
        if (!snapshot.dirty && side != null) return SidecarResult(remoteHash!!)
        val content = InkPerformance.measure("sidecar_encode") { InkCodec.encode(identity, snapshot.strokes).toByteArray() }
        if (side == null) side = DC.createDocument(resolver, DC.buildDocumentUriUsingTree(folder, DC.getTreeDocumentId(folder)),
            "application/octet-stream", sideName) ?: error("无法创建旁文件")
        resolver.openOutputStream(side, "wt")!!.use { it.write(content) }
        val verified = resolver.openInputStream(side)!!.use { it.readBytes() }
        val hash = inkDigest(verified)
        require(hash == inkDigest(content)) { "旁文件写入校验失败" }
        return SidecarResult(hash)
    }
}
