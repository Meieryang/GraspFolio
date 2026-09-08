package io.graspfolio.app

import android.content.SharedPreferences

internal fun SharedPreferences.loadBrush(type: String? = getString("brush", "pressure")): BrushStyle {
    val brush = type?.takeIf { it in BrushStyle.types } ?: "pressure"
    val defaultColor = if (brush == "highlighter") 0xfff1ce58.toInt() else 0xff111111.toInt()
    val range = if (brush == "highlighter") 6f..32f else .5f..8f
    val savedWidth = getFloat("${brush}_width", BrushStyle.defaultWidth(brush))
    return BrushStyle(brush, getInt("${brush}_color", defaultColor),
        if (savedWidth.isFinite()) savedWidth.coerceIn(range) else BrushStyle.defaultWidth(brush))
}

internal fun SharedPreferences.saveBrush(style: BrushStyle) {
    edit().putString("brush", style.brush).putInt("${style.brush}_color", style.color)
        .putFloat("${style.brush}_width", style.width).apply()
}
