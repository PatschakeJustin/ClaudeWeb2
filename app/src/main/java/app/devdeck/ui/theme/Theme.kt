package app.devdeck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours taken from the Humantize logo (blue, green and pink strands). */
object Brand {
    val Blue = Color(0xFF2F80ED)
    val BlueLight = Color(0xFF5AA2FF)
    val Green = Color(0xFF1DB98A)
    val GreenLight = Color(0xFF3DDBA6)
    val Pink = Color(0xFFE8486F)
    val PinkLight = Color(0xFFFF6E8F)
    val Night = Color(0xFF0E1116)
}

val MonoFamily = FontFamily.Monospace

private val Dark = darkColorScheme(
    primary = Brand.BlueLight,
    onPrimary = Color(0xFF001A3D),
    primaryContainer = Color(0xFF13325C),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Brand.GreenLight,
    onSecondary = Color(0xFF00281C),
    secondaryContainer = Color(0xFF0F3D2F),
    onSecondaryContainer = Color(0xFFB9F5DD),
    tertiary = Brand.PinkLight,
    onTertiary = Color(0xFF3D0013),
    tertiaryContainer = Color(0xFF5A1528),
    onTertiaryContainer = Color(0xFFFFD9E0),
    background = Brand.Night,
    onBackground = Color(0xFFE6E8EC),
    surface = Brand.Night,
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1C2129),
    onSurfaceVariant = Color(0xFFAAB2BF),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF14181E),
    surfaceContainer = Color(0xFF171B22),
    surfaceContainerHigh = Color(0xFF1E232B),
    surfaceContainerHighest = Color(0xFF252B34),
    outline = Color(0xFF3A414D),
    outlineVariant = Color(0xFF2A3039),
    error = Brand.PinkLight,
)

private val Light = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E7FF),
    onPrimaryContainer = Color(0xFF00214A),
    secondary = Brand.Green,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F4E3),
    onSecondaryContainer = Color(0xFF002117),
    tertiary = Brand.Pink,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E1),
    onTertiaryContainer = Color(0xFF3E0014),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF15181D),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFE8EBF0),
    onSurfaceVariant = Color(0xFF4A515C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEBEEF2),
    surfaceContainerHigh = Color(0xFFE5E8ED),
    surfaceContainerHighest = Color(0xFFDFE3E9),
    outline = Color(0xFFC4C9D2),
    outlineVariant = Color(0xFFDDE1E7),
    error = Brand.Pink,
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Light or dark following the system setting, with the logo's colours as accents. */
@Composable
fun DevDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
