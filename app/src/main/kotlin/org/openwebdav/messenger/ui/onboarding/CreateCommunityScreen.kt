package org.openwebdav.messenger.ui.onboarding

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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.ui.OnboardingViewModelFactory

/**
 * Owner connect + create-community screen (`ui-chat-surface` Scenario 1; ui-guide single-column form, one
 * primary action). The owner enters the WebDAV details + a community name; a non-HTTPS URL is refused
 * inline (SC13). All work is in the ViewModel off the UI thread; this composable only renders the hoisted
 * state and raises events (stack-notes Compose).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateCommunityScreen(
    onCreated: () -> Unit,
    onBack: () -> Unit,
    viewModel: CreateCommunityViewModel = viewModel(factory = OnboardingViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    // Inherit server settings from the first community
    val existingConfig = remember { AppContainer.existingConnectionConfig() }
    val hasExistingConfig = existingConfig != null
    var useExistingConfig by remember { mutableStateOf(hasExistingConfig) }

    // Pre-fill or clear server fields when the inherit toggle changes
    LaunchedEffect(useExistingConfig) {
        if (useExistingConfig && existingConfig != null) {
            viewModel.onBaseUrl(existingConfig.baseUrl)
            viewModel.onUsername(existingConfig.username)
            viewModel.onAppPassword(existingConfig.appPassword)
        } else {
            viewModel.onBaseUrl("")
            viewModel.onUsername("")
            viewModel.onAppPassword("")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create a community") },
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
            // Inherit server settings toggle (only shown when another community exists)
            if (hasExistingConfig) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useExistingConfig,
                        onCheckedChange = { useExistingConfig = it },
                    )
                    Text(
                        "Use same WebDAV server as existing community",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val serverFieldsReadOnly = useExistingConfig && hasExistingConfig
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrl,
                label = { Text("Disk address (https://…)") },
                isError = state.urlError != null,
                supportingText = state.urlError?.let { { Text(it) } },
                singleLine = true,
                readOnly = serverFieldsReadOnly,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Disk address" },
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsername,
                label = { Text("Login") },
                singleLine = true,
                readOnly = serverFieldsReadOnly,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Login" },
            )
            OutlinedTextField(
                value = state.appPassword,
                onValueChange = viewModel::onAppPassword,
                label = { Text("App password") },
                singleLine = true,
                readOnly = serverFieldsReadOnly,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    if (!serverFieldsReadOnly) {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "App password" },
            )
            OutlinedTextField(
                value = state.communityName,
                onValueChange = viewModel::onCommunityName,
                label = { Text("Community name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Community name" },
            )
            state.generalError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { viewModel.submit(onCreated) },
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Create community" },
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("Create")
                }
            }
        }
    }
}
