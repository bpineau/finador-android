package fin.android.ui

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B4F),
    secondary = Color(0xFF4F6354),
    tertiary = Color(0xFF3B6470),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD6AC),
    secondary = Color(0xFFB6CCBC),
    tertiary = Color(0xFFA4CDDB),
)

/** A single Material3 theme. Uses dynamic colour on Android 12+ when available, dark-aware. */
@Composable
fun FinadorTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
