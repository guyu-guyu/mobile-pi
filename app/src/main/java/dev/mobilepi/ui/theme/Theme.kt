package dev.mobilepi.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val LightColors = lightColorScheme(
    primary = Color(0xFF1769AA),
    onPrimary = Color.White,
    secondary = Color(0xFF376B58),
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F2F4),
    outline = Color(0xFF72777F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BCBFA),
    secondary = Color(0xFF9BD2B8),
    background = Color(0xFF111416),
    surface = Color(0xFF171A1D),
    surfaceContainer = Color(0xFF202428),
)

@Composable
fun MobilePiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.surface.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
        }
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
