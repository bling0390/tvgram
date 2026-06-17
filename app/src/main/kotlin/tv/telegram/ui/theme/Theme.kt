@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Tvgram color palette — leans on Telegram's brand blue, but darker
 * to fit the TV-room ambient lighting.
 */
private val TvgramDarkColors = darkColorScheme(
    primary           = Color(0xFF5288C1),   // Telegram blue
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

@Composable
fun TvgramTheme(
    themeMode: tv.telegram.ui.ThemeMode = tv.telegram.ui.ThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    // v0.8.0: only Dark is wired up. When Light / System are added in
    // v0.8.1+ this is where the dispatch will live.
    val scheme = when (themeMode) {
        tv.telegram.ui.ThemeMode.Dark -> TvgramDarkColors
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
