package io.graspfolio.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class DurableInk(
    val strokes: List<InkStroke> = emptyList(), val base: String? = null,
    val revision: Long = 0, val syncedRevision: Long = 0, val progress: ReadingProgress? = null, val audio: List<AudioNote> = emptyList(),
    val contentRevision: Long = revision
) {
    val dirty get() = revision != syncedRevision
    // Only a fresh, never-edited local document may import despite unsynced reading progress.
    val canImportRemote get() = !dirty || (base == null && contentRevision == 0L && strokes.isEmpty() && audio.isEmpty())
    fun acknowledged(revision: Long, hash: String): DurableInk {
        require(revision in syncedRevision..this.revision)
        return copy(base = hash, syncedRevision = revision)
    }
}

/** Local-writer-thread only. V4 indexes immutable stroke blobs; v1–v3 migrate with bounded decoding. */
internal class AnnotationJournal(private val file: File, private val identity: String, pruneOnOpen: Boolean = false) {
    private val checkpoint = AtomicFile(file)
    private val blobs = File(file.parentFile, file.name + ".pages")
    private val directory = File(file.parentFile, file.name + ".journal")
    private var resident: List<InkStroke> = emptyList()
    fun window(pages: Set<Int>): List<InkStroke> = (state.strokes as PagedInk).window(pages).also { resident = it }
    private var sequence = 0L
    private var checkpointSequence = 0L
    var state = DurableInk(); private set

    private fun readEntry(atomic: AtomicFile, field: String): Pair<JSONObject, InkDocument?> {
        val root = JSONObject()
        var document: InkDocument? = null
        android.util.JsonReader(LegacyCheckpointReader(atomic.openRead().bufferedReader(), field)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (key == field) document = StreamingInk.read(reader, identity, blobs)
                else root.put(key, StreamingInk.value(reader))
            }
            reader.endObject()
            require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
        }
        return root to document
    }
    init {
        var legacy = false
        if (file.exists() || File(file.path + ".bak").exists()) {
            val (root, document) = readEntry(checkpoint, "data")
            require(root.optInt("journalVersion", 1) in 1..4) { "恢复日志版本不支持" }
            legacy = root.optInt("journalVersion", 1) < 4
            if (!legacy) require(root.getString("document") == identity)
            sequence = root.optLong("sequence", 0); checkpointSequence = sequence
            val revision = root.optLong("revision", if (root.getBoolean("dirty")) 1 else 0)
            state = DurableInk(if (legacy) requireNotNull(document).strokes else PagedInk.read(blobs, identity, root.getJSONArray("refs")),
                root.optString("base").ifEmpty { null }, revision,
                root.optLong("syncedRevision", if (root.getBoolean("dirty")) 0 else revision),
                if (legacy) document?.progress else readProgress(root),
                if (legacy) document?.audio.orEmpty() else readAudio(root.getJSONArray("audio")),
                root.optLong("contentRevision", revision))
            validate(state)
        } else state = state.copy(strokes = PagedInk(blobs, identity, emptyList()))
        val entries = records()
        require(entries.isEmpty() || file.exists()) { "恢复日志缺少基础快照，已禁止覆盖" }
        entries.filter { it.first > sequence }.forEach { (number, entry) ->
            require(number == sequence + 1) { "恢复日志不连续，已禁止覆盖" }
            val (root, added) = readEntry(AtomicFile(entry), "added")
            require(root.getInt("version") in 1..4 && root.getString("document") == identity && root.getLong("sequence") == number)
            val current = state.strokes as PagedInk
            val removed = root.getJSONArray("removed").let { array -> (0 until array.length()).mapTo(hashSetOf()) { array.getString(it) } }
            val updates = if (root.getInt("version") == 4) PagedInk.read(blobs, identity, root.getJSONArray("refs")) else requireNotNull(added).strokes as PagedInk
            val byId = current.refs.associateByTo(linkedMapOf()) { it.id }
            removed.forEach(byId::remove)
            updates.refs.forEach { byId[it.id] = it }
            state = DurableInk(PagedInk(blobs, identity, byId.values.toList()), root.optString("base").ifEmpty { null },
                root.getLong("revision"), root.getLong("syncedRevision"),
                if (root.has("reading")) readProgress(root) else state.progress,
                if (root.has("audio")) readAudio(root.getJSONArray("audio")) else state.audio,
                root.optLong("contentRevision", root.getLong("revision")))
            validate(state); sequence = number
        }
        // Publish the new index only after every legacy record has validated and its blobs are durable.
        if (pruneOnOpen) pruneRetiredBlobs() else if (legacy) compact()
    }
    private fun validate(value: DurableInk) { require(value.revision >= 0 && value.syncedRevision in 0..value.revision && value.contentRevision in 0..value.revision) }
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
    fun replace(strokes: List<InkStroke>, progress: ReadingProgress? = state.progress, audio: List<AudioNote> = state.audio): Int =
        replacePages(null, strokes, progress, audio)
    fun replacePages(pages: Set<Int>?, strokes: List<InkStroke>, progress: ReadingProgress? = state.progress, audio: List<AudioNote> = state.audio): Int {
        require(progress == null || progress.page >= 0)
        val current = state.strokes as PagedInk
        if (strokes === current) return append(state.copy(progress = progress, audio = audio, revision = state.revision + 1,
            contentRevision = if (audio != state.audio) state.revision + 1 else state.contentRevision), emptyList(), emptyList())
        require(pages == null || strokes.all { it.page in pages })
        val oldRefs = current.refs.filter { pages == null || it.page in pages }.let { if (pages == null) it else it.sortedBy { ref -> ref.page } }
        val old = oldRefs.associateBy { it.id }
        val ids = strokes.mapTo(hashSetOf()) { it.id }
        require(ids.size == strokes.size)
        require(current.refs.none { it.id in ids && it.id !in old }) { "笔迹跨页索引冲突" }
        val residentById = resident.associateBy { it.id }
        val added = strokes.filter { residentById[it.id] !== it && old[it.id]?.blob != inkDigest(InkCodec.encode(identity, listOf(it)).toByteArray()) }
        val removed = old.keys.filter { it !in ids }
        fun validateOrder(incoming: List<InkStroke>, previous: List<InkRef>) {
            val previousIds = previous.mapTo(hashSetOf()) { it.id }
            val retainedOrder = previous.filter { it.id in ids }.map { it.id }
            require(incoming.filter { it.id in previousIds }.map { it.id } == retainedOrder) { "不能重排已有笔迹" }
            require(incoming.map { it.id } == retainedOrder + incoming.filter { it.id !in previousIds }.map { it.id }) { "新笔迹必须追加到末尾" }
        }
        if (pages == null) validateOrder(strokes, oldRefs)
        else {
            require(strokes.all { old[it.id]?.page?.let { page -> page == it.page } != false }) { "已有笔迹不能更换页码" }
            val incoming = strokes.groupBy { it.page }; val previous = oldRefs.groupBy { it.page }
            pages.forEach { validateOrder(incoming[it].orEmpty(), previous[it].orEmpty()) }
        }
        return append(state.copy(strokes = current.changed(added, removed), progress = progress, audio = audio, revision = state.revision + 1,
            contentRevision = if (added.isNotEmpty() || removed.isNotEmpty() || audio != state.audio) state.revision + 1 else state.contentRevision), added, removed).also { resident = strokes }
    }
    fun acknowledge(revision: Long, hash: String): Int = append(state.acknowledged(revision, hash), emptyList(), emptyList())
    private fun append(next: DurableInk, added: List<InkStroke>, removed: List<String>): Int {
        // Establish a baseline before the first record; never overwrite a corrupt baseline.
        if (!file.exists()) compact()
        check(directory.exists() || directory.mkdirs()) { "无法创建恢复日志目录" }
        val number = sequence + 1
        val record = JSONObject().put("version", 4).put("document", identity).put("sequence", number)
            .put("revision", next.revision).put("contentRevision", next.contentRevision).put("syncedRevision", next.syncedRevision).put("base", next.base ?: "")
            .put("reading", next.progress?.toJson() ?: JSONObject.NULL)
            .put("refs", PagedInk.from(blobs, identity, added).manifest()).put("removed", JSONArray(removed))
        if (next.audio != state.audio) record.put("audio", audioJson(next.audio))
        val bytes = write(AtomicFile(File(directory, "%020d.json".format(java.util.Locale.ROOT, number))), record.toString())
        sequence = number; state = next
        val changed = added.associateBy { it.id }; val deleted = removed.toHashSet()
        resident = resident.filterNot { it.id in deleted }.map { changed[it.id] ?: it }
        // Publish only after fsync/atomic commit succeeds.
        return bytes
    }
    fun importRemote(strokes: List<InkStroke>, hash: String, progress: ReadingProgress? = null, audio: List<AudioNote> = emptyList()) {
        val next = DurableInk(PagedInk.from(blobs, identity, strokes), hash, state.revision + 1, state.revision + 1, progress, audio)
        writeCheckpoint(next)
        state = next
        resident = emptyList()
    }
    fun compactIfNeeded() { if (sequence - checkpointSequence >= 128) compact() }
    fun compact() = writeCheckpoint(state)
    /** Production calls this only on a fresh coordinator, before any sync snapshot exists. */
    private fun pruneRetiredBlobs() {
        compact()
        val retained = (state.strokes as PagedInk).refs.mapTo(hashSetOf()) { it.blob }
        blobs.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}")) && it.name !in retained }.forEach { it.delete() }
        resident = emptyList()
    }
    private fun writeCheckpoint(value: DurableInk) {
        val root = JSONObject().put("journalVersion", 4).put("document", identity).put("sequence", sequence)
            .put("revision", value.revision).put("contentRevision", value.contentRevision).put("syncedRevision", value.syncedRevision)
            .put("refs", (value.strokes as PagedInk).manifest()).put("reading", value.progress?.toJson() ?: JSONObject.NULL).put("audio", audioJson(value.audio)).put("base", value.base ?: "").put("dirty", value.dirty)
        write(checkpoint, root.toString())
        checkpointSequence = sequence
        // Crash before cleanup is safe: recovery skips all records covered by the checkpoint.
        records().filter { it.first <= sequence }.forEach { AtomicFile(it.second).delete() }
    }
}
