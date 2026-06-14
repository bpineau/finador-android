package fin.android.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * A single, fixed dark palette — no dynamic/wallpaper colour (that's a source of inconsistency).
 * Dark-mode-first: the app forces this scheme regardless of the system setting. The accent (green)
 * lives in [primary]; the two extra semantic colours (gain/loss) live in [FinColors] since they
 * are not Material 3 slots.
 *
 * Accent variants tried during design:
 *   A  #27C281  (balanced emerald — shipped)
 *   B  #2EE6A6  (brighter mint)
 *   C  #16A571  (deeper forest)
 */
private val FinDarkColors = darkColorScheme(
    background = Color(0xFF0C0D0F),
    onBackground = Color(0xFFECEDEE),
    surface = Color(0xFF15181B),
    onSurface = Color(0xFFECEDEE),
    surfaceVariant = Color(0xFF1C2024),
    onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceContainerHigh = Color(0xFF23282D),
    surfaceContainerHighest = Color(0xFF23282D),
    outline = Color(0xFF2B2F35),
    outlineVariant = Color(0xFF23272C),

    // Accent (variant A). Change only this when sampling B/C.
    primary = Color(0xFF27C281),
    onPrimary = Color(0xFF04130C),
    primaryContainer = Color(0xFF123524),
    onPrimaryContainer = Color(0xFF7FE6B0),

    secondaryContainer = Color(0xFF16291E),
    onSecondaryContainer = Color(0xFF74E3AC),

    error = Color(0xFFF26D6D),
    onError = Color(0xFF2A0A0A),
)

/**
 * Semantic up/down colours used wherever a figure is a gain or a loss. Harmonious with the accent
 * (not flashy). Exposed via [LocalFinColors] so call sites read `FinColors.current`.
 */
data class FinSemanticColors(
    val gain: Color = Color(0xFF35D07F),
    val loss: Color = Color(0xFFF26D6D),
)

val LocalFinColors = staticCompositionLocalOf { FinSemanticColors() }

/** Shorthand accessor: `FinColors.current.gain`. */
object FinColors {
    val current: FinSemanticColors
        @Composable get() = LocalFinColors.current
}

/** The fixed, dark Material 3 theme. Forces dark; draws the system bars over the dark background. */
@Composable
fun FinadorTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Light icons (false = light content) on our dark status/nav bars.
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(colorScheme = FinDarkColors, content = content)
}
