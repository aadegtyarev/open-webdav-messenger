package org.openwebdav.messenger.ui.feed

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.openwebdav.messenger.R
import org.openwebdav.messenger.app.ChatDeleteCapability
import org.openwebdav.messenger.app.ChatDeletionStatus
import org.openwebdav.messenger.app.RecipientReadiness
import org.openwebdav.messenger.data.DmLocalDeleteResult
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
    canDeleteLocalDm: Boolean = false,
    canDeleteForAll: Boolean = false,
    deleteCapabilityOverride: ChatDeleteCapability? = null,
    chatTitle: String = "",
    onLocalDmDeleted: () -> Unit = {},
    onChatDeleted: (ChatDeletionStatus) -> Unit = {},
    toolbarSafeArea: Modifier = Modifier.statusBarsPadding().displayCutoutPadding(),
    viewModel: ChatFeedViewModel =
        viewModel(
            key = runtimeScopeKey("feed"),
            factory = FeedViewModelFactory,
        ),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val composerState by viewModel.composerState.collectAsStateWithLifecycle()
    val draft = composerState.draft
    val editorSessionToken = composerState.sessionToken
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val lastSyncText by viewModel.lastSyncText.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val memberNamesError by viewModel.memberNamesError.collectAsStateWithLifecycle()
    val recipientReadiness by viewModel.recipientReadiness.collectAsStateWithLifecycle()
    val privateClaimStatus by viewModel.privateClaimStatus.collectAsStateWithLifecycle()
    val projectedDeleteCapability by viewModel.deleteCapability.collectAsStateWithLifecycle()
    val deleteCapability = deleteCapabilityOverride ?: projectedDeleteCapability
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val rosterLoadingLabel = stringResource(R.string.roster_loading_accessibility)
    val deleteAuthorityLoadingLabel = stringResource(R.string.chat_delete_authority_loading)
    val deleteAuthorityRetryLabel = stringResource(R.string.chat_delete_authority_retry)
    val participantsActionLabel = stringResource(R.string.participants_action)
    val inviteActionLabel = stringResource(R.string.invite_action)
    val participantsActionDescription =
        when (recipientReadiness) {
            is RecipientReadiness.Loading -> stringResource(R.string.participants_loading_disabled)
            is RecipientReadiness.Unavailable -> stringResource(R.string.participants_unavailable_disabled)
            is RecipientReadiness.Ready -> participantsActionLabel
        }
    val inviteActionDescription =
        when (recipientReadiness) {
            is RecipientReadiness.Loading -> stringResource(R.string.invite_roster_loading)
            is RecipientReadiness.Unavailable -> stringResource(R.string.invite_roster_unavailable)
            is RecipientReadiness.Ready -> inviteActionLabel
        }
    val rosterActionsEnabled = recipientReadiness is RecipientReadiness.Ready
    val rosterIsLoading = recipientReadiness is RecipientReadiness.Loading
    val authorityIsLoading = deleteCapability is ChatDeleteCapability.Loading
    val refreshingMessagesLabel = stringResource(R.string.toolbar_refreshing_messages)
    val loadingProgressLabel =
        when {
            isSyncing && rosterIsLoading && authorityIsLoading ->
                stringResource(
                    R.string.feed_operations_loading,
                    refreshingMessagesLabel,
                    stringResource(R.string.feed_operations_loading, rosterLoadingLabel, deleteAuthorityLoadingLabel),
                )
            isSyncing && rosterIsLoading -> stringResource(R.string.feed_operations_loading, refreshingMessagesLabel, rosterLoadingLabel)
            isSyncing && authorityIsLoading ->
                stringResource(R.string.feed_operations_loading, refreshingMessagesLabel, deleteAuthorityLoadingLabel)
            rosterIsLoading && authorityIsLoading ->
                stringResource(R.string.feed_operations_loading, rosterLoadingLabel, deleteAuthorityLoadingLabel)
            isSyncing -> refreshingMessagesLabel
            rosterIsLoading -> rosterLoadingLabel
            authorityIsLoading -> deleteAuthorityLoadingLabel
            else -> ""
        }
    val displayTitle = viewModel.communityName
    val titleStatus =
        when (privateClaimStatus) {
            PrivateClaimPublicationStatus.PENDING -> stringResource(R.string.private_claim_pending)
            PrivateClaimPublicationStatus.UPLOADED -> stringResource(R.string.private_claim_uploaded)
            PrivateClaimPublicationStatus.NOT_PRIVATE -> lastSyncText
        }
    val moreActionsDescription = stringResource(R.string.toolbar_more_actions, chatTitle.ifBlank { displayTitle })
    val showOverflowActions =
        canDeleteForAll || canDeleteLocalDm || deleteCapability !is ChatDeleteCapability.NotAuthorized
    var showMoreActions by remember { mutableStateOf(false) }
    var showDeleteDmConfirmation by remember { mutableStateOf(false) }
    var showDeleteChatConfirmation by remember { mutableStateOf(false) }
    var typedDeleteTitle by remember { mutableStateOf("") }
    var deletingChat by remember { mutableStateOf(false) }

    if (showDeleteChatConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!deletingChat) showDeleteChatConfirmation = false },
            title = { Text(stringResource(R.string.chat_delete_all_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.chat_delete_all_confirmation, chatTitle))
                    OutlinedTextField(
                        value = typedDeleteTitle,
                        onValueChange = { typedDeleteTitle = it },
                        label = { Text(stringResource(R.string.dm_delete_type_title)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deletingChat && typedDeleteTitle == chatTitle,
                    onClick = {
                        deletingChat = true
                        viewModel.deleteGroupForAll { result ->
                            deletingChat = false
                            showDeleteChatConfirmation = false
                            typedDeleteTitle = ""
                            if (result in
                                setOf(
                                    ChatDeletionStatus.LOCAL_COMMITTED,
                                    ChatDeletionStatus.TOMBSTONE_PUBLISHED,
                                    ChatDeletionStatus.CLEANUP_PENDING,
                                    ChatDeletionStatus.COMPLETE,
                                )
                            ) {
                                onChatDeleted(result)
                            } else {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_delete_failure))
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.chat_delete_all_action)) }
            },
            dismissButton = {
                TextButton(enabled = !deletingChat, onClick = { showDeleteChatConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDeleteDmConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteDmConfirmation = false },
            title = { Text(stringResource(R.string.dm_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.dm_delete_confirmation, chatTitle))
                    OutlinedTextField(
                        value = typedDeleteTitle,
                        onValueChange = { typedDeleteTitle = it },
                        label = { Text(stringResource(R.string.dm_delete_type_title)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typedDeleteTitle == chatTitle,
                    onClick = {
                        showDeleteDmConfirmation = false
                        typedDeleteTitle = ""
                        viewModel.deleteLocalDm { result ->
                            when (result) {
                                DmLocalDeleteResult.Deleted, DmLocalDeleteResult.AlreadyHidden -> onLocalDmDeleted()
                                DmLocalDeleteResult.SuppressionLimitReached ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.dm_delete_limit))
                                    }
                                DmLocalDeleteResult.StorageFailure ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.dm_delete_failure))
                                    }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.dm_delete_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDmConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

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
            Box(
                modifier = Modifier.fillMaxWidth().then(toolbarSafeArea),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = displayTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = titleStatus,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier =
                                Modifier.semantics {
                                    if (privateClaimStatus != PrivateClaimPublicationStatus.NOT_PRIVATE) {
                                        contentDescription = "$lastSyncText. $titleStatus"
                                    }
                                },
                        )
                    }
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (loadingProgressLabel.isNotEmpty()) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier.size(22.dp).semantics {
                                        contentDescription = loadingProgressLabel
                                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                                    },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(
                                onClick = { viewModel.syncNow() },
                                enabled = !isSyncing,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh messages")
                            }
                        }
                    }
                    IconButton(
                        onClick = onShowParticipants,
                        enabled = rosterActionsEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.People, contentDescription = participantsActionDescription)
                    }
                    IconButton(
                        onClick = onShowInvite,
                        enabled = rosterActionsEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = inviteActionDescription)
                    }
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (showOverflowActions) {
                            IconButton(
                                onClick = { showMoreActions = true },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.MoreVert, contentDescription = moreActionsDescription)
                            }
                            DropdownMenu(
                                expanded = showMoreActions,
                                onDismissRequest = { showMoreActions = false },
                            ) {
                                when {
                                    deleteCapability is ChatDeleteCapability.Loading ->
                                        DropdownMenuItem(
                                            text = { Text(deleteAuthorityLoadingLabel) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                    deleteCapability is ChatDeleteCapability.Unavailable -> {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_delete_authority_unavailable_status)) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                        DropdownMenuItem(
                                            text = { Text(deleteAuthorityRetryLabel) },
                                            onClick = {
                                                showMoreActions = false
                                                viewModel.refreshDeleteCapability()
                                            },
                                        )
                                    }
                                    deleteCapability is ChatDeleteCapability.NotAuthorized && !canDeleteForAll && canDeleteLocalDm ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_delete_authority_not_authorized)) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                }
                                if (canDeleteForAll || deleteCapability is ChatDeleteCapability.Available) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_delete_all_action)) },
                                        onClick = {
                                            showMoreActions = false
                                            showDeleteChatConfirmation = true
                                        },
                                    )
                                }
                                if (canDeleteLocalDm) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dm_delete_action)) },
                                        onClick = {
                                            showMoreActions = false
                                            showDeleteDmConfirmation = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Composer(
                draft = draft,
                editorSessionToken = editorSessionToken,
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
    val retryLabel = stringResource(R.string.message_retry)
    val align = if (row.isMine) Alignment.End else Alignment.Start
    val borderAlpha =
        when (row.sendStatus) {
            MessageEntity.STATUS_SENT, MessageEntity.STATUS_READ -> 0.06f
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
            if (row.isMine) {
                val statusLabel =
                    when (row.sendStatus) {
                        MessageEntity.STATUS_SENDING -> stringResource(R.string.message_status_queued)
                        MessageEntity.STATUS_FAILED -> stringResource(R.string.message_status_failed)
                        else -> stringResource(R.string.message_status_sent)
                    }
                val failedStatusRetryLabel = stringResource(R.string.message_failed_retry, statusLabel, retryLabel)
                if (row.sendStatus == MessageEntity.STATUS_FAILED) {
                    IconButton(
                        onClick = onRetry,
                        modifier =
                            Modifier.size(48.dp).semantics {
                                contentDescription = failedStatusRetryLabel
                            },
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    val isQueued = row.sendStatus == MessageEntity.STATUS_SENDING
                    val statusIcon = if (isQueued) Icons.Filled.Schedule else Icons.Filled.CheckCircle
                    val statusTint =
                        if (isQueued) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                    Icon(
                        statusIcon,
                        contentDescription = statusLabel,
                        modifier = Modifier.size(16.dp),
                        tint = statusTint,
                    )
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
    editorSessionToken: Long,
    sendError: String?,
    onDraft: (String, Long) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    rosterUnavailable: RecipientReadiness.Unavailable?,
    rosterLoading: Boolean,
    onRetryRoster: () -> Unit,
) {
    val rosterLoadingExplanation = stringResource(R.string.roster_loading_explanation)
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        if (rosterLoading) {
            Text(
                rosterLoadingExplanation,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
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
            ComposerDraftField(
                draft = draft,
                sessionToken = editorSessionToken,
                onDraft = onDraft,
                enabled = !rosterLoading,
                placeholder = if (rosterLoading) stringResource(R.string.roster_loading_placeholder) else "Message",
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
