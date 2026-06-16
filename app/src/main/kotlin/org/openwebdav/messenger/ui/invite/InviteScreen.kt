package org.openwebdav.messenger.ui.invite

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Share screen — two tabs: "Download app" (QR → GitHub Releases) and "Invite" (QR → join token).
 * Switch between them with the bottom FilterChips. Both tabs have Copy and Share buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InviteScreen(
    onBack: () -> Unit,
    viewModel: InviteViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val downloadUrl = "https://github.com/aadegtyarev/open-webdav-messenger/releases/latest/download/DavChat.apk"
    val downloadQr = remember { QrEncoder.toImageBitmap(QrEncoder.encode(downloadUrl)) }
    var tab by remember { mutableStateOf("invite") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                FilterChip(
                    selected = tab == "download",
                    onClick = { tab = "download" },
                    label = { Text("Download app") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = tab == "invite",
                    onClick = { tab = "invite" },
                    label = { Text("Invite") },
                )
            }
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
            if (tab == "invite") {
                // --- Invite tab ---
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Anyone who gets this invite can read and write this chat. Share it only with people you trust.",
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
                        contentDescription = "QR code of the invite",
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                }

                if (state.qrUnavailable) {
                    Text(InviteViewModel.QR_UNAVAILABLE_MESSAGE, style = MaterialTheme.typography.bodyMedium)
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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(invite)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Copy")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, invite)
                                    }
                                context.startActivity(Intent.createChooser(intent, "Share invite"))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Share")
                        }
                    }
                }
            } else {
                // --- Download tab ---
                Image(
                    bitmap = downloadQr,
                    contentDescription = "QR code to download the app",
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )

                Text("Download link", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        text = downloadUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Download link" },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(downloadUrl)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Copy")
                    }
                    OutlinedButton(
                        onClick = {
                            val intent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, downloadUrl)
                                }
                            context.startActivity(Intent.createChooser(intent, "Share download link"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Share")
                    }
                }
            }
        }
    }
}
