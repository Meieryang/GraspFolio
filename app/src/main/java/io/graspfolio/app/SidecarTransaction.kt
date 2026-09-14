package io.graspfolio.app

/** Names are resolved afresh after every operation: SAF renames may change document URIs. */
internal interface SidecarFiles {
    fun read(name: String): ByteArray?
    fun open(name: String): java.io.InputStream? = read(name)?.inputStream()
    fun createFrom(name: String, input: java.io.InputStream) = create(name, input.readBytes())
    fun create(name: String, bytes: ByteArray)
    fun canRename(name: String): Boolean
    fun rename(from: String, to: String)
    fun delete(name: String)
}
internal data class PendingSidecar(val oldHash: String?, val newHash: String, val revision: Long,
    val staged: String, val backup: String)
internal interface SidecarTransactionLog {
    fun load(): PendingSidecar?
    fun save(value: PendingSidecar)
    fun clear()
}
internal data class RecoveredCommit(val hash: String, val revision: Long)

/** Never truncate the canonical file. Keep a durable intent until the local journal acknowledges it. */
internal class SidecarTransaction(private val files: SidecarFiles, private val log: SidecarTransactionLog,
    private val name: String) {
    private fun hash(name: String) = files.open(name)?.use(::streamInkDigest)
    private fun deleteKnown(name: String, expected: String) {
        val actual = hash(name) ?: return
        require(actual == expected) { "保存临时文件已被外部修改，已保留所有副本" }
        files.delete(name)
    }
    fun recover(base: String?, revision: Long, syncedRevision: Long): RecoveredCommit? {
        val pending = log.load() ?: return null
        require(pending.revision <= revision) { "保存事务与本地恢复版本不匹配，已保留所有副本" }
        val suffix = pending.staged.removePrefix("$name.pending-")
        require(pending.staged == "$name.pending-${java.util.UUID.fromString(suffix)}" &&
            pending.backup == "$name.backup-$suffix") { "保存事务文件名无效，未清理任何文件" }
        val current = hash(name)
        if (base == pending.newHash && syncedRevision >= pending.revision) {
            require(current != null) { "已同步旁文件被删除，旧备份已保留，未自动重建" }
            // The transaction is already acknowledged. External edits now belong to the
            // normal conflict/import policy, not to this earlier transaction.
            pending.oldHash?.let { deleteKnown(pending.backup, it) }
            deleteKnown(pending.staged, pending.newHash)
            log.clear()
            return null
        }
        if (current == pending.newHash) {
            require(base == pending.oldHash && pending.revision >= syncedRevision) { "保存事务基线冲突" }
            return RecoveredCommit(pending.newHash, pending.revision)
        }
        require(base == pending.oldHash) { "保存事务基线已变化，未覆盖文件" }
        if (current == null && pending.oldHash != null) {
            require(hash(pending.backup) == pending.oldHash) { "保存中断且旧副本不可用，已保留恢复数据" }
            files.rename(pending.backup, name)
            require(hash(name) == pending.oldHash) { "旧旁文件恢复校验失败" }
        } else {
            require(current == pending.oldHash) { "旁文件在保存期间被外部修改，已保留所有副本" }
        }
        // An interrupted staging write is disposable only under our recorded unique name.
        files.delete(pending.staged)
        pending.oldHash?.let { deleteKnown(pending.backup, it) }
        log.clear()
        return null
    }
    fun write(expected: String?, content: ByteArray, revision: Long): String =
        writeStream(expected, inkDigest(content), revision) { content.inputStream() }
    fun write(expected: String?, content: java.io.File, revision: Long): String =
        writeStream(expected, content.inputStream().use(::streamInkDigest), revision) { content.inputStream() }
    private fun writeStream(expected: String?, digest: String, revision: Long, input: () -> java.io.InputStream): String {
        check(log.load() == null) { "请先恢复未完成的保存事务" }
        require(hash(name) == expected) { "旁文件在保存前已变化，未覆盖" }
        if (expected != null) require(files.canRename(name)) { "此存储位置不支持安全替换，请改用支持重命名的本地目录" }
        val id = java.util.UUID.randomUUID().toString()
        val pending = PendingSidecar(expected, digest, revision, "$name.pending-$id", "$name.backup-$id")
        log.save(pending) // Atomic local intent precedes even creation of the staging file.
        input().use { files.createFrom(pending.staged, it) }
        require(hash(pending.staged) == pending.newHash) { "新旁文件写入校验失败，原文件未改动" }
        require(files.canRename(pending.staged)) { "此存储位置不支持安全替换，原文件未改动" }
        require(hash(name) == expected) { "旁文件在保存期间被外部修改，未覆盖" }
        if (expected != null) {
            files.rename(name, pending.backup)
            require(hash(pending.backup) == expected) { "旧旁文件备份校验失败" }
        }
        files.rename(pending.staged, name)
        require(hash(name) == pending.newHash) { "旁文件切换校验失败，旧副本已保留" }
        return pending.newHash
    }
}

internal fun streamInkDigest(input: java.io.InputStream): String {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
