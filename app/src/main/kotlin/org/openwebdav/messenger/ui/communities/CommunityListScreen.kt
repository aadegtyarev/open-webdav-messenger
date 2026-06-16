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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
                                    selectedCommunityId = community.id
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
                            onOpenFeed()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Open chat",
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
                                        onDm = {
                                            scope.launch(Dispatchers.IO) {
                                                val chatId = AppContainer.startDm(member)
                                                if (chatId != null) {
                                                    onOpenFeed()
                                                }
                                            }
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
    onDm: () -> Unit,
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
    }
}
