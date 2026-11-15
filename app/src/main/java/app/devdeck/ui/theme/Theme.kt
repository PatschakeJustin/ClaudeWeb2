package app.devdeck.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

private val Accent = Color(0xFFD97757)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF8AB4F8),
    background = Color(0xFF14161C),
    surface = Color(0xFF1B1F2A),
    surfaceVariant = Color(0xFF262B38),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB8573A),
    onPrimary = Color.White,
    secondary = Color(0xFF3A6EA5),
)

val MonoFamily = FontFamily.Monospace

@Composable
fun DevDeckTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current).copy(primary = Accent)
            else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
