package io.graspfolio.app

import java.util.concurrent.CancellationException

internal data class DocumentLoading(val stage: String, val completed: Int = 0, val total: Int? = null, val error: String? = null) {
    val fraction: Float? get() = total?.let { if (it == 0) 1f else (completed.toFloat() / it).coerceIn(0f, 1f) }
}

/** Main-thread owned after publication. Only visible pages are assembled for the native ink view. */
internal class ResidentInk private constructor(private val pages: MutableMap<Int, List<InkStroke>>) {
    fun window(indices: Set<Int>): List<InkStroke> = indices.sorted().flatMap { pages[it].orEmpty() }
    fun replace(indices: Set<Int>, strokes: List<InkStroke>) {
        require(strokes.all { it.page in indices })
        val grouped = strokes.groupBy { it.page }
        indices.forEach { pages[it] = grouped[it].orEmpty() }
    }
    companion object {
        fun load(source: List<InkStroke>, cancelled: () -> Boolean = { false },
            memoryAvailable: () -> Boolean = {
                val runtime = Runtime.getRuntime()
                runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) > maxOf(32L * 1024 * 1024, runtime.maxMemory() / 10)
            }, onProgress: (DocumentLoading) -> Unit = {}): ResidentInk {
            val pages = mutableMapOf<Int, MutableList<InkStroke>>()
            onProgress(DocumentLoading("正在加载全书批注", 0, source.size))
            // Keep stroke order within each page. Index traversal itself never decodes points.
            val order = if (source is PagedInk) source.refs.indices.sortedBy { source.refs[it].page } else source.indices
            var completed = 0
            var lastReport = 0L
            for (index in order) {
                if (cancelled()) throw CancellationException("已取消打开")
                if (!memoryAvailable()) throw InsufficientInkMemory()
                val stroke = source[index]
                pages.getOrPut(stroke.page) { mutableListOf() }.add(stroke)
                completed++
                val now = System.nanoTime()
                if (completed == source.size || now - lastReport >= 50_000_000) {
                    onProgress(DocumentLoading("正在加载全书批注", completed, source.size)); lastReport = now
                }
            }
            if (cancelled()) throw CancellationException("已取消打开")
            return ResidentInk(pages.mapValuesTo(mutableMapOf()) { it.value.toList() })
        }
    }
}
internal class InsufficientInkMemory : Exception("内存不足，无法完整加载全书批注。请关闭其他文档或应用后重试。")
