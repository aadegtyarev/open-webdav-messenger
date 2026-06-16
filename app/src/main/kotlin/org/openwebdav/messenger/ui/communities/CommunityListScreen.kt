package org.openwebdav.messenger.ui.communities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.protocol.Hex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityListScreen(
    onOpenFeed: () -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
) {
    val communities = remember { AppContainer.communities() }
    val scope = rememberCoroutineScope()
    var selectedCommunityId by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<DirectoryEntry>?>(null) }
    var loadingMembers by remember { mutableStateOf(false) }
    val isHost by remember { mutableStateOf(AppContainer.isHost) }

    // Rotation dialog state
    var memberToRemove by remember { mutableStateOf<DirectoryEntry?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showRotationForm by remember { mutableStateOf(false) }
    var rotationUrl by remember { mutableStateOf("") }
    var rotationUsername by remember { mutableStateOf("") }
    var rotationPassword by remember { mutableStateOf("") }
    var rotating by remember { mutableStateOf(false) }
    var rotationError by remember { mutableStateOf<String?>(null) }
    var rotationSuccess by remember { mutableStateOf(false) }

    // Load members when a community is selected
    LaunchedEffect(selectedCommunityId) {
        val id = selectedCommunityId
        if (id != null) {
            loadingMembers = true
            members =
                withContext(Dispatchers.IO) {
                    AppContainer.loadMembers(id)
                }
            loadingMembers = false
        } else {
            members = null
        }
    }

    // Confirmation dialog: "Remove <name>?"
    if (showConfirmDialog && memberToRemove != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Remove member?") },
            text = {
                Text(
                    "Remove ${memberToRemove!!.displayName} from this community and rotate the " +
                        "WebDAV credential? All other members will receive the new credential automatically.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    showRotationForm = true
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Rotation form: enter new WebDAV URL, username, password
    if (showRotationForm && memberToRemove != null) {
        AlertDialog(
            onDismissRequest = {
                if (!rotating) {
                    showRotationForm = false
                    rotationError = null
                }
            },
            title = { Text("New WebDAV credential") },
            text = {
                Column {
                    Text(
                        "Enter the new WebDAV URL, username, and app password. " +
                            "All remaining members will receive the new credential automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = rotationUrl,
                        onValueChange = { rotationUrl = it },
                        label = { Text("WebDAV URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !rotating,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rotationUsername,
                        onValueChange = { rotationUsername = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !rotating,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rotationPassword,
                        onValueChange = { rotationPassword = it },
                        label = { Text("App password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !rotating,
                    )
                    if (rotating) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    if (rotationError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            rotationError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!rotating && rotationUrl.isNotBlank() && rotationUsername.isNotBlank() && rotationPassword.isNotBlank()) {
                            val member = memberToRemove!!
                            val excludedHex = Hex.encode(member.copySigningPublicKey())
                            rotating = true
                            rotationError = null
                            scope.launch(Dispatchers.IO) {
                                val ok =
                                    AppContainer.rotateCredential(
                                        newUrl = rotationUrl.trim(),
                                        newUsername = rotationUsername.trim(),
                                        newPassword = rotationPassword.trim(),
                                        excludeMemberSignPub = excludedHex,
                                    )
                                withContext(Dispatchers.Main) {
                                    rotating = false
                                    if (ok) {
                                        rotationSuccess = true
                                        showRotationForm = false
                                    } else {
                                        rotationError = "Rotation failed. Check the new credential and try again."
                                    }
                                }
                            }
                        }
                    },
                    enabled = !rotating && rotationUrl.isNotBlank() && rotationUsername.isNotBlank() && rotationPassword.isNotBlank(),
                ) {
                    Text(if (rotating) "Rotating…" else "Rotate credential")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRotationForm = false
                        rotationError = null
                    },
                    enabled = !rotating,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // Success feedback
    if (rotationSuccess) {
        AlertDialog(
            onDismissRequest = { rotationSuccess = false },
            title = { Text("Credential rotated") },
            text = {
                Text(
                    "The WebDAV credential has been rotated. " +
                        "All remaining members will receive the new credential on their next poll cycle.",
                )
            },
            confirmButton = {
                TextButton(onClick = { rotationSuccess = false }) {
                    Text("OK")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Communities") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        if (communities.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No communities yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                for (community in communities) {
                    val isSelected = selectedCommunityId == community.id
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppContainer.switchToCommunity(community.id)
                                    onOpenFeed()
                                }
                                .padding(16.dp)
                                .semantics { contentDescription = community.name },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(
                            community.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            AppContainer.switchToCommunity(community.id)
                            selectedCommunityId =
                                if (selectedCommunityId == community.id) null else community.id
                        }) {
                            Icon(
                                Icons.Filled.Group,
                                contentDescription = "Show members",
                            )
                        }
                    }

                    // Show members for the selected community
                    if (isSelected) {
                        HorizontalDivider()
                        Text(
                            "Members",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        when {
                            loadingMembers -> {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                            members == null -> {
                                Text(
                                    "Could not load members.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            members!!.isEmpty() -> {
                                Text(
                                    "No members yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            else -> {
                                for (member in members!!) {
                                    MemberRow(
                                        member = member,
                                        isHost = isHost,
                                        onDm = {
                                            scope.launch(Dispatchers.IO) {
                                                val chatId = AppContainer.startDm(member)
                                                if (chatId != null) {
                                                    onOpenFeed()
                                                }
                                            }
                                        },
                                        onRemove = {
                                            memberToRemove = member
                                            showConfirmDialog = true
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: DirectoryEntry,
    isHost: Boolean,
    onDm: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(member.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDm, modifier = Modifier.semantics { contentDescription = "DM ${member.displayName}" }) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        if (isHost) {
            IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Remove ${member.displayName}" }) {
                Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}
