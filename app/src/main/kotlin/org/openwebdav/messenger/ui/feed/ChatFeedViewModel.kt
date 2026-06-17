package org.openwebdav.messenger.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.MessageSendService
import org.openwebdav.messenger.app.ReadReceiptService
import org.openwebdav.messenger.app.RuntimeGraph
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.transport.TransportFactory

/**
 * Chat-feed ViewModel (`ui-chat-surface` arch note Choice 4 `ChatFeedViewModel`; Scenarios 5–6). It reads
 * the chat history **solely** from the existing observable Room `Flow` (`MessageStore.observeChat`) — no
 * second message list, no second persistence path — so a background poll landing a new message surfaces it
 * on its own and a send echo + later poll re-fetch dedup to one row (the design that makes those free; arch
 * note behavioral risk "Feed observes Room"). [send] delegates to [MessageSendService] (build → seal →
 * write-once → local echo), off the UI thread.
 *
 * Built from the already-joined [RuntimeGraph]; the composable hoists [messages] and [draft] and raises a
 * send event (stack-notes Compose). The body is mapped to a plain [FeedRow] of literal text — no markup,
 * no link, no auto-load (SC8 stays closed; ui-guide literal-plain-text).
 */
internal class ChatFeedViewModel(
    private val graph: RuntimeGraph,
    private val sendService: MessageSendService = MessageSendService(graph),
) : ViewModel() {
    val communityName: String = graph.communityName

    init {
        // Load member names from directory so sender labels appear without waiting for a poll cycle.
        viewModelScope.launch {
            try {
                val names = AppContainer.loadMemberNames()
                android.util.Log.d("ChatFeedVM", "loadMemberNames returned ${names.size} names: $names")
                if (names.isNotEmpty()) {
                    graph.memberNames = names
                    android.util.Log.d("ChatFeedVM", "memberNames set to ${graph.memberNames.size} entries")
                    _memberNamesError.value = null
                } else {
                    android.util.Log.w("ChatFeedVM", "loadMemberNames returned empty — no sender names available")
                    _memberNamesError.value = "Member names not available — showing key prefixes"
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatFeedVM", "loadMemberNames failed", e)
                _memberNamesError.value = "Couldn't load member names"
            }
        }
    }

    private val _memberNamesError = MutableStateFlow<String?>(null)
    val memberNamesError: StateFlow<String?> = _memberNamesError

    private val _isSyncing = MutableStateFlow(false)

    /** `true` while a manual sync is in progress — the UI shows a loading state on the refresh button. */
    val isSyncing: StateFlow<Boolean> = _isSyncing

    /** Human-readable relative time of the last successful sync ("Just now", "N seconds ago", …, "Never"). */
    val lastSyncText: StateFlow<String> =
        graph.lastSyncTime
            .map { millis -> formatLastSyncTime(millis) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "Never")

    /** The chat history, oldest→newest, observed from Room — re-maps when member names change. */
    val messages: StateFlow<List<FeedRow>> =
        combine(
            graph.store.observeChat(graph.chatId),
            graph.memberNamesFlow,
        ) { rows, names ->
            rows.map { it.toFeedRow(names) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft

    private val _sendError = MutableStateFlow<String?>(null)

    /** A transient send-failure message, or `null`. Cleared when the draft changes or a send succeeds. */
    val sendError: StateFlow<String?> = _sendError

    fun onDraft(v: String) {
        _draft.value = v
        _sendError.value = null
    }

    /**
     * Send the current draft: clear the field, persist a local echo with SENDING status,
     * then attempt the disk write. On success, mark the echo SENT. On failure,
     * mark FAILED — the message stays in chat with an error indicator.
     */
    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        _sendError.value = null
        viewModelScope.launch {
            val result =
                try {
                    sendService.send(text)
                } catch (_: Exception) {
                    null
                }
            if (result != null && result.logWritten) {
                graph.store.markSent(result.messageId)
            } else {
                // Mark the echo as FAILED if it was created (it was — we persist before sending).
                if (result != null) {
                    graph.store.markFailed(result.messageId)
                }
                _sendError.value = SEND_FAILED_MESSAGE
            }
        }
    }

    /** Retry sending a failed message — re-seal and re-send. */
    fun retryFailed(
        messageId: String,
        body: String,
    ) {
        _sendError.value = null
        viewModelScope.launch {
            val result =
                try {
                    sendService.send(body)
                } catch (_: Exception) {
                    null
                }
            if (result != null && result.logWritten) {
                graph.store.markSent(result.messageId)
            } else {
                _sendError.value = SEND_FAILED_MESSAGE
            }
        }
    }

    /** Trigger an immediate poll cycle for the current chat. No-op if a sync is already in progress. */
    fun syncNow() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                graph.requestSync()
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /** Format an epoch-millis [lastSyncMillis] (0 = never) as a human-readable relative time. */
    private fun formatLastSyncTime(lastSyncMillis: Long): String {
        if (lastSyncMillis <= 0L) return "Never"
        val elapsed = System.currentTimeMillis() - lastSyncMillis
        return when {
            elapsed < 10_000L -> "Just now"
            elapsed < 60_000L -> "${elapsed / 1_000} seconds ago"
            elapsed < 3_600_000L -> "${elapsed / 60_000} minutes ago"
            else -> "${elapsed / 3_600_000} hours ago"
        }
    }

    /** Mark all messages up to [orderToken] as READ, and write receipt to disk. */
    fun markRead(orderToken: String) {
        viewModelScope.launch {
            graph.store.markMessagesReadUpTo(graph.chatId, orderToken)
            // Also write a read receipt to the WebDAV disk so other members can see.
            val transport = TransportFactory.create(graph.config)
            ReadReceiptService(transport).writeReceipt(
                graph.senderIdentifier,
                graph.chatId,
                orderToken,
            )
        }
    }

    /** Mark the latest message in the current chat as read (called when the feed opens and after sync). */
    fun markLatestRead() {
        viewModelScope.launch {
            val allMessages = graph.store.messagesForChat(graph.chatId)
            val lastToken = allMessages.lastOrNull()?.orderToken ?: return@launch
            markRead(lastToken)
        }
    }

    private fun MessageEntity.toFeedRow(names: Map<String, String>): FeedRow =
        FeedRow(
            messageId = messageId,
            body = body ?: "",
            isMine = senderSignPub == graph.senderIdentifier,
            sendStatus = sendStatus,
            senderName = if (senderSignPub != graph.senderIdentifier) names[senderSignPub] else null,
            senderKey = if (senderSignPub != graph.senderIdentifier) senderSignPub.take(8) else null,
        )

    /** A rendered feed row — literal plain-text [body], never styled / linked / auto-loaded (SC8). */
    data class FeedRow(
        val messageId: String,
        val body: String,
        val isMine: Boolean,
        val sendStatus: String,
        val senderName: String? = null,
        val senderKey: String? = null,
    )

    companion object {
        /** Plain-language send-failure message (ui-guide: inline, non-technical). */
        const val SEND_FAILED_MESSAGE = "Couldn't send — check your connection and try again."

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val SEND_MAX_ATTEMPTS = 2
        private const val SEND_RETRY_DELAY_MS = 1_000L
    }
}
