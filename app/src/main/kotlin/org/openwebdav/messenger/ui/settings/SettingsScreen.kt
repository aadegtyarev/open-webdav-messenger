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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.UpdateChecker
import kotlin.math.abs
import kotlin.math.roundToInt

private val RETENTION_OPTIONS = listOf(7, 14, 30, 60, 90)
private val POLL_FLOOR_OPTIONS = listOf(15, 30, 60, 120, 300, 600, 900, 1800, 3600)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    isHost: Boolean = false,
    retentionWindowDays: Int = UserSettings.DEFAULT_RETENTION_WINDOW_DAYS,
    communityPollFloor: Int = UserSettings.DEFAULT_POLL_INTERVAL_SECONDS,
    onRetentionChanged: (Int) -> Unit = {},
    onPollFloorChanged: (Int) -> Unit = {},
) {
    var name by remember { mutableStateOf(UserSettings.displayName) }
    var nameChanged by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Only show name-saved feedback after the user actually edited the name
    LaunchedEffect(name) {
        if (nameChanged) {
            delay(800)
            snackbarHostState.showSnackbar("Name saved")
        }
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameChanged = true
                    UserSettings.displayName = it
                },
                label = { Text("Your name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your name" },
            )

            ThemeSection()

            RetentionSection(isHost, retentionWindowDays, onRetentionChanged)

            PollFloorSection(isHost, communityPollFloor, onPollFloorChanged, snackbarHostState)

            PersonalPollSection(snackbarHostState)

            Spacer(Modifier.height(8.dp))

            FontScaleSection(snackbarHostState)

            Spacer(Modifier.height(16.dp))
            UpdateSection()
        }
    }
}

@Composable
private fun ThemeSection() {
    val currentMode = UserSettings.themeMode
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()

    Text("Community poll floor", style = MaterialTheme.typography.titleMedium)

    if (isHost) {
        var expanded by remember { mutableStateOf(false) }
        var selectedFloor by remember { mutableIntStateOf(currentFloor) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = UserSettings.formatPollInterval(selectedFloor),
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
                POLL_FLOOR_OPTIONS.forEach { seconds ->
                    val label = UserSettings.formatPollInterval(seconds)
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            selectedFloor = seconds
                            expanded = false
                            onChanged(seconds)
                            scope.launch { snackbarHostState.showSnackbar("Poll floor: $label") }
                        },
                    )
                }
            }
        }
    } else {
        Text(
            "Community minimum: ${UserSettings.formatPollInterval(currentFloor)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Community poll minimum" },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalPollSection(snackbarHostState: SnackbarHostState) {
    val communityFloor = UserSettings.communityMinPollSeconds
    var selected by remember { mutableIntStateOf(UserSettings.pollIntervalSeconds) }
    var expanded by remember { mutableStateOf(false) }
    val secondMarks = listOf(15, 30, 45, 60)
    val options =
        buildList {
            // Second marks (15, 30, 45, 60) from floor
            for (s in secondMarks) {
                if (s in communityFloor..UserSettings.MAX_POLL_INTERVAL_SECONDS) add(s)
            }
            // Minute marks from 2m up, starting from max(floor, 120)
            var m = maxOf(communityFloor, 120)
            while (m <= UserSettings.MAX_POLL_INTERVAL_SECONDS) {
                if (m !in this) add(m)
                m += 60
            }
        }
    // Clamp stored value into options
    val clamped = options.minByOrNull { kotlin.math.abs(it - selected) } ?: options.last()
    if (selected != clamped) { selected = clamped; UserSettings.pollIntervalSeconds = clamped; AppContainer.reschedulePoll() }
    val scope = rememberCoroutineScope()

    Text("My poll interval", style = MaterialTheme.typography.titleMedium)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = UserSettings.formatPollInterval(selected),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { seconds ->
                DropdownMenuItem(
                    text = { Text(UserSettings.formatPollInterval(seconds)) },
                    onClick = {
                        selected = seconds
                        expanded = false
                        val old = UserSettings.pollIntervalSeconds
                        UserSettings.pollIntervalSeconds = seconds
                        if (seconds != old) AppContainer.reschedulePoll()
                        scope.launch { snackbarHostState.showSnackbar("My interval: ${UserSettings.formatPollInterval(seconds)}") }
                    },
                )
            }
        }
    }
    if (selected < 900) {
        Text(
            "Intervals under 15 min require a persistent notification (Android requirement). Use the refresh button in chat instead, or set 15+ min.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FontScaleSection(snackbarHostState: SnackbarHostState) {
    var fontScale by remember { mutableFloatStateOf(UserSettings.fontScale) }
    val scope = rememberCoroutineScope()

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
            if (rounded != fontScale) {
                fontScale = rounded
                UserSettings.fontScale = rounded
                scope.launch { snackbarHostState.showSnackbar("Font size: ${String.format("%.1f", rounded)}x") }
            }
        },
        valueRange = 0.8f..1.5f,
        modifier = Modifier.fillMaxWidth(),
    )
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

    var status by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("Updates", style = MaterialTheme.typography.titleMedium)
    Text("Current version: v$versionName", style = MaterialTheme.typography.bodySmall)

    when {
        status.startsWith("new:") -> {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    status = "downloading"
                    scope.launch(Dispatchers.IO) {
                        val result = UpdateChecker.downloadApk(context, updateUrl)
                        withContext(Dispatchers.Main) {
                            result.fold(
                                onSuccess = { file ->
                                    status = "installing"
                                    UpdateChecker.installApk(context, file)
                                },
                                onFailure = { status = "download-failed" },
                            )
                            checking = false
                        }
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
        }

        status == "downloading" -> {
            Button(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text("Downloading\u2026", style = MaterialTheme.typography.bodySmall)
        }

        status == "installing" -> {
            Text("Opening installer\u2026", style = MaterialTheme.typography.bodySmall)
        }

        status == "download-failed" -> {
            Text("Download failed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    status = "checking"
                    scope.launch(Dispatchers.IO) {
                        UpdateChecker.check(versionName).fold(
                            onSuccess = { info ->
                                status = if (info.isNewer) "new: v${info.latestVersion}" else "up-to-date"
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
                    Text("Retry")
                }
            }
        }

        status == "error" -> {
            Text("Couldn't check for updates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    status = "checking"
                    scope.launch(Dispatchers.IO) {
                        UpdateChecker.check(versionName).fold(
                            onSuccess = { info ->
                                status = if (info.isNewer) "new: v${info.latestVersion}" else "up-to-date"
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

        status == "checking" -> {
            Button(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text("Checking\u2026", style = MaterialTheme.typography.bodySmall)
        }

        status == "up-to-date" -> {
            Text("You're on the latest version.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    status = "checking"
                    scope.launch(Dispatchers.IO) {
                        UpdateChecker.check(versionName).fold(
                            onSuccess = { info ->
                                status = if (info.isNewer) "new: v${info.latestVersion}" else "up-to-date"
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

        else -> {
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    status = "checking"
                    scope.launch(Dispatchers.IO) {
                        UpdateChecker.check(versionName).fold(
                            onSuccess = { info ->
                                status = if (info.isNewer) "new: v${info.latestVersion}" else "up-to-date"
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
    }
}
