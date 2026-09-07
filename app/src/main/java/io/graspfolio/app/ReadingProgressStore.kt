package io.graspfolio.app

import android.content.Context

internal data class ReadingProgress(val page: Int = 0, val cover: Boolean = true)

/** Small private metadata only. The PDF is never modified or copied into a library. */
internal class ReadingProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    fun load(uri: String) = ReadingProgress(preferences.getInt("$uri:page", 0).coerceAtLeast(0), preferences.getBoolean("$uri:cover", true))
    fun save(uri: String, progress: ReadingProgress) {
        preferences.edit().putInt("$uri:page", progress.page).putBoolean("$uri:cover", progress.cover).apply()
    }
}
