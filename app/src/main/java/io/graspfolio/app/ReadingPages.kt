package io.graspfolio.app

internal const val PageCornerSizeDp = 69f

/** Physical PDF indices; a null slot is the blank facing a cover or final page. */
internal fun readingPages(page: Int, count: Int, spread: Boolean, cover: Boolean): List<Int?> {
    require(count > 0)
    val current = page.coerceIn(0, count - 1)
    if (!spread) return listOf(current)
    if (cover && current == 0) return listOf(null, 0)
    val start = if (cover) 1 + (current - 1) / 2 * 2 else current / 2 * 2
    return listOf(start, (start + 1).takeIf { it < count })
}

internal fun turnPage(page: Int, count: Int, spread: Boolean, cover: Boolean, direction: Int): Int {
    val visible = readingPages(page, count, spread, cover).filterNotNull()
    return if (direction > 0) {
        if (visible.last() == count - 1) page else visible.last() + 1
    } else {
        if (visible.first() == 0) page else {
            val previous = visible.first() - 1
            readingPages(previous, count, spread, cover).filterNotNull().first()
        }
    }
}
