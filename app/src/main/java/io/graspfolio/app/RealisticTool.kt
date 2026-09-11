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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Small vector instruments: shaded barrels, metal collars and downward-facing tips. */
@Composable
internal fun RealisticTool(type: String, label: String, selected: Boolean, color: Color, compact: Boolean, onClick: () -> Unit) {
    val extension by animateDpAsState(if (selected) 7.dp else 0.dp, label = "tool extension")
    Canvas(Modifier.width(if (compact) 32.dp else 38.dp).height(66.dp)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected }
        .padding(top = extension, bottom = 7.dp - extension)) {
        scale(size.width / 38f, size.height / 59f, pivot = Offset.Zero) {
            val metal = Brush.horizontalGradient(listOf(Color(0xff7e8991), Color(0xfff9fafb), Color(0xffa9b5bd), Color(0xff687581)), 10f, 28f)
            val barrel = Brush.horizontalGradient(listOf(color.copy(alpha = 1f), Color(0xffe6eceb), color, Color(0xff354b58)), 11f, 27f)
            fun block(x: Float, y: Float, w: Float, h: Float, brush: Brush, r: Float = 2f) =
                drawRoundRect(brush, Offset(x, y), Size(w, h), CornerRadius(r))
            fun polygon(vararg points: Pair<Float, Float>): Path = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.first, p.second) else lineTo(p.first, p.second) }; close()
            }
            // Soft offset shadow gives the physical tool depth without a bitmap asset.
            drawRoundRect(Color(0x16000000), Offset(13f, 2f), Size(16f, 42f), CornerRadius(4f))
            when (type) {
                "pressure" -> {
                    block(12f, -4f, 14f, 33f, barrel, 4f)
                    block(12f, 25f, 14f, 4f, metal, 0f)
                    block(13f, 29f, 12f, 9f, Brush.horizontalGradient(listOf(Color(0xff253744), Color(0xff687d86), Color(0xff1d2e39)), 13f, 25f))
                    val nib = polygon(13f to 38f, 25f to 38f, 26f to 43f, 19f to 56f, 12f to 43f)
                    drawPath(nib, Brush.horizontalGradient(listOf(Color(0xffad8441), Color(0xffffecc2), Color(0xffc9a360)), 12f, 26f))
                    drawPath(nib, Color(0xff927340), style = Stroke(.6f))
                    drawLine(Color(0xff554936), Offset(19f, 42f), Offset(19f, 55f), .8f)
                    drawCircle(Color(0xff554936), 1.4f, Offset(19f, 42f))
                    block(22f, 0f, 2f, 18f, metal, 1f)
                }
                "highlighter" -> {
                    block(9f, -4f, 20f, 37f, barrel, 4f)
                    block(10f, 30f, 18f, 6f, metal)
                    block(12f, 36f, 14f, 8f, Brush.horizontalGradient(listOf(Color(0xff525e60), Color(0xffa4acab)), 12f, 26f))
                    drawPath(polygon(12f to 44f, 26f to 44f, 26f to 51f, 12f to 56f), color)
                    drawLine(color.copy(alpha = .5f), Offset(13f, 45f), Offset(13f, 53f), 1f)
                }
                "eraser" -> {
                    block(12f, -4f, 14f, 39f, Brush.horizontalGradient(listOf(Color(0xffd5dfe0), Color.White, Color(0xff96a7b0)), 12f, 26f), 4f)
                    block(12f, 30f, 14f, 9f, metal)
                    for (y in listOf(32f, 35f, 38f)) drawLine(Color(0xff7c8b94), Offset(12f, y), Offset(26f, y), .5f)
                    block(13f, 39f, 12f, 16f, Brush.horizontalGradient(listOf(Color(0xffc98881), Color(0xfff4cec3), Color(0xffd99a8d)), 13f, 25f), 4f)
                }
                "lasso" -> {
                    block(12f, -4f, 14f, 37f, barrel, 4f)
                    block(12f, 30f, 14f, 5f, metal)
                    drawLine(Color(0xff6d8583), Offset(19f, 35f), Offset(19f, 41f), 3f)
                    drawOval(Color(0xff516f72), Offset(10f, 40f), Size(18f, 14f), style = Stroke(1.6f))
                    drawLine(Color(0xff516f72), Offset(23f, 52f), Offset(26f, 57f), 1.5f)
                }
                else -> {
                    block(12f, -4f, 14f, 39f, barrel, 3f)
                    block(12f, 33f, 14f, 4f, metal)
                    drawPath(polygon(13f to 37f, 25f to 37f, 21f to 50f, 17f to 50f), metal)
                    block(18f, 49f, 2f, 7f, Brush.verticalGradient(listOf(Color(0xffa1aeb6), Color(0xff17232c))))
                }
            }
            if (selected) drawCircle(Color(0xff567896), 2f, Offset(33f, 9f))
        }
    }
}
