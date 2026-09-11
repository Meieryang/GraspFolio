package io.graspfolio.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class DurableInk(
    val strokes: List<InkStroke> = emptyList(), val base: String? = null,
    val revision: Long = 0, val syncedRevision: Long = 0, val progress: ReadingProgress? = null
) {
    val dirty get() = revision != syncedRevision
    fun acknowledged(revision: Long, hash: String): DurableInk {
        require(revision in syncedRevision..this.revision)
        return copy(base = hash, syncedRevision = revision)
    }
}

/** Local-writer-thread only. Version 2 adds reading state; legacy checkpoints and records remain readable. */
internal class AnnotationJournal(private val file: File, private val identity: String) {
    private val checkpoint = AtomicFile(file)
    private val directory = File(file.parentFile, file.name + ".journal")
    private var sequence = 0L
    private var checkpointSequence = 0L
    var state = DurableInk(); private set

    init {
        if (file.exists() || File(file.path + ".bak").exists()) {
            val root = JSONObject(checkpoint.openRead().bufferedReader().use { it.readText() })
            require(root.optInt("journalVersion", 1) in 1..2) { "恢复日志版本不支持" }
            sequence = root.optLong("sequence", 0); checkpointSequence = sequence
            val revision = root.optLong("revision", if (root.getBoolean("dirty")) 1 else 0)
            val document = InkCodec.decodeDocument(root.getString("data"), identity)
            state = DurableInk(document.strokes,
                root.optString("base").ifEmpty { null }, revision,
                root.optLong("syncedRevision", if (root.getBoolean("dirty")) 0 else revision),
                document.progress)
            validate(state)
        }
        val entries = records()
        require(entries.isEmpty() || file.exists()) { "恢复日志缺少基础快照，已禁止覆盖" }
        entries.filter { it.first > sequence }.forEach { (number, entry) ->
            require(number == sequence + 1) { "恢复日志不连续，已禁止覆盖" }
            val root = JSONObject(AtomicFile(entry).openRead().bufferedReader().use { it.readText() })
            require(root.getInt("version") in 1..2 && root.getString("document") == identity && root.getLong("sequence") == number)
            val byId = state.strokes.associateByTo(linkedMapOf()) { it.id }
            val removed = root.getJSONArray("removed")
            for (i in 0 until removed.length()) byId.remove(removed.getString(i))
            InkCodec.decode(root.getString("added"), identity).forEach { byId[it.id] = it }
            state = DurableInk(byId.values.toList(), root.optString("base").ifEmpty { null },
                root.getLong("revision"), root.getLong("syncedRevision"),
                if (root.has("reading")) readProgress(root) else state.progress)
            validate(state); sequence = number
        }
    }
    private fun validate(value: DurableInk) { require(value.revision >= 0 && value.syncedRevision in 0..value.revision) }
    private fun records(): List<Pair<Long, File>> = directory.listFiles().orEmpty().mapNotNull { entry ->
        val name = entry.name.removeSuffix(".bak")
        if (!name.endsWith(".json")) null else name.removeSuffix(".json").toLongOrNull()?.let { it to File(directory, name) }
    }.distinctBy { it.first }.sortedBy { it.first }
    private fun write(target: AtomicFile, text: String): Int {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
        return bytes.size
    }
    fun replace(strokes: List<InkStroke>, progress: ReadingProgress? = state.progress): Int {
        require(progress == null || progress.page >= 0)
        if (strokes === state.strokes) return append(state.copy(progress = progress, revision = state.revision + 1), emptyList(), emptyList())
        val old = state.strokes.associateBy { it.id }
        val ids = strokes.mapTo(hashSetOf()) { it.id }
        require(ids.size == strokes.size)
        val added = strokes.filter { old[it.id] !== it && old[it.id] != it }
        val removed = old.keys.filter { it !in ids }
        val retainedOrder = state.strokes.filter { it.id in ids }.map { it.id }
        require(strokes.filter { it.id in old }.map { it.id } == retainedOrder) { "不能重排已有笔迹" }
        require(strokes.map { it.id } == retainedOrder + strokes.filter { it.id !in old }.map { it.id }) { "新笔迹必须追加到末尾" }
        return append(state.copy(strokes = strokes, progress = progress, revision = state.revision + 1), added, removed)
    }
    fun acknowledge(revision: Long, hash: String): Int = append(state.acknowledged(revision, hash), emptyList(), emptyList())
    private fun append(next: DurableInk, added: List<InkStroke>, removed: List<String>): Int {
        // Establish a baseline before the first record; never overwrite a corrupt baseline.
        if (!file.exists()) compact()
        check(directory.exists() || directory.mkdirs()) { "无法创建恢复日志目录" }
        val number = sequence + 1
        val record = JSONObject().put("version", 2).put("document", identity).put("sequence", number)
            .put("revision", next.revision).put("syncedRevision", next.syncedRevision).put("base", next.base ?: "")
            .put("reading", next.progress?.toJson() ?: JSONObject.NULL)
            .put("added", InkCodec.encode(identity, added)).put("removed", JSONArray(removed))
        val bytes = write(AtomicFile(File(directory, "%020d.json".format(java.util.Locale.ROOT, number))), record.toString())
        sequence = number; state = next // Publish only after fsync/atomic commit succeeds.
        return bytes
    }
    fun importRemote(strokes: List<InkStroke>, hash: String, progress: ReadingProgress? = null) {
        val next = DurableInk(strokes, hash, state.revision + 1, state.revision + 1, progress)
        writeCheckpoint(next)
        state = next
    }
    fun compactIfNeeded() { if (sequence - checkpointSequence >= 128) compact() }
    fun compact() = writeCheckpoint(state)
    private fun writeCheckpoint(value: DurableInk) {
        val root = JSONObject().put("journalVersion", 2).put("sequence", sequence)
            .put("revision", value.revision).put("syncedRevision", value.syncedRevision)
            .put("data", InkCodec.encodeDocument(identity, value.strokes, value.progress)).put("base", value.base ?: "").put("dirty", value.dirty)
        write(checkpoint, root.toString())
        checkpointSequence = sequence
        // Crash before cleanup is safe: recovery skips all records covered by the checkpoint.
        records().filter { it.first <= sequence }.forEach { AtomicFile(it.second).delete() }
    }
}
