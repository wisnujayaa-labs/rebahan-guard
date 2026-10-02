@file:Suppress("PropertyName")

package io.github.wisnujayaa.rebahanguard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.wisnujayaa.rebahanguard.R

/**
 * "A study desk" palette: ivory paper, ink, and colour used only for meaning —
 * brick red = urgent / lying, gold = what matters (dreams, the threshold), sage = done / safe.
 * Follows the system's dark mode, because studying at night still deserves a dark screen.
 */
@Immutable
data class Palette(
    val Ink: Color,      // the page background
    val Dusk: Color,     // raised panels
    val DuskHigh: Color, // the "sitting" half of the dial
    val Lamp: Color,     // gold accent: values, the threshold, important
    val Blanket: Color,  // brick red: urgent, lying, the alarm
    val Mint: Color,     // sage: safe, done
    val Text: Color,
    val Muted: Color,
    val Hairline: Color,
    val OnInk: Color,    // text on a solid ink button
    val isDark: Boolean,
)

val PaperPalette = Palette(
    Ink = Color(0xFFF5F0E6),
    Dusk = Color(0xFFFBF8F2),
    DuskHigh = Color(0xFFEAE3D4),
    Lamp = Color(0xFF9A7020),
    Blanket = Color(0xFFB5532F),
    Mint = Color(0xFF4E6B57),
    Text = Color(0xFF1C1F26),
    Muted = Color(0xFF6B6A64),
    Hairline = Color(0xFFE2DACB),
    OnInk = Color(0xFFF5F0E6),
    isDark = false,
)

val NightPalette = Palette(
    Ink = Color(0xFF14161B),
    Dusk = Color(0xFF1D2027),
    DuskHigh = Color(0xFF262A33),
    Lamp = Color(0xFFD4A84B),
    Blanket = Color(0xFFDB7B5C),
    Mint = Color(0xFF8FB39A),
    Text = Color(0xFFEDE6D8),
    Muted = Color(0xFF9A9488),
    Hairline = Color(0xFF2E3139),
    OnInk = Color(0xFF14161B),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { PaperPalette }

/** Read the current palette: `Tone.Text`, `Tone.Blanket`, … (inside composables). */
object Tone {
    val current: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Ink
    val Dusk: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Dusk
    val DuskHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.DuskHigh
    val Lamp: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Lamp
    val Blanket: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Blanket
    val Mint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Mint
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Muted
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Hairline
    val OnInk: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.OnInk
}

/** Fraunces (SIL Open Font License), bundled: an editorial serif for headings and quotes. */
val Fraunces = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_medium, FontWeight.Medium),
    Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
)

private fun scheme(p: Palette) = if (p.isDark) {
    darkColorScheme(
        primary = p.Text, onPrimary = p.OnInk,
        secondary = p.Mint, onSecondary = p.Ink,
        tertiary = p.Lamp,
        background = p.Ink, onBackground = p.Text,
        surface = p.Ink, onSurface = p.Text,
        surfaceVariant = p.Dusk, onSurfaceVariant = p.Muted,
        surfaceContainerHigh = p.Dusk, surfaceContainer = p.Dusk,
        outline = p.Hairline, outlineVariant = p.Hairline,
        error = p.Blanket,
    )
} else {
    lightColorScheme(
        primary = p.Text, onPrimary = p.OnInk,
        secondary = p.Mint, onSecondary = p.Ink,
        tertiary = p.Lamp,
        background = p.Ink, onBackground = p.Text,
        surface = p.Ink, onSurface = p.Text,
        surfaceVariant = p.Dusk, onSurfaceVariant = p.Muted,
        surfaceContainerHigh = p.Dusk, surfaceContainer = p.Dusk,
        outline = p.Hairline, outlineVariant = p.Hairline,
        error = p.Blanket,
    )
}

/** Serif for headings and sentence-like status lines; the default sans for controls and body. */
private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.Normal),
        headlineLarge = t.headlineLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.Normal, lineHeight = 38.sp),
        headlineMedium = t.headlineMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.Normal),
        headlineSmall = t.headlineSmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.Normal),
        titleLarge = t.titleLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.Normal),
    )
}

@Composable
fun RebahanGuardTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) NightPalette else PaperPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme(palette), typography = AppTypography, content = content)
    }
}
