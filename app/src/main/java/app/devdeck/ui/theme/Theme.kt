package app.devdeck.ui.theme

import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours in the style of a classic sci-fi starship console (black background, warm pastel blocks). */
object Lcars {
    val Orange = Color(0xFFFF9C3D)
    val Peach = Color(0xFFFFCC99)
    val Gold = Color(0xFFFFB347)
    val Lavender = Color(0xFFCC99CC)
    val Violet = Color(0xFF9999FF)
    val Ice = Color(0xFF99CCFF)
    val Red = Color(0xFFDD6655)
    val Tan = Color(0xFFD9A066)
    val Panel = Color(0xFF12121A)
    val PanelHigh = Color(0xFF1C1C28)
    val Black = Color(0xFF000000)

    /** Colours cycled through for navigation blocks. */
    val Cycle = listOf(Orange, Lavender, Peach, Violet, Tan, Ice)
}

/** Narrow, technical-looking system font (condensed sans). */
val Condensed: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
val MonoFamily = FontFamily.Monospace

private val Scheme = darkColorScheme(
    primary = Lcars.Orange,
    onPrimary = Lcars.Black,
    primaryContainer = Lcars.Peach,
    onPrimaryContainer = Lcars.Black,
    secondary = Lcars.Lavender,
    onSecondary = Lcars.Black,
    secondaryContainer = Lcars.Lavender,
    onSecondaryContainer = Lcars.Black,
    tertiary = Lcars.Ice,
    onTertiary = Lcars.Black,
    background = Lcars.Black,
    onBackground = Lcars.Peach,
    surface = Lcars.Black,
    onSurface = Color(0xFFF2E6D9),
    surfaceVariant = Lcars.Panel,
    onSurfaceVariant = Color(0xFFBFB3C9),
    surfaceContainer = Lcars.Panel,
    surfaceContainerHigh = Lcars.PanelHigh,
    surfaceContainerHighest = Lcars.PanelHigh,
    surfaceContainerLow = Lcars.Panel,
    surfaceContainerLowest = Lcars.Black,
    outline = Lcars.Violet,
    outlineVariant = Color(0xFF4A4460),
    error = Lcars.Red,
    onError = Lcars.Black,
)

private fun Typography.condensed(): Typography {
    fun TextStyle.c() = copy(fontFamily = Condensed)
    return copy(
        displayLarge = displayLarge.c(), displayMedium = displayMedium.c(), displaySmall = displaySmall.c(),
        headlineLarge = headlineLarge.c(), headlineMedium = headlineMedium.c(), headlineSmall = headlineSmall.c(),
        titleLarge = titleLarge.copy(fontFamily = Condensed, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp, color = Lcars.Orange),
        titleMedium = titleMedium.copy(fontFamily = Condensed, letterSpacing = 1.sp),
        titleSmall = titleSmall.copy(fontFamily = Condensed, letterSpacing = 1.sp),
        bodyLarge = bodyLarge.c(), bodyMedium = bodyMedium.c(), bodySmall = bodySmall.c(),
        labelLarge = labelLarge.copy(fontFamily = Condensed, letterSpacing = 1.sp, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.c(), labelSmall = labelSmall.c(),
    )
}

@Composable
fun DevDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Typography().condensed(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(50),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
