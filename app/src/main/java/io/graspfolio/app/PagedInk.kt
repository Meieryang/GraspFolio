package io.graspfolio.app

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.io.Writer

/** Immutable disk references: snapshots shared with sync never retain off-screen point arrays. */
internal data class InkRef(val id: String, val page: Int, val blob: String)
internal class PagedInk(val directory: File, val identity: String, val refs: List<InkRef>) : AbstractList<InkStroke>() {
    override val size get() = refs.size
    override fun get(index: Int): InkStroke {
        InkPerformance.decodedBlobReads.incrementAndGet()
        val ref = refs[index]
        val bytes = File(directory, ref.blob).readBytes()
        require(inkDigest(bytes) == ref.blob) { "本页批注校验失败，已禁止覆盖" }
        return InkCodec.decode(bytes.toString(Charsets.UTF_8), identity).single().also {
            require(it.id == ref.id && it.page == ref.page) { "批注页索引不匹配" }
        }
    }
    fun window(pages: Set<Int>): List<InkStroke> = refs.indices.filter { refs[it].page in pages }.map(::get)
    fun manifest() = JSONArray().apply { refs.forEach { put(JSONArray().put(it.id).put(it.page).put(it.blob)) } }
    fun changed(added: List<InkStroke>, removed: Collection<String>): PagedInk {
        val updates = added.associate { it.id to persist(directory, identity, it) }
        val ids = refs.mapTo(hashSetOf()) { it.id }
        val deleted = removed.toHashSet()
        return PagedInk(directory, identity, refs.filterNot { it.id in deleted }.map { updates[it.id] ?: it } + updates.values.filter { it.id !in ids })
    }
    companion object {
        fun persist(directory: File, identity: String, stroke: InkStroke): InkRef {
            check(directory.isDirectory || directory.mkdirs())
            val text = InkCodec.encode(identity, listOf(stroke))
            val key = inkDigest(text.toByteArray())
            val target = File(directory, key)
            if (!target.exists()) {
                val atomic = AtomicFile(target); val stream = atomic.startWrite()
                try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
            }
            return InkRef(stroke.id, stroke.page, key)
        }
        fun from(directory: File, identity: String, strokes: List<InkStroke>): PagedInk =
            if (strokes is PagedInk && strokes.directory == directory) strokes
            else PagedInk(directory, identity, strokes.map { persist(directory, identity, it) })
        fun read(directory: File, identity: String, items: JSONArray): PagedInk {
            val refs = (0 until items.length()).map { i -> val item = items.getJSONArray(i)
                InkRef(item.getString(0), item.getInt(1), item.getString(2)).also {
                    require(it.page >= 0 && it.blob.matches(Regex("[0-9a-f]{64}")) && File(directory, it.blob).isFile) { "批注索引不完整，已禁止覆盖" }
                }
            }
            require(refs.map { it.id }.toSet().size == refs.size)
            return PagedInk(directory, identity, refs)
        }
    }
}

/** JSON compatibility is unchanged externally; only one stroke is decoded/encoded at a time. */
internal object StreamingInk {
    fun value(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().apply {
            reader.beginObject(); while (reader.hasNext()) put(reader.nextName(), value(reader)); reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            reader.beginArray(); while (reader.hasNext()) put(value(reader)); reader.endArray()
        }
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.STRING -> reader.nextString()
        else -> error("无效批注 JSON")
    }
    fun read(reader: JsonReader, identity: String, directory: File): InkDocument {
        val metadata = JSONObject(); val refs = mutableListOf<InkRef>(); val ids = hashSetOf<String>()
        var hasStrokes = false
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key == "strokes") {
                require(!hasStrokes); hasStrokes = true
                reader.beginArray()
                while (reader.hasNext()) {
                    val item = value(reader)
                    val stroke = InkCodec.decode(JSONObject().put("version", 1).put("document", identity)
                        .put("strokes", JSONArray().put(item)).toString(), identity).single()
                    require(ids.add(stroke.id))
                    refs += PagedInk.persist(directory, identity, stroke)
                }
                reader.endArray()
            } else if (key in setOf("version", "document", "reading", "audio")) metadata.put(key, value(reader))
            else reader.skipValue()
        }
        reader.endObject()
        require(hasStrokes)
        val header = InkCodec.decodeDocument(metadata.put("strokes", JSONArray()).toString(), identity)
        return header.copy(strokes = PagedInk(directory, identity, refs))
    }
    fun write(out: Writer, identity: String, strokes: List<InkStroke>, progress: ReadingProgress?, audio: List<AudioNote>) {
        val header = JSONObject().put("version", if (audio.isEmpty()) 2 else 3).put("document", identity)
            .put("reading", progress?.toJson() ?: JSONObject.NULL)
        if (audio.isNotEmpty()) header.put("audio", audioJson(audio))
        out.write(header.toString().dropLast(1)); out.write(",\"strokes\":[")
        strokes.forEachIndexed { i, stroke ->
            if (i > 0) out.write(",")
            out.write(JSONObject(InkCodec.encode(identity, listOf(stroke))).getJSONArray("strokes").getJSONObject(0).toString())
        }
        out.write("]}")
    }
}

/** Old checkpoints stored their entire document in an escaped JSON string. Unwrap without readText(). */
internal class LegacyCheckpointReader(private val source: Reader, private val field: String = "data") : Reader() {
    private var inString = false
    private var escaped = false
    private var token = StringBuilder()
    private var afterData = false
    private var embedded = false
    private fun next(): Int {
        val c = source.read()
        if (c < 0) return c
        if (embedded) {
            if (c == '"'.code) { embedded = false; return next() }
            if (c != '\\'.code) return c
            return when (val e = source.read()) {
                'u'.code -> { val hex = CharArray(4) { source.read().also { require(it >= 0) }.toChar() }; hex.concatToString().toInt(16) }
                'n'.code -> '\n'.code; 'r'.code -> '\r'.code; 't'.code -> '\t'.code
                'b'.code -> '\b'.code; 'f'.code -> 12
                '"'.code, '\\'.code, '/'.code -> e
                else -> error("无效旧批注转义")
            }
        }
        if (afterData) {
            if (c == '"'.code) { afterData = false; embedded = true; return next() }
            if (!c.toChar().isWhitespace() && c != ':'.code) afterData = false
            return c
        }
        if (inString) {
            if (escaped) { escaped = false; token.append('!') }
            else if (c == '\\'.code) { escaped = true; token.append('!') }
            else if (c == '"'.code) { inString = false; afterData = token.toString() == field }
            else if (token.length < field.length + 1) token.append(c.toChar())
        } else if (c == '"'.code) { inString = true; token = StringBuilder() }
        return c
    }
    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var n = 0
        while (n < length) { val c = next(); if (c < 0) break; buffer[offset + n++] = c.toChar() }
        return if (n == 0) -1 else n
    }
    override fun close() = source.close()
}
