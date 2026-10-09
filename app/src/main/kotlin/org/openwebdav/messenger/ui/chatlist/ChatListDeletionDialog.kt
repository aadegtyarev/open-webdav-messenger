package org.openwebdav.messenger.ui.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.openwebdav.messenger.R
import org.openwebdav.messenger.app.AppContainer.UnifiedChat

@Composable
internal fun ChatListDeletionDialog(
    chat: UnifiedChat,
    localOnly: Boolean,
    typedTitle: String,
    busy: Boolean,
    onTypedTitle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(stringResource(if (localOnly) R.string.dm_delete_title else R.string.chat_delete_all_title))
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (localOnly) R.string.dm_delete_confirmation else R.string.chat_delete_all_confirmation,
                        chat.name,
                    ),
                )
                OutlinedTextField(
                    value = typedTitle,
                    onValueChange = onTypedTitle,
                    label = { Text(stringResource(R.string.dm_delete_type_title)) },
                    singleLine = true,
                )
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && typedTitle == chat.name, onClick = onConfirm) {
                Text(stringResource(if (localOnly) R.string.dm_delete_action else R.string.chat_delete_all_action))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
