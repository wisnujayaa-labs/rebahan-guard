package io.github.wisnujayaa.rebahanguard.ui

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

private val NightIndigo = Color(0xFF3F4AA8)
private val MoonAmber = Color(0xFFF2B33D)

@Composable
fun RebahanGuardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        // Material You: follow the user's wallpaper colours on Android 12+.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = MoonAmber, secondary = NightIndigo)
        else -> lightColorScheme(primary = NightIndigo, secondary = MoonAmber)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
