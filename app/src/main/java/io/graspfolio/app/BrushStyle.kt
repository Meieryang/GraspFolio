package io.graspfolio.app

/** Width is in PDF points, never display pixels. Alpha is applied once to a whole stroke. */
internal data class BrushStyle(val brush: String = "pressure", val color: Int = 0xff111111.toInt(), val width: Float = 2f) {
    val alpha: Int get() = if (brush == "highlighter") 76 else color ushr 24
    val opaqueColor: Int get() = color or 0xff000000.toInt()
    fun widthAt(pressure: Float): Float = if (brush == "pressure") pressureWidth(width, pressure) else width
    companion object {
        val types = listOf("pressure", "fineliner", "highlighter")
        fun defaultWidth(brush: String) = if (brush == "highlighter") 12f else 2f
    }
}

internal val InkStroke.style get() = BrushStyle(brush, color, width)
