package org.openwebdav.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import org.openwebdav.messenger.ui.settings.UserSettings

/**
 * The app's Material 3 theme (`docs/ui-guide.md` Design system: Material 3, light + dark, follow the
 * system setting). Uses the default Material 3 colour roles — no hard-coded hex in composables — so both
 * themes and the security-bearing warning text stay legible (ui-guide Readability / Anti-patterns).
 *
 * Font scaling is applied at the typography level: every [Typography] text style's [TextStyle.fontSize] is
 * multiplied by `systemFontScale * userFontScale` (the user's setting from SharedPreferences), clamped to
 * readable bounds. This ensures ALL [MaterialTheme.typography] usages pick up the scale automatically.
 */
@Composable
fun OpenWebDavMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val themeMode by UserSettings.themeModeFlow.collectAsState()
    val effectiveDarkTheme =
        when (themeMode) {
            "dark" -> true
            "light" -> false
            else -> darkTheme // "system" or unknown — follow the OS setting
        }
    val colors = if (effectiveDarkTheme) darkColorScheme() else lightColorScheme()
    val systemFontScale = LocalDensity.current.fontScale
    val userFontScale by UserSettings.fontScaleFlow.collectAsState()
    val scaledTypography = buildScaledTypography(userFontScale * systemFontScale)

    MaterialTheme(
        colorScheme = colors,
        typography = scaledTypography,
        content = content,
    )
}

/**
 * Creates a [Typography] where every text style's [TextStyle.fontSize] is multiplied by [scale],
 * clamped to 0.6×–2.5×. The base is the default Material 3 [Typography]; dark/light theme colour
 * roles are not affected (only font sizes scale).
 */
private fun buildScaledTypography(scale: Float): Typography {
    val clampedScale = scale.coerceIn(0.6f, 2.5f)
    val base = Typography()

    fun TextStyle.scaled(): TextStyle = copy(fontSize = fontSize * clampedScale)
    return Typography(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}
