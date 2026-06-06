package org.openwebdav.messenger.ui.invite

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Invite-display screen (`ui-chat-surface` Scenario 2; ui-guide: invite is the one security-bearing
 * surface). Shows the copyable invite string + its QR, with an **always-visible** bearer-token warning
 * ("anyone who gets this invite can read/write this chat and use the disk — share it only with people you
 * trust"). The warning is text + (later) icon, never color-only (ui-guide accessibility).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InviteScreen(
    onBack: () -> Unit,
    viewModel: InviteViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text =
                        "Anyone who gets this invite can read and write this chat and use the disk. " +
                            "Share it only with people you trust.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }

            state.qr?.let { qr ->
                Image(
                    bitmap = qr,
                    contentDescription = "QR code of the invite. The invite string below is the same thing.",
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            }

            state.inviteString?.let { invite ->
                Text("Invite string", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        text = invite,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Invite string" },
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(invite)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Copy invite" },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Copy")
                }
            }
        }
    }
}
