package io.graspfolio.app

import android.content.Context

internal data class ReadingProgress(val page: Int = 0, val cover: Boolean = true)

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
internal fun readProgress(root: org.json.JSONObject): ReadingProgress? {
    if (!root.has("reading") || root.isNull("reading")) return null
    val value = root.getJSONObject("reading")
    val raw = value.get("page")
    require(raw is Number) { "阅读页码无效" }
    val page = value.getLong("page")
    require(raw.toDouble().isFinite() && raw.toDouble() == page.toDouble()) { "阅读页码必须为整数" }
    require(page in 0..Int.MAX_VALUE.toLong()) { "阅读页码无效" }
    require(value.get("cover") is Boolean) { "封面设置无效" }
    return ReadingProgress(page.toInt(), value.getBoolean("cover"))
}
