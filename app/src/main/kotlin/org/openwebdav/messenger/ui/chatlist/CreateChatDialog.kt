package org.openwebdav.messenger.ui.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.chatdirectory.ChatAccess

/**
 * A dialog for creating a new group chat — public or private.
 * DM creation is not done through this dialog — tap a member in the member list instead.
 *
 * - **Public:** visible to all community members, uses community key.
 * - **Private:** invite-only, gets its own random key, not published to directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateChatDialog(
    onDismiss: () -> Unit,
    onCreated: (chatId: String) -> Unit,
) {
    val communities = remember { AppContainer.communities() }
    var name by remember { mutableStateOf("") }
    var selectedCommunity by remember { mutableStateOf(communities.firstOrNull()) }
    var communityDropdownExpanded by remember { mutableStateOf(false) }
    var isPrivate by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group chat") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Chat name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = communityDropdownExpanded,
                    onExpandedChange = { communityDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedCommunity?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Community") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = communityDropdownExpanded)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = communityDropdownExpanded,
                        onDismissRequest = { communityDropdownExpanded = false },
                    ) {
                        communities.forEach { community ->
                            DropdownMenuItem(
                                text = { Text(community.name) },
                                onClick = {
                                    selectedCommunity = community
                                    communityDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Visibility", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isPrivate, onClick = { isPrivate = false })
                    Text("Public", modifier = Modifier.padding(start = 4.dp))
                    Spacer(Modifier.weight(1f))
                    RadioButton(selected = isPrivate, onClick = { isPrivate = true })
                    Text("Private", modifier = Modifier.padding(start = 4.dp))
                }
                Text(
                    text =
                        if (isPrivate) {
                            "Only invited members can see this chat."
                        } else {
                            "All community members can see and join this chat."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val community = selectedCommunity ?: return@TextButton
                    if (name.isBlank() || creating) return@TextButton
                    creating = true
                    val access = if (isPrivate) ChatAccess.PRIVATE else ChatAccess.PUBLIC
                    scope.launch(Dispatchers.IO) {
                        val chatId = AppContainer.createGroupChat(name.trim(), community.id, access)
                        withContext(Dispatchers.Main) {
                            creating = false
                            if (chatId != null) {
                                onDismiss()
                                onCreated(chatId)
                            }
                        }
                    }
                },
                enabled = name.isNotBlank() && selectedCommunity != null && !creating,
            ) {
                Text(if (creating) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
