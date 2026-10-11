package org.openwebdav.messenger.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.openwebdav.messenger.R
import org.openwebdav.messenger.app.CreationPolicySettingsState

@Composable
internal fun CreationPolicySettingsContent(
    state: CreationPolicySettingsState,
    canAdminister: Boolean,
    updating: Boolean,
    onRetry: () -> Unit,
    onPublicChanged: (Boolean) -> Unit,
    onPrivateChanged: (Boolean) -> Unit,
) {
    val unavailable = stringResource(R.string.creation_policy_unavailable)
    val blocked = stringResource(R.string.creation_policy_blocked)
    val publicLabel = stringResource(R.string.creation_policy_public)
    val privateLabel = stringResource(R.string.creation_policy_private)
    when (state) {
        CreationPolicySettingsState.Loading -> {
            Text(stringResource(R.string.creation_policy_loading))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        CreationPolicySettingsState.Unavailable, CreationPolicySettingsState.Blocked -> {
            Text(if (state == CreationPolicySettingsState.Blocked) blocked else unavailable)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.participants_retry)) }
        }
        CreationPolicySettingsState.DefaultAllowed, is CreationPolicySettingsState.Policy -> {
            if (canAdminister) {
                val current = state as? CreationPolicySettingsState.Policy
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(publicLabel, modifier = Modifier.weight(1f))
                    Switch(
                        checked = current?.allowPublic ?: true,
                        onCheckedChange = onPublicChanged,
                        enabled = !updating,
                        modifier = Modifier.semantics { contentDescription = publicLabel },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(privateLabel, modifier = Modifier.weight(1f))
                    Switch(
                        checked = current?.allowPrivate ?: true,
                        onCheckedChange = onPrivateChanged,
                        enabled = !updating,
                        modifier = Modifier.semantics { contentDescription = privateLabel },
                    )
                }
            } else {
                Text(stringResource(R.string.creation_policy_read_only))
            }
        }
    }
}
