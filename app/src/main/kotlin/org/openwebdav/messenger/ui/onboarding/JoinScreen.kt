package org.openwebdav.messenger.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openwebdav.messenger.ui.OnboardingViewModelFactory
import org.openwebdav.messenger.ui.scan.QrScannerView

/**
 * Member join screen (`ui-chat-surface` Scenarios 3–4; ui-guide error display). The **paste field is
 * always present and always works** (the mandated fallback); the camera-scan affordance is an optional
 * enhancement that degrades silently when the camera is denied or absent (`camera_denied_falls_back_to_paste`).
 * The disk credentials carried inside the invite are NEVER shown — the screen only ever renders the user's
 * own pasted text and the community result via navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JoinScreen(
    onJoined: () -> Unit,
    onBack: () -> Unit,
    viewModel: JoinViewModel = viewModel(factory = OnboardingViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var scanning by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Denied → stay on the paste path (never block joining); granted → open the scanner.
            scanning = granted
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join by invite") },
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
            if (scanning && hasCamera) {
                QrScannerView(
                    onDecoded = { decoded ->
                        scanning = false
                        viewModel.joinFromScan(decoded, onJoined)
                    },
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            }

            Text(
                text = "Paste the invite you were sent, or scan its QR code.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = state.pasted,
                onValueChange = viewModel::onPasted,
                label = { Text("Invite") },
                isError = state.error != null,
                supportingText = state.error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Invite string" },
            )
            Button(
                onClick = { viewModel.joinFromPaste(onJoined) },
                enabled = !state.joining && state.pasted.isNotBlank(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Join" },
            ) {
                if (state.joining) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("Join")
                }
            }

            if (hasCamera && !scanning) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { requestScan(context, permissionLauncher::launch) { scanning = true } },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Scan QR code" },
                ) {
                    Text("Scan QR code")
                }
            }
        }
    }
}

/** Open the scanner if CAMERA is already granted; otherwise request it (the launcher opens on grant). */
private fun requestScan(
    context: android.content.Context,
    requestPermission: (String) -> Unit,
    openScanner: () -> Unit,
) {
    val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (granted) openScanner() else requestPermission(Manifest.permission.CAMERA)
}
