package org.openwebdav.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's Material 3 theme (`docs/ui-guide.md` Design system: Material 3, light + dark, follow the
 * system setting). Uses the default Material 3 colour roles — no hard-coded hex in composables — so both
 * themes and the security-bearing warning text stay legible (ui-guide Readability / Anti-patterns).
 */
@Composable
fun OpenWebDavMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
