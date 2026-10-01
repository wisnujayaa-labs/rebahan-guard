package io.github.wisnujayaa.rebahanguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * "A bedroom at night" palette. Always dark on purpose: the app is used right before sleep,
 * and a bright screen is exactly what it is trying to talk you out of.
 */
object Night {
    val Ink = Color(0xFF10142E)       // the room with the lights off
    val Dusk = Color(0xFF1A2150)      // panels
    val DuskHigh = Color(0xFF262E66)  // the "sky" half of the dial
    val Lamp = Color(0xFFF2D28B)      // bedside lamp: primary actions, the threshold
    val Blanket = Color(0xFFB86E8A)   // the "in bed" zone and the alarm
    val Mint = Color(0xFF8FD3C1)      // calm / safe
    val Text = Color(0xFFE9EAF6)
    val Muted = Color(0xFF9CA3C7)
    val Hairline = Color(0x22FFFFFF)
}

private val NightScheme = darkColorScheme(
    primary = Night.Lamp,
    onPrimary = Color(0xFF2A2140),
    secondary = Night.Mint,
    onSecondary = Night.Ink,
    tertiary = Night.Blanket,
    background = Night.Ink,
    onBackground = Night.Text,
    surface = Night.Ink,
    onSurface = Night.Text,
    surfaceVariant = Night.Dusk,
    onSurfaceVariant = Night.Muted,
    outline = Night.Hairline,
    outlineVariant = Night.Hairline,
    error = Night.Blanket,
    errorContainer = Color(0xFF4A2340),
    onErrorContainer = Night.Text,
)

/** Serif for the calm, sentence-like status lines; the default sans for controls. */
private val NightTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun RebahanGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightScheme, typography = NightTypography, content = content)
}
