@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

private val TvgramDarkColors = darkColorScheme(
    primary           = Color(0xFF5288C1),
    onPrimary         = Color.White,
    secondary         = Color(0xFF7AB2FF),
    onSecondary       = Color.Black,
    background        = Color(0xFF101010),
    onBackground      = Color(0xFFE6E6E6),
    surface           = Color(0xFF1A1A1A),
    onSurface         = Color(0xFFE6E6E6),
    surfaceVariant    = Color(0xFF2A2A2A),
    onSurfaceVariant  = Color(0xFFB0B0B0),
)

private val TvgramLightColors = androidx.tv.material3.lightColorScheme(
    primary           = Color(0xFF5288C1),
    onPrimary         = Color.White,
    secondary         = Color(0xFF1E5FA8),
    onSecondary       = Color.White,
    background        = Color(0xFFF5F5F7),
    onBackground      = Color(0xFF1A1A1A),
    surface           = Color(0xFFFFFFFF),
    onSurface         = Color(0xFF1A1A1A),
    surfaceVariant    = Color(0xFFE8E8EC),
    onSurfaceVariant  = Color(0xFF555555),
)

@Composable
fun TvgramTheme(
    themeMode: tv.telegram.ui.ThemeMode = tv.telegram.ui.ThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val scheme = when (themeMode) {
        tv.telegram.ui.ThemeMode.Dark -> TvgramDarkColors
        tv.telegram.ui.ThemeMode.Light -> TvgramLightColors

        tv.telegram.ui.ThemeMode.System -> TvgramDarkColors
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
