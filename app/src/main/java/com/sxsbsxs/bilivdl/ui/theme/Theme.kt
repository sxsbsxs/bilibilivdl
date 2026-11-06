package com.sxsbsxs.bilivdl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = BvPink, onPrimary = Color.White,
    primaryContainer = BvPinkContainer, onPrimaryContainer = BvText,
    secondary = BvPinkDark, onSecondary = Color.White,
    secondaryContainer = BvSurfaceMuted, onSecondaryContainer = BvText,
    background = BvCanvas, onBackground = BvText,
    surface = BvSurface, onSurface = BvText,
    surfaceVariant = BvSurfaceMuted, onSurfaceVariant = BvTextSecondary,
    outline = BvOutline, error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF82A8), onPrimary = Color(0xFF3A071B),
    primaryContainer = Color(0xFF3B202B), onPrimaryContainer = BvDarkText,
    secondary = BvPink, onSecondary = Color(0xFF3A071B),
    secondaryContainer = BvDarkSurfaceMuted, onSecondaryContainer = BvDarkText,
    background = BvDarkCanvas, onBackground = BvDarkText,
    surface = BvDarkSurface, onSurface = BvDarkText,
    surfaceVariant = BvDarkSurfaceMuted, onSurfaceVariant = BvDarkTextSecondary,
    outline = BvDarkOutline, error = Color(0xFFFFB4AB)
)

private val BiliVdlShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Brand-owned Material 3 theme for new Compose screens. Dynamic wallpaper colors are
 * intentionally disabled so the BiliVdl identity remains consistent across devices. */
@Composable
fun BiliVdlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        shapes = BiliVdlShapes,
        content = content
    )
}
