package org.openwebdav.messenger.ui.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.keystore.CommunityRegistry

/**
 * A dialog for creating a new group chat. The user picks a community and enters a name.
 * DM creation is not done through this dialog — tap a member in the member list instead.
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val community = selectedCommunity ?: return@TextButton
                    if (name.isBlank() || creating) return@TextButton
                    creating = true
                    scope.launch(Dispatchers.IO) {
                        val chatId = AppContainer.createGroupChat(name.trim(), community.id)
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
