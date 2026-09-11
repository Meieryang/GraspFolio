package io.graspfolio.app

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

internal val GlassInk = Color(0xff173d58)
internal val GlassMuted = Color(0xff687f90)
internal val GlassPaper = Color(0xfff5eee4)
private val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalSolidGlass = compositionLocalOf { false }
internal val LocalSetSolidGlass = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
internal fun GlassEnvironment(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var solid by remember { mutableStateOf(preferences.getBoolean("solid_glass", false)) }
    val backdrop = rememberLayerBackdrop { drawRect(GlassPaper); drawContent() }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalSolidGlass provides solid, LocalSetSolidGlass provides { value ->
        solid = value; preferences.edit().putBoolean("solid_glass", value).apply()
    }, content = content)
}

/** Capture only the static Compose background/PDF, never the native low-latency ink surface. */
@Composable
internal fun Modifier.glassSource(): Modifier = LocalGlassBackdrop.current?.let { then(Modifier.layerBackdrop(it)) } ?: this

@Composable
internal fun Modifier.liquidGlass(radius: Int = 28, tintAlpha: Float = .24f): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    val source = LocalGlassBackdrop.current
    val solid = LocalSolidGlass.current
    val alpha = if (solid || Build.VERSION.SDK_INT < 31) .88f else tintAlpha
    val base = if (source == null) background(Color.White.copy(alpha = .86f), shape) else drawBackdrop(
        backdrop = source,
        shape = { shape },
        effects = {
            if (!solid) {
                vibrancy()
                blur(3.dp.toPx())
                lens(minOf(10.dp.toPx(), size.minDimension / 3), minOf(16.dp.toPx(), size.minDimension / 2), chromaticAberration = true)
            }
        },
        highlight = { Highlight(width = 1.dp, alpha = .8f) },
        shadow = { Shadow(radius = 12.dp, color = Color(0xff514638).copy(alpha = .16f)) },
        onDrawSurface = {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha + (1f - alpha) * .22f), Color.White.copy(alpha = alpha))))
        }
    )
    return base.border(.7.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = .12f), Color.White.copy(alpha = .8f))), shape).clip(shape)
}

@Composable
internal fun LeatherBackground(modifier: Modifier = Modifier) {
    val texture = ImageBitmap.imageResource(R.drawable.leather_surface)
    Canvas(modifier.fillMaxSize().glassSource()) {
        // Keep the grain at a fixed physical scale; mirror neighbouring tiles to avoid seams.
        val tile = 300.dp.roundToPx()
        for (row in 0..(size.height / tile).toInt()) {
            for (column in 0..(size.width / tile).toInt()) {
                translate(column * tile.toFloat(), row * tile.toFloat()) {
                    scale(if (column % 2 == 0) 1f else -1f, if (row % 2 == 0) 1f else -1f,
                        pivot = Offset(tile / 2f, tile / 2f)) {
                        drawImage(texture, dstSize = IntSize(tile, tile))
                    }
                }
            }
        }
        drawRect(GlassPaper.copy(alpha = .28f))
    }
}

@Composable
internal fun GlassAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.liquidGlass(24).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = GlassInk)
    }
}

@Composable
internal fun GlassToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp, label = "glass switch")
    Box(Modifier.size(56.dp, 48.dp).semantics { contentDescription = label }
        .toggleable(checked, role = Role.Switch, onValueChange = onChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp, 32.dp).liquidGlass(16, if (checked) .16f else .48f)
            .background(if (checked) GlassInk.copy(alpha = .18f) else Color.Transparent).padding(4.dp)) {
            Box(Modifier.offset(x = offset).size(24.dp)
                .background(if (checked) GlassInk else Color.White, CircleShape)
                .border(.7.dp, Color.White.copy(alpha = .8f), CircleShape))
        }
    }
}
