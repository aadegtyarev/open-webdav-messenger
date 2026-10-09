package org.openwebdav.messenger.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.R
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.AppContainer.UnifiedChat
import org.openwebdav.messenger.app.ChatDeleteCapability
import org.openwebdav.messenger.app.ChatListDeletionResult
import org.openwebdav.messenger.app.ChatOpenRequestCoordinator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedChatListScreen(
    onCreateCommunity: () -> Unit,
    onJoin: () -> Unit,
    onOpenFeed: () -> Unit,
    onSettings: () -> Unit,
    chatItems: List<UnifiedChat>? = null,
    deletedChatKeys: Set<Pair<String, String>> = emptySet(),
    openChat: suspend (UnifiedChat, ChatOpenRequestCoordinator.Token) -> Boolean = { chat, token ->
        if (chat.kind == "general") {
            AppContainer.switchToCommunity(chat.communityId, token)
        } else {
            AppContainer.openGroupChat(chat.chatId, chat.name, chat.communityId, requestToken = token)
        }
    },
    observeUnreadCount: (String, String) -> Flow<Int> = AppContainer::observeUnreadCount,
    projectDeleteCapability: suspend (String, String) -> ChatDeleteCapability = { communityId, chatId ->
        AppContainer.projectDeleteForAllCapability(communityId, chatId)
    },
    deleteChat: suspend (String, String, Boolean) -> ChatListDeletionResult = { communityId, chatId, localOnly ->
        AppContainer.deleteChatFromList(communityId, chatId, localOnly)
    },
) {
    val chats =
        remember(chatItems, deletedChatKeys) {
            (chatItems ?: AppContainer.allChats()).filterNot { chat ->
                (chat.communityId to chat.chatId) in deletedChatKeys
            }
        }
    var fabExpanded by remember { mutableStateOf(false) }
    var showCreateChatDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var openRequestGeneration by remember { mutableLongStateOf(0L) }
    var deleteTarget by remember { mutableStateOf<Pair<UnifiedChat, Boolean>?>(null) }
    var typedDeleteTitle by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    deleteTarget?.let { (chat, localOnly) ->
        ChatListDeletionDialog(
            chat = chat,
            localOnly = localOnly,
            typedTitle = typedDeleteTitle,
            busy = deleting,
            onTypedTitle = { typedDeleteTitle = it },
            onDismiss = {
                deleteTarget = null
                typedDeleteTitle = ""
            },
            onConfirm = {
                deleting = true
                scope.launch {
                    val result =
                        try {
                            withContext(Dispatchers.IO) { deleteChat(chat.communityId, chat.chatId, localOnly) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ChatListDeletionResult.FAILED
                        }
                    deleting = false
                    deleteTarget = null
                    typedDeleteTitle = ""
                    val message =
                        when (result) {
                            ChatListDeletionResult.LOCAL_DELETED -> context.getString(R.string.dm_delete_success)
                            ChatListDeletionResult.REMOTE_LOCAL_COMMITTED -> context.getString(R.string.chat_delete_local_committed)
                            ChatListDeletionResult.REMOTE_TOMBSTONE_PUBLISHED -> context.getString(R.string.chat_delete_tombstone_published)
                            ChatListDeletionResult.REMOTE_CLEANUP_PENDING -> context.getString(R.string.chat_delete_cleanup_pending)
                            ChatListDeletionResult.REMOTE_COMPLETE -> context.getString(R.string.chat_delete_complete)
                            ChatListDeletionResult.FAILED -> context.getString(R.string.chat_delete_failure)
                        }
                    snackbarHostState.showSnackbar(message)
                }
            },
        )
    }

    if (showCreateChatDialog) {
        CreateChatDialog(
            onDismiss = { showCreateChatDialog = false },
            onCreated = {
                showCreateChatDialog = false
                onOpenFeed()
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        text = { Text("New group chat") },
                        onClick = {
                            fabExpanded = false
                            showCreateChatDialog = true
                        },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("New community") },
                        onClick = {
                            fabExpanded = false
                            onCreateCommunity()
                        },
                        leadingIcon = { Icon(Icons.Filled.Group, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Join") },
                        onClick = {
                            fabExpanded = false
                            onJoin()
                        },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
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
                        observeUnreadCount = observeUnreadCount,
                        projectDeleteCapability = projectDeleteCapability,
                        deletionBusy = deleting,
                        onDeleteRequest = { target, localOnly ->
                            deleteTarget = target to localOnly
                            typedDeleteTitle = ""
                        },
                        onClick = {
                            val request = ++openRequestGeneration
                            val requestToken = AppContainer.beginChatOpenRequest()
                            snackbarHostState.currentSnackbarData?.dismiss()
                            scope.launch {
                                try {
                                    val chatReady = withContext(Dispatchers.IO) { openChat(chat, requestToken) }
                                    if (request != openRequestGeneration) return@launch
                                    if (chatReady) {
                                        onOpenFeed()
                                    } else {
                                        snackbarHostState.showSnackbar("Could not open this chat. It may no longer be available.")
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (request == openRequestGeneration) {
                                        snackbarHostState.showSnackbar("Could not open this chat. Please try again.")
                                    }
                                }
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
    observeUnreadCount: (String, String) -> Flow<Int>,
    projectDeleteCapability: suspend (String, String) -> ChatDeleteCapability,
    deletionBusy: Boolean,
    onDeleteRequest: (UnifiedChat, Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var deleteCapability by remember(chat.communityId, chat.chatId) {
        mutableStateOf(if (chat.kind == "general") ChatDeleteCapability.NotAuthorized else ChatDeleteCapability.Loading)
    }
    var refreshToken by remember(chat.communityId, chat.chatId) { mutableIntStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(chat.communityId, chat.chatId, refreshToken) {
        if (chat.kind != "general") {
            deleteCapability =
                runCatching { projectDeleteCapability(chat.communityId, chat.chatId) }
                    .getOrDefault(ChatDeleteCapability.Unavailable)
        }
    }
    val icon =
        when (chat.kind) {
            "dm" -> Icons.Filled.Person
            else -> Icons.Filled.Group
        }
    val label =
        when (chat.kind) {
            "general" -> "${chat.communityName} · General"
            "group" -> "${chat.communityName} · ${chat.name}"
            else -> chat.name
        }
    val actionsLabel = stringResource(R.string.chat_actions_accessibility, label)
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
        UnreadBadge(chat.communityId, chat.chatId, observeUnreadCount)
        if (chat.kind != "general") {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !deletionBusy,
                    modifier =
                        Modifier.size(48.dp).semantics {
                            contentDescription = actionsLabel
                        },
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    when (deleteCapability) {
                        ChatDeleteCapability.Loading ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_delete_authority_loading)) },
                                enabled = false,
                                onClick = {},
                            )
                        ChatDeleteCapability.Unavailable ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_delete_authority_retry)) },
                                enabled = !deletionBusy,
                                onClick = { refreshToken++ },
                            )
                        ChatDeleteCapability.Available ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_delete_all_action)) },
                                enabled = !deletionBusy,
                                onClick = {
                                    menuExpanded = false
                                    onDeleteRequest(chat, false)
                                },
                            )
                        ChatDeleteCapability.NotAuthorized -> Unit
                    }
                    if (chat.kind == "dm") {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dm_delete_action)) },
                            enabled = !deletionBusy,
                            onClick = {
                                menuExpanded = false
                                onDeleteRequest(chat, true)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Observable unread badge for a chat. Shows count if > 0, nothing otherwise.
 * Lives here to keep the badge co-located with the list that uses it.
 */
@Composable
internal fun UnreadBadge(
    communityId: String,
    chatId: String,
    observeUnreadCount: (String, String) -> Flow<Int> = AppContainer::observeUnreadCount,
) {
    val count by observeUnreadCount(communityId, chatId).collectAsStateWithLifecycle(0)
    if (count > 0) {
        Badge { Text(count.toString()) }
    }
}
