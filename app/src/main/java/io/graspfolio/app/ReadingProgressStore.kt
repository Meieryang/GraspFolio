package io.graspfolio.app

import android.content.Context

internal data class BlankPage(val id: Int, val after: Int)
internal data class ReadingProgress(val page: Int = 0, val cover: Boolean = true, val blanks: List<BlankPage> = emptyList()) {
    fun order(pdfCount: Int): List<Int> {
        val result = (0 until pdfCount).toMutableList()
        blanks.forEachIndexed { index, blank ->
            require(blank.id == pdfCount + index) { "空白页索引与 PDF 不匹配" }
            val position = result.indexOf(blank.after)
            require(position >= 0) { "空白页位置无效" }
            result.add(position + 1, blank.id)
        }
        return result
    }
    fun insertAfter(logicalPage: Int, pdfCount: Int): ReadingProgress {
        val order = order(pdfCount)
        require(logicalPage in order.indices)
        return copy(page = logicalPage + 1, blanks = blanks + BlankPage(pdfCount + blanks.size, order[logicalPage]))
    }
}

/** Legacy preference migration and a lightweight local UI cache; the journal is authoritative. */
internal class ReadingProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    fun contains(uri: String) = preferences.contains("$uri:page") || preferences.contains("$uri:cover")
    fun load(uri: String) = ReadingProgress(preferences.getInt("$uri:page", 0).coerceAtLeast(0), preferences.getBoolean("$uri:cover", true))
    fun save(uri: String, progress: ReadingProgress) {
        preferences.edit().putInt("$uri:page", progress.page).putBoolean("$uri:cover", progress.cover).apply()
    }
}

internal fun ReadingProgress.toJson() = org.json.JSONObject().put("page", page).put("cover", cover)
    .put("blanks", org.json.JSONArray().apply { blanks.forEach { put(org.json.JSONObject().put("id", it.id).put("after", it.after)) } })
internal fun readProgress(root: org.json.JSONObject): ReadingProgress? {
    if (!root.has("reading") || root.isNull("reading")) return null
    val value = root.getJSONObject("reading")
    val raw = value.get("page")
    require(raw is Number) { "阅读页码无效" }
    val page = value.getLong("page")
    require(raw.toDouble().isFinite() && raw.toDouble() == page.toDouble()) { "阅读页码必须为整数" }
    require(page in 0..Int.MAX_VALUE.toLong()) { "阅读页码无效" }
    require(value.get("cover") is Boolean) { "封面设置无效" }
    val items = value.optJSONArray("blanks") ?: org.json.JSONArray()
    val blanks = (0 until items.length()).map { i ->
        val item = items.getJSONObject(i)
        val id = item.getLong("id"); val after = item.getLong("after")
        require(id in 0..Int.MAX_VALUE.toLong() && after in 0..Int.MAX_VALUE.toLong())
        require(item.getDouble("id") == id.toDouble() && item.getDouble("after") == after.toDouble())
        BlankPage(id.toInt(), after.toInt())
    }
    require(blanks.map { it.id }.distinct().size == blanks.size)
    return ReadingProgress(page.toInt(), value.getBoolean("cover"), blanks)
}
