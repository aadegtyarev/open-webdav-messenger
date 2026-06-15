package org.openwebdav.messenger.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openwebdav.messenger.data.MessageEntity
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
    onBack: () -> Unit = {},
    viewModel: ChatFeedViewModel = viewModel(factory = FeedViewModelFactory),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val lastMessageId = messages.lastOrNull()?.messageId
    val visibleCount = listState.layoutInfo.visibleItemsInfo.size
    LaunchedEffect(lastMessageId, visibleCount) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.communityName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.messageId }) { row ->
                    MessageRow(row, onRetry = { viewModel.retryFailed(row.messageId, row.body) })
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
private fun MessageRow(
    row: ChatFeedViewModel.FeedRow,
    onRetry: () -> Unit,
) {
    val align = if (row.isMine) Alignment.End else Alignment.Start
    val borderAlpha = when (row.sendStatus) {
        MessageEntity.STATUS_SENT -> 0.06f   // barely visible = delivered
        MessageEntity.STATUS_READ -> 0.14f   // normal = seen
        else -> 0.10f                         // SENDING or other
    }
    val borderColor =
        if (row.isMine) MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha)
        else MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (row.isMine) 12.dp else 2.dp,
        bottomEnd = if (row.isMine) 2.dp else 12.dp,
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = align,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon on the left for own messages
            if (row.isMine) {
                when (row.sendStatus) {
                    MessageEntity.STATUS_SENDING -> Icon(
                        Icons.Filled.Schedule,
                        contentDescription = "Sending",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    MessageEntity.STATUS_FAILED -> Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "Failed — tap to retry",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onRetry() },
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                text = row.body,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
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
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
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
