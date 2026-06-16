package org.openwebdav.messenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openwebdav.messenger.app.UpdateChecker
import kotlin.math.roundToInt

/** The ordered retention-window options the host can pick from. */
private val RETENTION_OPTIONS = listOf(7, 14, 30, 60, 90)

/** The ordered poll-floor options the host can pick from (minutes). */
private val POLL_FLOOR_OPTIONS = listOf(1, 5, 10, 15, 30, 60, 120, 240)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    isHost: Boolean = false,
    retentionWindowDays: Int = UserSettings.DEFAULT_RETENTION_WINDOW_DAYS,
    communityPollFloor: Int = UserSettings.DEFAULT_POLL_INTERVAL_MINUTES,
    onRetentionChanged: (Int) -> Unit = {},
    onPollFloorChanged: (Int) -> Unit = {},
) {
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

            // Theme toggle section
            ThemeSection()

            Spacer(Modifier.height(8.dp))

            // Retention window section (host-governed)
            RetentionSection(
                isHost = isHost,
                currentDays = retentionWindowDays,
                onChanged = onRetentionChanged,
            )

            Spacer(Modifier.height(8.dp))

            // Community poll floor section (host-governed)
            PollFloorSection(
                isHost = isHost,
                currentFloor = communityPollFloor,
                onChanged = onPollFloorChanged,
            )

            Spacer(Modifier.height(8.dp))

            // Personal poll interval section
            PersonalPollSection()

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

            Spacer(Modifier.height(16.dp))
            UpdateSection()
        }
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val versionName =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }

    var status by remember { mutableStateOf("") } // "", "checking", "up-to-date", "new: X", "error"
    var updateUrl by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("Updates", style = MaterialTheme.typography.titleMedium)
    Text("Current version: v$versionName", style = MaterialTheme.typography.bodySmall)

    if (status.startsWith("new:")) {
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = {
                scope.launch {
                    checking = true
                    UpdateChecker.downloadApk(context, updateUrl).fold(
                        onSuccess = { file -> UpdateChecker.installApk(context, file) },
                        onFailure = { status = "Download failed" },
                    )
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else {
                Text("Update")
            }
        }
    } else {
        Button(
            onClick = {
                scope.launch {
                    checking = true
                    status = "checking"
                    UpdateChecker.check(versionName).fold(
                        onSuccess = { info ->
                            status =
                                if (info.isNewer) {
                                    "new: v${info.latestVersion}"
                                } else {
                                    "up-to-date"
                                }
                            updateUrl = info.apkUrl
                        },
                        onFailure = { status = "error" },
                    )
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !checking,
        ) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else {
                Text("Check for updates")
            }
        }
    }
    if (status == "up-to-date") {
        Text("You're on the latest version.", style = MaterialTheme.typography.bodySmall)
    } else if (status == "error") {
        Text("Couldn't check for updates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    } else if (status == "checking") {
        Text("Checking...", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ThemeSection() {
    val currentMode = UserSettings.themeMode

    Text("Appearance", style = MaterialTheme.typography.titleMedium)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = currentMode == "system",
            onClick = { UserSettings.themeMode = "system" },
            label = { Text("System") },
        )
        FilterChip(
            selected = currentMode == "light",
            onClick = { UserSettings.themeMode = "light" },
            label = { Text("Light") },
        )
        FilterChip(
            selected = currentMode == "dark",
            onClick = { UserSettings.themeMode = "dark" },
            label = { Text("Dark") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetentionSection(
    isHost: Boolean,
    currentDays: Int,
    onChanged: (Int) -> Unit,
) {
    Text("Keep messages", style = MaterialTheme.typography.titleMedium)

    if (isHost) {
        var expanded by remember { mutableStateOf(false) }
        var selectedDays by remember { mutableIntStateOf(currentDays) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = "$selectedDays days",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                RETENTION_OPTIONS.forEach { days ->
                    DropdownMenuItem(
                        text = { Text("$days days") },
                        onClick = {
                            selectedDays = days
                            expanded = false
                            onChanged(days)
                        },
                    )
                }
            }
        }
    } else {
        Text(
            "$currentDays days",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Retention window" },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PollFloorSection(
    isHost: Boolean,
    currentFloor: Int,
    onChanged: (Int) -> Unit,
) {
    Text("Community poll floor", style = MaterialTheme.typography.titleMedium)

    if (isHost) {
        var expanded by remember { mutableStateOf(false) }
        var selectedFloor by remember { mutableIntStateOf(currentFloor) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = "$selectedFloor min",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                POLL_FLOOR_OPTIONS.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text("$minutes min") },
                        onClick = {
                            selectedFloor = minutes
                            expanded = false
                            onChanged(minutes)
                        },
                    )
                }
            }
        }
    } else {
        Text(
            "Community minimum: $currentFloor min",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Community poll minimum" },
        )
    }
}

@Composable
private fun PersonalPollSection() {
    val communityFloor = UserSettings.communityMinPollMinutes
    var pollInterval by remember { mutableIntStateOf(UserSettings.pollIntervalMinutes) }

    Text("My poll interval", style = MaterialTheme.typography.titleMedium)

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
