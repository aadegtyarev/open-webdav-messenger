package org.openwebdav.messenger.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.AppContainer.UnifiedChat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedChatListScreen(
    onCreateChat: () -> Unit,
    onJoin: () -> Unit,
    onOpenFeed: () -> Unit,
    onSettings: () -> Unit,
) {
    val chats = remember { AppContainer.allChats() }
    var fabExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(
                    expanded = fabExpanded,
                    onDismissRequest = { fabExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("New chat") },
                        onClick = {
                            fabExpanded = false
                            onCreateChat()
                        },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Join") },
                        onClick = {
                            fabExpanded = false
                            onJoin()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                            )
                        },
                    )
                }
                FloatingActionButton(onClick = { fabExpanded = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Create or join")
                }
            }
        },
    ) { padding ->
        if (chats.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "No chats yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Tap + to create a chat or join a community.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                for (chat in chats) {
                    ChatRow(
                        chat = chat,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                AppContainer.switchToCommunity(chat.communityId)
                                if (chat.kind != "general") {
                                    AppContainer.openGroupChat(chat.chatId, chat.name)
                                }
                                withContext(Dispatchers.Main) { onOpenFeed() }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: UnifiedChat,
    onClick: () -> Unit,
) {
    val icon = when (chat.kind) {
        "dm" -> Icons.Filled.Person
        else -> Icons.Filled.Group
    }
    val label = when (chat.kind) {
        "general" -> "${chat.communityName} · General"
        "group" -> "${chat.communityName} · ${chat.name}"
        else -> chat.name
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                chat.communityName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UnreadBadge(chat.chatId)
    }
}

/**
 * Observable unread badge for a chat. Shows count if > 0, nothing otherwise.
 * Lives here to keep the badge co-located with the list that uses it.
 */
@Composable
internal fun UnreadBadge(chatId: String) {
    val count by AppContainer.observeUnreadCount(chatId).collectAsStateWithLifecycle(0)
    if (count > 0) {
        Badge { Text(count.toString()) }
    }
}
