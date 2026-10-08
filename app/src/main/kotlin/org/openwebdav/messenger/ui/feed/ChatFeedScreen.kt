package org.openwebdav.messenger.ui.feed

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.openwebdav.messenger.R
import org.openwebdav.messenger.app.RecipientReadiness
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.membership.PrivateClaimPublicationStatus
import org.openwebdav.messenger.ui.FeedViewModelFactory
import org.openwebdav.messenger.ui.runtimeScopeKey

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
    onShowParticipants: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: ChatFeedViewModel =
        viewModel(
            key = runtimeScopeKey("feed"),
            factory = FeedViewModelFactory,
        ),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val lastSyncText by viewModel.lastSyncText.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val memberNamesError by viewModel.memberNamesError.collectAsStateWithLifecycle()
    val recipientReadiness by viewModel.recipientReadiness.collectAsStateWithLifecycle()
    val privateClaimStatus by viewModel.privateClaimStatus.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val rosterLoadingLabel = stringResource(R.string.roster_loading_accessibility)
    val rosterLoadingExplanation = stringResource(R.string.roster_loading_explanation)

    // Show member-names error as a one-shot snackbar, then clear.
    LaunchedEffect(memberNamesError) {
        memberNamesError?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    // Trigger a sync when the screen first appears, and dismiss any pending
    // message notifications for this chat (notification tapped = auto-cancel,
    // but the user might have opened the app from the launcher instead).
    LaunchedEffect(Unit) {
        viewModel.syncNow()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(viewModel.communityName.hashCode())
        nm.cancel(SYNC_NOTIFIER_ID)
    }

    // Derive the message list with a "New messages" divider inserted at the READ→SENT boundary.
    val itemsWithDivider =
        remember(messages) {
            buildList<Any> {
                var dividerInserted = false
                for (msg in messages) {
                    if (!dividerInserted && !msg.isMine && msg.sendStatus == MessageEntity.STATUS_SENT) {
                        add(DividerMarker)
                        dividerInserted = true
                    }
                    add(msg)
                }
            }
        }

    // On first open, scroll to the divider (first unread) if present, else to bottom.
    val didInitialScroll = remember { mutableStateOf(false) }
    LaunchedEffect(itemsWithDivider) {
        if (!didInitialScroll.value && itemsWithDivider.isNotEmpty()) {
            val dividerIndex = itemsWithDivider.indexOfFirst { it is DividerMarker }
            val targetIndex = if (dividerIndex >= 0) dividerIndex else itemsWithDivider.size - 1
            listState.scrollToItem(targetIndex)
            didInitialScroll.value = true
        }
    }

    // Keep the last observed viewport state separate from the changing dataset. After an append,
    // layoutInfo may already describe the new list, so reading isAtBottom() in that effect is too late.
    val latestItemCount by rememberUpdatedState(itemsWithDivider.size)
    LaunchedEffect(listState) {
        val policy = FeedAppendPolicy(latestItemCount)
        var hasViewportSample = false
        snapshotFlow { latestItemCount to listState.isAtBottom() }
            .collect { (itemCount, isNearBottom) ->
                if (hasViewportSample && itemCount != policy.itemCount) {
                    if (didInitialScroll.value && policy.onDatasetChanged(itemCount)) {
                        listState.animateScrollToItem(itemCount - 1)
                    } else if (!didInitialScroll.value) {
                        policy.onDatasetChanged(itemCount)
                    }
                }
                policy.onViewportChanged(isNearBottom)
                hasViewportSample = true
            }
    }

    // Progressive markRead: mark the highest visible message as READ after the
    // user has viewed it for ~2.5s. Uses collectLatest so a scroll resets the timer —
    // only messages the user has actually looked at get marked read.
    val latestItemsWithDivider by rememberUpdatedState(itemsWithDivider)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filter { it != null }
            .collectLatest {
                delay(MARK_READ_DELAY_MS)
                val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
                val maxOrderToken = latestVisibleOrderToken(latestItemsWithDivider, visibleIndices) ?: return@collectLatest
                viewModel.markRead(maxOrderToken)
            }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(viewModel.communityName)
                        Text(
                            text = lastSyncText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        when (privateClaimStatus) {
                            PrivateClaimPublicationStatus.NOT_PRIVATE -> Unit
                            PrivateClaimPublicationStatus.PENDING ->
                                Text(
                                    stringResource(R.string.private_claim_pending),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            PrivateClaimPublicationStatus.UPLOADED ->
                                Text(
                                    stringResource(R.string.private_claim_uploaded),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = !isSyncing,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh messages")
                    }
                    when (recipientReadiness) {
                        RecipientReadiness.Loading ->
                            IconButton(
                                onClick = { coroutineScope.launch { snackbarHostState.showSnackbar(rosterLoadingExplanation) } },
                                modifier =
                                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                        contentDescription = rosterLoadingLabel
                                    },
                            ) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(24.dp).semantics {
                                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                                        },
                                    strokeWidth = 2.dp,
                                )
                            }
                        is RecipientReadiness.Ready -> Unit
                        is RecipientReadiness.Unavailable -> Unit
                    }
                    IconButton(
                        onClick = onShowParticipants,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(Icons.Filled.People, contentDescription = stringResource(R.string.participants_action))
                    }
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
                sendEnabled = recipientReadiness is RecipientReadiness.Ready,
                rosterUnavailable = recipientReadiness as? RecipientReadiness.Unavailable,
                rosterLoading = recipientReadiness is RecipientReadiness.Loading,
                onRetryRoster = viewModel::retryRecipientRoster,
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
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(itemsWithDivider, key = { _, item ->
                    when (item) {
                        is DividerMarker -> "divider"
                        is ChatFeedViewModel.FeedRow -> item.messageId
                        else -> "unknown"
                    }
                }) { _, item ->
                    when (item) {
                        is DividerMarker -> NewMessagesDivider()
                        is ChatFeedViewModel.FeedRow ->
                            MessageRow(item, onRetry = { viewModel.retryFailed(item.messageId) })
                    }
                }
            }
        }
    }
}

/** Sentinel inserted into the message list at the READ→SENT boundary. */
private data object DividerMarker

/**
 * The "New messages" divider line shown between read and unread messages. A thin horizontal rule
 * with a muted label, aligned to the same visual grid as message rows.
 */
@Composable
private fun NewMessagesDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = "New messages",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** True when no messages exist beyond the unread boundary. */
private fun androidx.compose.foundation.lazy.LazyListState.isAtBottom(): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    val totalItems = layoutInfo.totalItemsCount
    return totalItems == 0 || lastVisible.index >= totalItems - 1 - NEAR_BOTTOM_SLACK
}

private const val NEAR_BOTTOM_SLACK = 2

/** The notification id used by [org.openwebdav.messenger.sync.SyncNotifier] (id=100). */
private const val SYNC_NOTIFIER_ID = 100

/** How long the user must view messages before they are marked read (ms). */
private const val MARK_READ_DELAY_MS = 2_500L

@Composable
private fun MessageRow(
    row: ChatFeedViewModel.FeedRow,
    onRetry: () -> Unit,
) {
    val align = if (row.isMine) Alignment.End else Alignment.Start
    val borderAlpha =
        when (row.sendStatus) {
            MessageEntity.STATUS_SENT -> 0.06f // barely visible = delivered
            MessageEntity.STATUS_READ -> 0.14f // normal = seen
            else -> 0.10f // SENDING or other
        }
    val borderColor =
        if (row.isMine) {
            MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    val shape =
        RoundedCornerShape(
            topStart = 12.dp,
            topEnd = 12.dp,
            bottomStart = if (row.isMine) 12.dp else 2.dp,
            bottomEnd = if (row.isMine) 2.dp else 12.dp,
        )

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = align,
    ) {
        if (!row.isMine && row.senderName != null) {
            Text(
                text = row.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        } else if (!row.isMine && row.senderKey != null) {
            Text(
                text = row.senderKey,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon on the left for own messages
            if (row.isMine) {
                when (row.sendStatus) {
                    MessageEntity.STATUS_SENDING ->
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = "Sending",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    MessageEntity.STATUS_FAILED ->
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Retry failed message" },
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                }
            }

            Text(
                text = row.body,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
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
    sendEnabled: Boolean,
    rosterUnavailable: RecipientReadiness.Unavailable?,
    rosterLoading: Boolean,
    onRetryRoster: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        sendError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        rosterUnavailable?.let {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetryRoster) { Text("Retry") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                enabled = !rosterLoading,
                placeholder = {
                    Text(if (rosterLoading) stringResource(R.string.roster_loading_placeholder) else "Message")
                },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Message" },
            )
            IconButton(
                onClick = onSend,
                enabled = draft.isNotBlank() && sendEnabled,
                modifier = Modifier.semantics { contentDescription = "Send" },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
        }
    }
}
