package org.openwebdav.messenger.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.openwebdav.messenger.ui.theme.OpenWebDavMessengerTheme

/**
 * The single launcher Activity hosting the Compose chat surface (`ui-chat-surface` feature). It holds no
 * logic — it sets the Material 3 theme and the [AppRoot] navigation; all state lives in per-screen
 * ViewModels and the process-scoped engine wiring (arch note Choice 1/4).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenWebDavMessengerTheme {
                AppRoot()
            }
        }
    }
}
