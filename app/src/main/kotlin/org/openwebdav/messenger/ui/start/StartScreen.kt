package org.openwebdav.messenger.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-launch fork (`docs/ui-guide.md` Empty states: "the create-vs-join fork, each with a one-line
 * explanation of the role"). The owner holds the disk and creates the community; a member joins by an
 * invite. Pure navigation — no state, no I/O (arch note Choice 4 `ConnectStartViewModel` is navigation
 * only, so it stays a stateless composable here).
 */
@Composable
internal fun StartScreen(
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Open WebDAV Messenger",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Chat over a cloud disk you already control. No messenger server.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onCreate,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Create a community — I host the disk" },
            ) {
                Text("Create a community")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "I host the cloud disk and invite others.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = onJoin,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Join by invite" },
            ) {
                Text("Join by invite")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Someone sent me an invite (a string or a QR code).",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
