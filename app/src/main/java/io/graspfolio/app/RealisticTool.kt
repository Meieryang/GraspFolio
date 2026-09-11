package io.graspfolio.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** White instruments with soft cylindrical shading; tips extend below the separate tray surface. */
@Composable
internal fun RealisticTool(type: String, label: String, selected: Boolean, color: Color, compact: Boolean, onClick: () -> Unit) {
    val extension by animateDpAsState(if (selected) 5.dp else 0.dp, label = "tool extension")
    Canvas(Modifier.width(if (compact) 30.dp else 40.dp).height(58.dp)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected }) {
        clipRect {
            translate(top = extension.toPx()) {
                scale(size.width / 40f, size.height / 70f, pivot = Offset.Zero) {
                    val white = Brush.horizontalGradient(listOf(Color(0xffd8d9d9), Color(0xfff5f5f4),
                        Color(0xffffffff), Color(0xffededeb), Color(0xffcfd1d0)), 10f, 30f)
                    fun polygon(vararg points: Pair<Float, Float>) = Path().apply {
                        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.first, p.second) else lineTo(p.first, p.second) }; close()
                    }
                    val silhouette = Path().apply {
                        when (type) {
                            "pressure" -> {
                                moveTo(10f, -10f); lineTo(30f, -10f); lineTo(30f, 28f)
                                cubicTo(29f, 35f, 24f, 47f, 22f, 53f)
                                lineTo(20f, 61f); lineTo(18f, 53f)
                                cubicTo(16f, 47f, 11f, 35f, 10f, 28f); close()
                            }
                            "highlighter" -> {
                                moveTo(9f, -10f); lineTo(31f, -10f); lineTo(31f, 23f)
                                cubicTo(31f, 30f, 26f, 33f, 26f, 40f)
                                lineTo(26f, 55f); lineTo(14f, 59f); lineTo(14f, 40f)
                                cubicTo(14f, 33f, 9f, 30f, 9f, 23f); close()
                            }
                            "lasso" -> { moveTo(10f, -10f); lineTo(30f, -10f); lineTo(30f, 28f); lineTo(20f, 59f); lineTo(10f, 28f); close() }
                            else -> { moveTo(10f, -10f); lineTo(30f, -10f); lineTo(30f, 50f); quadraticTo(30f, 57f, 24f, 57f); lineTo(16f, 57f); quadraticTo(10f, 57f, 10f, 50f); close() }
                        }
                    }
                    // Broad, faint pen shadow extends over the tray edge with the tip.
                    translate(left = 1.5f, top = 2f) {
                        for (radius in listOf(7f, 4f, 2f)) drawPath(silhouette, Color(0x08000000), style = Stroke(radius))
                    }
                    drawPath(silhouette, white)
                    clipPath(silhouette) {
                        when (type) {
                            "pressure" -> {
                                drawRect(color, Offset(10f, 22f), Size(20f, 3.4f))
                                drawPath(polygon(17.7f to 53f, 22.3f to 53f, 20f to 61f),
                                    Brush.horizontalGradient(listOf(color, color, color.copy(alpha = .82f)), 17f, 23f))
                                drawLine(Color(0x33ffffff), Offset(13f, 28f), Offset(18f, 51f), .6f)
                            }
                            "highlighter" -> {
                                drawRect(color, Offset(9f, 12f), Size(22f, 7f))
                                val tip = polygon(14f to 44f, 26f to 44f, 26f to 55f, 14f to 59f)
                                drawPath(tip, Brush.horizontalGradient(listOf(color, androidx.compose.ui.graphics.lerp(color, Color.White, .18f), color), 14f, 26f))
                                drawLine(Color(0x33000000), Offset(14f, 44f), Offset(26f, 44f), .6f)
                            }
                            "eraser" -> {
                                drawRect(Color(0x12000000), Offset(10f, 26f), Size(20f, .7f))
                                drawRect(Brush.horizontalGradient(listOf(Color(0xffcc7d81), Color(0xffeea3a6), Color(0xfff4b1b3), Color(0xffd38287)), 10f, 30f),
                                    Offset(10f, 39f), Size(20f, 20f))
                                drawLine(Color(0x33ffffff), Offset(12f, 40f), Offset(12f, 50f), .7f)
                            }
                            "lasso" -> {
                                val cone = polygon(10f to 28f, 30f to 28f, 20f to 59f)
                                clipPath(cone) {
                                    for (y in 18..70 step 7) drawLine(Color(0xffb6b8b8), Offset(8f, y.toFloat()), Offset(32f, y - 15f), 2.7f)
                                    drawPath(cone, Brush.horizontalGradient(listOf(Color(0x26000000), Color(0x00ffffff), Color(0x66ffffff), Color(0x22000000)), 10f, 30f))
                                }
                            }
                        }
                    }
                    drawPath(silhouette, Color(0x17000000), style = Stroke(.45f))
                }
            }
        }
    }
}
