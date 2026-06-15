package org.openwebdav.messenger.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openwebdav.messenger.app.MessageSendService
import org.openwebdav.messenger.app.RuntimeGraph
import org.openwebdav.messenger.data.MessageEntity

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

    /** The chat history, oldest→newest, observed from Room (offline, off-main-thread). */
    val messages: StateFlow<List<FeedRow>> =
        graph.store
            .observeChat(graph.chatId)
            .map { rows -> rows.map { it.toFeedRow() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

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
     * Send the current draft. The draft is cleared only AFTER the send succeeds (the write landed on the
     * disk); if sealing or the disk write fails, the typed text is restored and a plain-language error
     * surfaces, so the user never silently loses what they wrote (review finding 8).
     */
    fun send() {
        val text = _draft.value
        if (text.isBlank()) return
        _draft.value = ""
        _sendError.value = null
        viewModelScope.launch {
            try {
                val result = sendService.send(text)
                if (!result.logWritten) {
                    // Disk write failed (network, permissions, folder) — restore draft, surface error.
                    if (_draft.value.isBlank()) _draft.value = text
                    _sendError.value = SEND_FAILED_MESSAGE
                }
            } catch (_: Exception) {
                if (_draft.value.isBlank()) _draft.value = text
                _sendError.value = SEND_FAILED_MESSAGE
            }
        }
    }

    private fun MessageEntity.toFeedRow(): FeedRow =
        FeedRow(
            messageId = messageId,
            // reactions carry no body; this slice renders text only.
            body = body ?: "",
            // senderIdentifier IS hex(identity.signPub), the same encoding MessageStore keys the row by.
            isMine = senderSignPub == graph.senderIdentifier,
        )

    /** A rendered feed row — literal plain-text [body], never styled / linked / auto-loaded (SC8). */
    data class FeedRow(
        val messageId: String,
        val body: String,
        val isMine: Boolean,
    )

    companion object {
        /** Plain-language send-failure message (ui-guide: inline, non-technical). */
        const val SEND_FAILED_MESSAGE = "Couldn't send — check your connection and try again."

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
