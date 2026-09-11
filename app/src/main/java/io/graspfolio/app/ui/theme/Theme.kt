package io.graspfolio.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xff567896),
    secondary = androidx.compose.ui.graphics.Color(0xff173d58),
    tertiary = androidx.compose.ui.graphics.Color(0xffc57968),
    background = androidx.compose.ui.graphics.Color(0xfff5eee4),
    surface = androidx.compose.ui.graphics.Color(0xfffffaf3),
    onSurface = androidx.compose.ui.graphics.Color(0xff173d58),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xff687f90),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xffe9e5dd),
    outline = androidx.compose.ui.graphics.Color(0xffa2afb5),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xffdce5e9)


)

@Composable
fun GraspFolioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = { io.graspfolio.app.GlassEnvironment(content) }
    )
}