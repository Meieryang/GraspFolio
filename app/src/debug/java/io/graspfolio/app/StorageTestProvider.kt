package io.graspfolio.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/** Debug-only private DocumentsProvider; all documents are confined to disposable cache fixtures. */
class StorageTestProvider : DocumentsProvider() {
    companion object { const val AUTHORITY = "io.graspfolio.app.storagefixture" }
    private val root get() = File(requireNotNull(context).cacheDir, "storage-provider-tests").canonicalFile.apply { mkdirs() }
    private fun file(id: String): File {
        val result = if (id == "root") root else File(root, id).canonicalFile
        require(result == root || result.path.startsWith(root.canonicalPath + File.separator))
        return result
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf("root_id"))
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    private fun add(cursor: MatrixCursor, value: File) {
        cursor.addRow(cursor.columnNames.map<String, Any?> { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> if (value == root) "root" else value.relativeTo(root).path
            Document.COLUMN_DISPLAY_NAME -> value.name
            Document.COLUMN_MIME_TYPE -> if (value.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE or
                (if (value.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
            Document.COLUMN_SIZE -> value.length()
            Document.COLUMN_LAST_MODIFIED -> value.lastModified()
            else -> null
        } }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).also { val f = file(documentId); if (f.exists()) add(it, f) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: columns).also { cursor -> file(parentDocumentId).listFiles().orEmpty().forEach { add(cursor, it) } }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val f = File(file(parentDocumentId), displayName)
        check(f.canonicalFile.parentFile == file(parentDocumentId).canonicalFile)
        check(f.createNewFile())
        return f.relativeTo(root).path
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val original = file(documentId); val target = File(original.parentFile, displayName)
        check(target.canonicalFile.parentFile == requireNotNull(original.parentFile).canonicalFile)
        check(!target.exists() && original.renameTo(target))
        return target.relativeTo(root).path // Deliberately changes the URI after every rename.
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        file(documentId).path.startsWith(file(parentDocumentId).path + File.separator)
}
