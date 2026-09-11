package io.graspfolio.app

import org.junit.Assert.*
import org.junit.Test

class LassoSelectionTest {
    private fun p(x: Float, y: Float) = InkPoint(x, y, .7f, 1)
    private val placement = PagePlacement(0, 0f, 0f, 100f, 100f, 1f)
    private val stroke = InkStroke("a", 0, listOf(p(20f, 20f), p(40f, 40f)))
    private val polygon = listOf(p(10f, 10f), p(50f, 10f), p(50f, 50f), p(10f, 50f))
    @Test fun selectionIncludesCrossingSegmentsButNotOtherPageOrEmptyTap() {
        val crossing = stroke.copy(id = "cross", points = listOf(p(0f, 30f), p(90f, 30f)))
        assertEquals(setOf("a", "cross"), LassoSelection.select(listOf(stroke, crossing, stroke.copy(id = "other", page = 1)), 0, polygon))
        assertTrue(LassoSelection.select(listOf(stroke), 0, listOf(p(20f, 20f), p(20f, 20f))).isEmpty())
    }
    private fun selected(): LassoSelection = LassoSelection().apply {
        down(polygon.first(), placement, listOf(stroke))
        polygon.drop(1).forEach { move(it, placement, listOf(stroke)) }
        up(listOf(stroke))
    }
    @Test fun moveClampsToPageAndKeepsStrokeMetadata() {
        val tool = selected()
        tool.down(p(30f, 30f), placement, listOf(stroke))
        tool.move(p(200f, 200f), placement, listOf(stroke))
        val moved = tool.up(listOf(stroke))!!.single()
        assertEquals(stroke.id, moved.id); assertEquals(stroke.brush, moved.brush)
        assertEquals(p(80f, 80f), moved.points.first())
        assertEquals(p(100f, 100f), moved.points.last())
    }
    @Test fun cancelledMoveIsNotCommittedAndEmptyTapClearsSelection() {
        val tool = selected()
        tool.down(p(30f, 30f), placement, listOf(stroke))
        tool.move(p(60f, 60f), placement, listOf(stroke)); tool.cancel()
        assertSame(stroke, tool.preview(listOf(stroke)).single())
        tool.down(p(90f, 90f), placement, listOf(stroke)); tool.up(listOf(stroke))
        assertTrue(tool.selected.isEmpty())
    }
}
