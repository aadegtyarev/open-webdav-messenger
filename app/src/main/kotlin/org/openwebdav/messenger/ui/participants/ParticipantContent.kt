package org.openwebdav.messenger.ui.participants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openwebdav.messenger.R

@Composable
internal fun ParticipantLoading() {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.participants_loading), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun ParticipantUnavailable(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text(stringResource(R.string.participants_unavailable), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRetry, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(stringResource(R.string.participants_retry))
        }
    }
}

@Composable
internal fun ParticipantRow(
    name: String,
    fingerprint: String,
    isSelf: Boolean,
    isPrivateChatOnly: Boolean = false,
) {
    val hasVerifiedName = name.isNotBlank()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            name.takeIf { hasVerifiedName }
                ?: stringResource(if (isSelf) R.string.participants_you else R.string.participants_unknown_name),
            style = MaterialTheme.typography.titleMedium,
        )
        if (isPrivateChatOnly) {
            Text(stringResource(R.string.participants_private_chat_only), style = MaterialTheme.typography.bodySmall)
        }
        if (isSelf && hasVerifiedName) {
            Text(
                stringResource(R.string.participants_you),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(stringResource(R.string.participants_fingerprint, fingerprint), style = MaterialTheme.typography.bodySmall)
    }
}
