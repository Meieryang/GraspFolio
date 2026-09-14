package io.graspfolio.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

internal data class LibraryBook(val uri: Uri, val title: String, val modified: Long = 0, val bytes: Long = 0)
internal data class RecentBook(val book: LibraryBook, val opened: Long)
internal data class LibrarySnapshot(val books: List<LibraryBook>, val recent: List<LibraryBook>, val warning: String?)

/** URI identity deduplicates direct grants and tree grants without hashing entire PDFs. */
internal fun libraryKey(uri: Uri): String = try {
    "${uri.authority}:${DC.getDocumentId(uri)}"
} catch (_: Exception) { uri.toString() }

internal class LibraryRepository(private val context: Context, preferencesName: String = "library") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    var folder: Uri?
        get() = preferences.getString("folder", null)?.let(Uri::parse)
        set(value) { preferences.edit { if (value == null) remove("folder") else putString("folder", value.toString()) } }
    fun folderLabel(uri: Uri): String = try {
        val id = DC.getTreeDocumentId(uri)
        id.replace("primary:", "内部存储 / ").replace(":", " / ")
    } catch (_: Exception) { uri.toString() }
    fun recent(): List<RecentBook> = synchronized(lock) {
        val array = runCatching { JSONArray(preferences.getString("recent", "[]")) }.getOrDefault(JSONArray())
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            RecentBook(LibraryBook(Uri.parse(item.getString("uri")), item.getString("title")), item.getLong("opened"))
        }.getOrNull() }.sortedByDescending { it.opened }
    }
    suspend fun recordOpened(uri: Uri) = withContext(Dispatchers.IO) {
        val book = describe(uri) ?: LibraryBook(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "PDF")
        synchronized(lock) {
            val previous = recent()
            val now = maxOf(System.currentTimeMillis(), (previous.firstOrNull()?.opened ?: 0) + 1)
            val updated = listOf(RecentBook(book, now)) + previous.filterNot { libraryKey(it.book.uri) == libraryKey(uri) }
            preferences.edit { putString("recent", JSONArray().apply {
                updated.take(100).forEach { put(JSONObject().put("uri", it.book.uri.toString()).put("title", it.book.title).put("opened", it.opened)) }
            }.toString()) }
        }
    }
    private fun describe(uri: Uri): LibraryBook? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            val name = it.getString(0) ?: return@use null
            if (!name.endsWith(".pdf", true) && resolver.getType(uri) != "application/pdf") return@use null
            LibraryBook(uri, name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""), bytes = if (it.isNull(1)) 0 else it.getLong(1))
        }
    } catch (_: Exception) { null }
    suspend fun load(): LibrarySnapshot = withContext(Dispatchers.IO) {
        val result = linkedMapOf<String, LibraryBook>()
        // Prefer existing direct grants so current local annotations keep their URI identity.
        resolver.persistedUriPermissions.filter { it.isReadPermission && !DC.isTreeUri(it.uri) }.forEach {
            coroutineContext.ensureActive()
            describe(it.uri)?.let { book -> result[libraryKey(book.uri)] = book }
        }
        val scan = folder?.let { scan(it) }
        scan?.books?.forEach { result.putIfAbsent(libraryKey(it.uri), it) }
        val history = recent().map { result[libraryKey(it.book.uri)] ?: it.book }
        LibrarySnapshot(result.values.sortedBy { it.title.lowercase() }, history, scan?.warning)
    }
    suspend fun scan(tree: Uri): LibrarySnapshot = withContext(Dispatchers.IO) {
        val result = linkedMapOf<String, LibraryBook>()
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        var count = 0
        var inaccessible = 0
        var limited = false
        try { pending.add(DC.getTreeDocumentId(tree)) } catch (_: Exception) {
            return@withContext LibrarySnapshot(emptyList(), emptyList(), "扫描路径无效，请重新选择文件夹")
        }
        while (pending.isNotEmpty() && !limited) {
            coroutineContext.ensureActive()
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            try {
                val children = DC.buildChildDocumentsUriUsingTree(tree, parent)
                val columns = arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME,
                    DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_LAST_MODIFIED, DC.Document.COLUMN_SIZE)
                val cursor = resolver.query(children, columns, null, null, null) ?: error("无法读取目录")
                cursor.use {
                    while (it.moveToNext()) {
                        coroutineContext.ensureActive()
                        if (++count > 10000) { limited = true; break }
                        val id = it.getString(0) ?: continue
                        val name = it.getString(1) ?: continue
                        val mime = it.getString(2)
                        if (mime == DC.Document.MIME_TYPE_DIR) pending.add(id)
                        else if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
                            val uri = DC.buildDocumentUriUsingTree(tree, id)
                            result.putIfAbsent(libraryKey(uri), LibraryBook(uri, name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""),
                                if (it.isNull(3)) 0 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4)))
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { inaccessible++ }
        }
        val warning = when {
            limited -> "目录条目超过 10000，已显示扫描到的书籍，建议选择更具体的文件夹"
            inaccessible > 0 -> "有 $inaccessible 个目录无法读取，请检查目录权限或重新选择文件夹"
            else -> null
        }
        LibrarySnapshot(result.values.toList(), emptyList(), warning)
    }
    companion object { private val lock = Any() }
}
