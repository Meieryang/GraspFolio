package io.graspfolio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPagesTest {
    @Test fun coverIsAloneOnRight() {
        assertEquals(listOf(null, 0), readingPages(0, 6, true, true))
        assertEquals(listOf(1, 2), readingPages(2, 6, true, true))
        assertEquals(listOf(5, null), readingPages(5, 6, true, true))
        assertEquals(listOf(0, 1), readingPages(0, 6, true, false))
    }

    @Test fun allPagesReachableInBothDirections() {
        for (count in 1..20) for (spread in listOf(false, true)) for (cover in listOf(false, true)) {
            var page = 0
            val visited = mutableListOf<Int>()
            repeat(count) {
                visited.addAll(readingPages(page, count, spread, cover).filterNotNull())
                val next = turnPage(page, count, spread, cover, 1)
                assertTrue(next in 0 until count)
                page = next
            }
            assertEquals((0 until count).toList(), visited.distinct())
            repeat(count) { page = turnPage(page, count, spread, cover, -1) }
            assertEquals(0, page)
        }
    }

    @Test fun rotationOrCoverToggleRetainsFocusedPage() {
        for (page in 0..8) {
            assertEquals(listOf(page), readingPages(page, 9, false, true))
            assertTrue(page in readingPages(page, 9, true, true))
            assertTrue(page in readingPages(page, 9, true, false))
        }
    }
}
