package io.graspfolio.app

import org.json.JSONArray
import org.json.JSONObject

internal data class AudioNote(val id: String, val page: Int, val name: String, val hash: String,
    val durationMs: Int, val bytes: Long) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(page >= 0 && name.isNotBlank() && name.length <= 256)
        require(hash.matches(Regex("[0-9a-f]{64}")))
        require(durationMs > 0 && bytes in 1..MAX_AUDIO_BYTES)
    }
    fun toJson() = JSONObject().put("id", id).put("page", page).put("name", name)
        .put("hash", hash).put("durationMs", durationMs).put("bytes", bytes)
}
internal const val MAX_AUDIO_BYTES = 200L * 1024 * 1024
internal fun audioJson(notes: List<AudioNote>) = JSONArray().apply { notes.forEach { put(it.toJson()) } }
internal fun readAudio(array: JSONArray): List<AudioNote> {
    require(array.length() <= 1000) { "音频条目过多" }
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val page = item.getLong("page"); val duration = item.getLong("durationMs")
        require(page in 0..Int.MAX_VALUE.toLong() && duration in 1..Int.MAX_VALUE.toLong())
        AudioNote(item.getString("id"), page.toInt(), item.getString("name"), item.getString("hash"), duration.toInt(), item.getLong("bytes"))
    }.also { require(it.map { note -> note.id }.distinct().size == it.size) }
}
internal fun pageAudio(notes: List<AudioNote>, pages: List<Int>) = notes.filter { it.page in pages }
internal fun audioTime(ms: Int): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
