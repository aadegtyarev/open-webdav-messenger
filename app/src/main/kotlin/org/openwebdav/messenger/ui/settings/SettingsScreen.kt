package org.openwebdav.messenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(onBack: () -> Unit) {
    var name by remember { mutableStateOf(UserSettings.displayName) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Debounced save feedback: show "Saved" 1 second after the user stops typing.
    LaunchedEffect(name) {
        delay(1_000)
        snackbarHostState.showSnackbar("Name saved")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    UserSettings.displayName = it
                },
                label = { Text("Your name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your name" },
            )
            Spacer(Modifier.height(8.dp))

            // Poll interval section
            PollIntervalSection()

            Spacer(Modifier.height(8.dp))

            // Text size section
            var fontScale by remember { mutableFloatStateOf(UserSettings.fontScale) }

            Text(
                "The quick brown fox jumps over the lazy dog",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            val scaleLabel =
                buildString {
                    append(String.format("%.1f", fontScale))
                    append("x")
                    if (fontScale == 1.0f) append(" (standard)")
                }
            Text(
                scaleLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "Font scale" },
            )

            Slider(
                value = fontScale,
                onValueChange = { newValue ->
                    val rounded = (newValue * 10).roundToInt() / 10f
                    fontScale = rounded
                    UserSettings.fontScale = rounded
                },
                valueRange = 0.8f..1.5f,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text("App version: v0.14.0+", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PollIntervalSection() {
    val communityFloor = UserSettings.communityMinPollMinutes
    var pollInterval by remember { mutableIntStateOf(UserSettings.pollIntervalMinutes) }

    Text("Poll interval", style = MaterialTheme.typography.titleMedium)

    // Community floor info — non-editable.
    Text(
        "Community minimum: $communityFloor min",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = "Community poll minimum" },
    )

    Spacer(Modifier.height(4.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$pollInterval min",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = "Poll interval" },
        )
    }

    Slider(
        value = pollInterval.toFloat(),
        onValueChange = { newValue ->
            val rounded = newValue.toInt()
            pollInterval = rounded
            UserSettings.pollIntervalMinutes = rounded
        },
        valueRange = communityFloor.toFloat()..UserSettings.MAX_POLL_INTERVAL_MINUTES.toFloat(),
        steps = UserSettings.MAX_POLL_INTERVAL_MINUTES - communityFloor - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}
