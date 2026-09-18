package io.graspfolio.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract as DC
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Immutable, content-addressed audio. Media bytes never enter the per-stroke JSON journal. */
internal class AudioAssets(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val directory get() = File(context.filesDir, "audio-assets").apply { check(exists() || mkdirs()) }
    private val verifiedRemote = mutableSetOf<String>() // Sidecar worker only.
    fun file(note: AudioNote): File = File(directory, "${note.hash}.audio")
    suspend fun import(source: Uri, page: Int, nameOverride: String? = null, noteId: String = UUID.randomUUID().toString()): AudioNote = withContext(Dispatchers.IO) {
        val name = nameOverride ?: resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(256)?.takeIf { it.isNotBlank() } ?: "音频"
        val temporary = File(directory, ".import-${UUID.randomUUID()}")
        try {
            val copied = resolver.openInputStream(source)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    copy(input, output) { ensureActive() }.also { output.fd.sync() }
                }
            } ?: error("无法打开所选音频")
            ensureActive()
            val metadata = MediaMetadataRetriever()
            val duration = try {
                metadata.setDataSource(temporary.path)
                require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "所选文件不含可播放音频" }
                val ms = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                require(ms in 1..Int.MAX_VALUE.toLong()) { "无法读取音频时长" }
                ms.toInt()
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("无法读取音频，请选择设备支持的完整音频文件", failure)
            } finally { metadata.release() }
            ensureActive()
            val note = AudioNote(noteId, page, name, copied.first, duration, copied.second)
            commit(temporary, note)
            note
        } finally { temporary.delete() }
    }
    private fun commit(temporary: File, note: AudioNote) {
        val target = file(note)
        if (target.exists() && target.length() == note.bytes && digest(target) == note.hash) return
        // Atomic rename inside the private directory. The imported bytes are fsynced first.
        check(temporary.renameTo(target)) { "无法保存音频副本" }
    }
    fun writeEmbedded(note: AudioNote, output: OutputStream) {
        val source = file(note)
        require(source.exists() && source.length() == note.bytes) { "缺少本地音频：${note.name}" }
        val result = source.inputStream().use { copy(it, output) }
        require(result.first == note.hash && result.second == note.bytes) { "本地音频损坏：${note.name}" }
    }
    fun readEmbedded(note: AudioNote, input: InputStream) {
        val temporary = File(directory, ".embedded-${UUID.randomUUID()}")
        try {
            val result = FileOutputStream(temporary).use { output -> copy(input, output).also { output.fd.sync() } }
            require(result.first == note.hash && result.second == note.bytes) { "内嵌音频损坏：${note.name}" }
            commit(temporary, note)
        } finally { temporary.delete() }
    }
    fun hasVerified(notes: List<AudioNote>) = notes.distinctBy { it.hash }.all {
        val source = file(it)
        source.exists() && source.length() == it.bytes && digest(source) == it.hash
    }
    /** Only retire known audio after every canonical sidecar in this folder is self-contained. */
    fun retireLegacyAttachments(folder: Uri, notes: List<AudioNote>) {
        if (notes.isEmpty()) return
        var canRetire = true
        resolver.query(DC.buildChildDocumentsUriUsingTree(folder, DC.getTreeDocumentId(folder)),
            arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (!c.getString(1).endsWith(".graspfolio")) continue
                val uri = DC.buildDocumentUriUsingTree(folder, c.getString(0))
                if (resolver.openInputStream(uri)?.use(SidecarBundle::isBundle) != true) canRetire = false
            }
        } ?: return
        if (!canRetire) return
        val existing = children(folder)
        notes.distinctBy { it.hash }.forEach { note ->
            val remote = existing[assetName(note)] ?: return@forEach
            // Private content-addressed bytes remain as recovery and playback cache.
            require(hasVerified(listOf(note))) { "音频本地恢复副本不可用，保留旧附件" }
            verify(remote, note)
            check(DC.deleteDocument(resolver, remote.uri)) { "旧音频附件暂未清理" }
        }
    }
    private fun assetName(note: AudioNote) = "graspfolio-audio-${note.hash}.audio"
    private data class Remote(val uri: Uri, val size: Long, val modified: Long)
    private fun children(folder: Uri): Map<String, Remote> {
        val result = mutableMapOf<String, Remote>()
        resolver.query(DC.buildChildDocumentsUriUsingTree(folder, DC.getTreeDocumentId(folder)),
            arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME, DC.Document.COLUMN_SIZE, DC.Document.COLUMN_LAST_MODIFIED),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (!name.startsWith("graspfolio-audio-")) continue
                require(name !in result) { "目录中存在同名音频附件" }
                result[name] = Remote(DC.buildDocumentUriUsingTree(folder, c.getString(0)),
                    if (c.isNull(2)) -1 else c.getLong(2), if (c.isNull(3)) 0 else c.getLong(3))
            }
        } ?: error("无法读取音频附件目录")
        return result
    }
    private fun verify(remote: Remote, note: AudioNote) {
        val key = if (remote.modified > 0 && remote.size == note.bytes) "${remote.uri}:${remote.size}:${remote.modified}" else null
        if (key != null && key in verifiedRemote) return
        val result = resolver.openInputStream(remote.uri)?.use { copy(it, null) } ?: error("无法校验音频附件")
        require(result.first == note.hash && result.second == note.bytes) { "音频附件损坏：${note.name}" }
        if (key != null) verifiedRemote += key
    }
    fun upload(folder: Uri, notes: List<AudioNote>) {
        if (notes.isEmpty()) return
        val existing = children(folder)
        val parent = DC.buildDocumentUriUsingTree(folder, DC.getTreeDocumentId(folder))
        for (note in notes.distinctBy { it.hash }) {
            val name = assetName(note)
            existing[name]?.let { verify(it, note) } ?: run {
                val source = file(note)
                require(source.exists() && source.length() == note.bytes) { "缺少本地音频：${note.name}" }
                var staged: Uri? = DC.createDocument(resolver, parent, "application/octet-stream", "$name.pending-${UUID.randomUUID()}")
                    ?: error("无法创建音频附件")
                try {
                    val result = source.inputStream().use { input ->
                        resolver.openOutputStream(staged!!, "w")?.use { copy(input, it) } ?: error("无法写入音频附件")
                    }
                    require(result.first == note.hash && result.second == note.bytes) { "本地音频副本损坏" }
                    verify(Remote(staged!!, -1, 0), note)
                    val racing = children(folder)[name]
                    if (racing != null) { verify(racing, note) }
                    else {
                        check(DC.renameDocument(resolver, staged!!, name) != null) { "存储位置不支持安全保存音频附件" }
                        staged = null
                        verify(children(folder)[name] ?: error("音频附件保存名称不一致"), note)
                    }
                } finally { staged?.let { runCatching { DC.deleteDocument(resolver, it) } } }
            }
        }
    }
    fun download(folder: Uri, notes: List<AudioNote>) {
        if (notes.isEmpty()) return
        val existing = children(folder)
        for (note in notes.distinctBy { it.hash }) {
            val target = file(note)
            if (target.exists() && target.length() == note.bytes && digest(target) == note.hash) continue
            val remote = existing[assetName(note)] ?: error("缺少音频附件：${note.name}，请连同 PDF 和旁文件一起复制")
            val temporary = File(directory, ".download-${UUID.randomUUID()}")
            try {
                val copied = resolver.openInputStream(remote.uri)?.use { input ->
                    FileOutputStream(temporary).use { output -> copy(input, output).also { output.fd.sync() } }
                } ?: error("无法读取音频附件")
                require(copied.first == note.hash && copied.second == note.bytes) { "音频附件校验失败：${note.name}" }
                commit(temporary, note)
            } finally { temporary.delete() }
        }
    }
    private fun digest(file: File) = file.inputStream().use { copy(it, null).first }
    private fun copy(input: InputStream, output: OutputStream?, checkActive: () -> Unit = {}): Pair<String, Long> {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var size = 0L
        while (true) {
            checkActive()
            val n = input.read(buffer)
            if (n < 0) break
            size += n
            require(size <= MAX_AUDIO_BYTES) { "单个音频不能超过 200 MB" }
            hash.update(buffer, 0, n); output?.write(buffer, 0, n)
        }
        require(size > 0) { "音频文件为空" }
        return hash.digest().joinToString("") { "%02x".format(it) } to size
    }
}
