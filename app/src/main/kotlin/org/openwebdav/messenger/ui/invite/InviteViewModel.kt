package org.openwebdav.messenger.ui.invite

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.app.AppContainer

/**
 * Invite-display ViewModel (`ui-chat-surface` Scenario 2; arch note Choice 4 `InviteViewModel`). Builds the
 * `owdm1:` invite string (off the UI thread, via [AppContainer.buildInvite]) and the QR [ImageBitmap]
 * (via [QrEncoder]) for the joined chat, and exposes the always-visible bearer-token warning. The token is
 * a bearer secret — it is held only in this transient UI state, never logged.
 */
internal class InviteViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        build()
    }

    private fun build() {
        val graph =
            AppContainer.runtimeGraph() ?: run {
                _state.value = UiState(error = NO_CHAT_MESSAGE)
                return
            }
        viewModelScope.launch {
            val invite = AppContainer.buildInvite(graph)
            val qr = withContext(Dispatchers.Default) { QrEncoder.toImageBitmap(QrEncoder.encode(invite)) }
            _state.value = UiState(inviteString = invite, qr = qr)
        }
    }

    /**
     * @property inviteString the copyable `owdm1:` token (bearer — anyone holding it is in).
     * @property qr the QR rendering of the same string (decorative-with-a-purpose; the string is the
     *   TalkBack-readable alternative, ui-guide accessibility).
     */
    data class UiState(
        val inviteString: String? = null,
        val qr: ImageBitmap? = null,
        val error: String? = null,
    )

    companion object {
        const val NO_CHAT_MESSAGE = "No community to invite to yet."
    }
}
