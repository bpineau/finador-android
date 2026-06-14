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
 * A single, fixed dark palette — no dynamic/wallpaper colour (a source of inconsistency).
 * Dark-mode-first. A restrained **terracotta / rust** accent on warm-neutral darks (premium,
 * distinctive, used sparingly) lives across the whole accent family; the gain/loss semantic
 * colours live in [FinColors] (financial green/red, refined) since they are not Material 3 slots.
 *
 * Accent: terracotta #C2613C. Earlier green builds (#27C281…) read too "template"; rust is warmer.
 */
private val FinDarkColors = darkColorScheme(
    background = Color(0xFF0E0C0B),
    onBackground = Color(0xFFECE7E1),
    surface = Color(0xFF181513),
    onSurface = Color(0xFFECE7E1),
    surfaceVariant = Color(0xFF211B16),
    onSurfaceVariant = Color(0xFFA69C90),
    surfaceContainerHigh = Color(0xFF2A231D),
    surfaceContainerHighest = Color(0xFF2A231D),
    outline = Color(0xFF342B24),
    outlineVariant = Color(0xFF261F1A),

    // Accent: terracotta / rust, harmonised across the family.
    primary = Color(0xFFC2613C),
    onPrimary = Color(0xFF1E0F08),
    primaryContainer = Color(0xFF3A1C10),
    onPrimaryContainer = Color(0xFFF2C3A8),

    secondaryContainer = Color(0xFF2C1A11),
    onSecondaryContainer = Color(0xFFE7B89C),

    error = Color(0xFFE0675E),
    onError = Color(0xFF2A0A0A),
)

/**
 * Semantic up/down colours used wherever a figure is a gain or a loss — refined financial
 * green/red (the accent stays reserved for interactive elements). Exposed via [LocalFinColors].
 */
data class FinSemanticColors(
    val gain: Color = Color(0xFF4FBF8B),
    val loss: Color = Color(0xFFE0675E),
)

val LocalFinColors = staticCompositionLocalOf { FinSemanticColors() }

/** Shorthand accessor: `FinColors.current.gain`. */
object FinColors {
    val current: FinSemanticColors
        @Composable get() = LocalFinColors.current
}

/** Colour for a signed figure: gain green / loss red / muted for null. Shared by all screens. */
@Composable
fun gainLossColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
    value < 0 -> FinColors.current.loss
    else -> FinColors.current.gain
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
