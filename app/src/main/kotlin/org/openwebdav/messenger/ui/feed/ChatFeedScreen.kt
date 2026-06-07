package org.openwebdav.messenger.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openwebdav.messenger.ui.FeedViewModelFactory

/**
 * The chat feed + composer (`ui-chat-surface` Scenarios 5–6; ui-guide: feed is a vertical, conversation-
 * ordered `LazyColumn`, composer is a bottom bar, one primary action = Send). Messages render as **literal
 * plain text** — no markup, no tappable link, no auto-load (SC8 stays closed; ui-guide). New messages from
 * the background poll append on their own because the list observes the Room `Flow`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFeedScreen(
    onShowInvite: () -> Unit,
    viewModel: ChatFeedViewModel = viewModel(factory = FeedViewModelFactory),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll only when the user is already at/near the bottom — otherwise a background-poll message
    // would yank the viewport down and fight a user reading scroll-back (review finding 7). Key on the last
    // message id (not the size) so re-emissions that don't add a new tail message don't re-trigger.
    val lastMessageId = messages.lastOrNull()?.messageId
    LaunchedEffect(lastMessageId) {
        if (messages.isNotEmpty() && listState.isAtBottom()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.communityName) },
                actions = {
                    IconButton(onClick = onShowInvite) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = "Invite someone")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                sendError = sendError,
                onDraft = viewModel::onDraft,
                onSend = viewModel::send,
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No messages yet — say hello.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.messageId }) { row ->
                    MessageRow(row)
                }
            }
        }
    }
}

/**
 * Whether the feed is scrolled to (or within [NEAR_BOTTOM_SLACK] rows of) the bottom. When nothing has been
 * laid out yet (first open) this is `true`, so the feed still scrolls to the latest message on open; once
 * the user scrolls up to read history it becomes `false`, so an inbound message no longer yanks the viewport.
 */
private fun androidx.compose.foundation.lazy.LazyListState.isAtBottom(): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    val totalItems = layoutInfo.totalItemsCount
    return totalItems == 0 || lastVisible.index >= totalItems - 1 - NEAR_BOTTOM_SLACK
}

private const val NEAR_BOTTOM_SLACK = 2

@Composable
private fun MessageRow(row: ChatFeedViewModel.FeedRow) {
    val align = if (row.isMine) Alignment.End else Alignment.Start
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
        // Literal plain text — Text renders the body verbatim; no markdown, link, or image (SC8).
        Text(text = row.body, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    draft: String,
    sendError: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        sendError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Message" },
            )
            IconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Send" },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
        }
    }
}
