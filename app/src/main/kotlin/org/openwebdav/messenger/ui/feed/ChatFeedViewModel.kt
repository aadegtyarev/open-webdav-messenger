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

    fun onDraft(v: String) {
        _draft.value = v
    }

    /** Send the current draft; clears it optimistically (the local echo appears via the Room Flow). */
    fun send() {
        val text = _draft.value
        if (text.isBlank()) return
        _draft.value = ""
        viewModelScope.launch { sendService.send(text) }
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
