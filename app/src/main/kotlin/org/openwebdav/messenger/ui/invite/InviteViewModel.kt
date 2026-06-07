package org.openwebdav.messenger.ui.invite

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
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
 *
 * [qrDispatcher] is the off-UI dispatcher the QR rasterization runs on (default [Dispatchers.Default]);
 * injectable so tests can drive it on a deterministic scheduler.
 */
internal class InviteViewModel(
    private val qrDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
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
            // If the invite string itself can't be built, the screen has nothing to show — surface an error.
            val invite =
                try {
                    AppContainer.buildInvite(graph)
                } catch (_: Exception) {
                    _state.value = UiState(error = BUILD_FAILED_MESSAGE)
                    return@launch
                }
            // The copyable string is the source of truth; the QR is a convenience. A token too long for QR
            // capacity throws WriterException — still surface the string (text-only fallback) + a note, so
            // the owner can always copy the invite (review finding 6).
            val qr =
                try {
                    withContext(qrDispatcher) { QrEncoder.toImageBitmap(QrEncoder.encode(invite)) }
                } catch (_: Exception) {
                    null
                }
            _state.value =
                UiState(
                    inviteString = invite,
                    qr = qr,
                    qrUnavailable = qr == null,
                )
        }
    }

    /**
     * @property inviteString the copyable `owdm1:` token (bearer — anyone holding it is in).
     * @property qr the QR rendering of the same string (decorative-with-a-purpose; the string is the
     *   TalkBack-readable alternative, ui-guide accessibility).
     * @property qrUnavailable `true` when the string built but the QR could not render (too long for QR
     *   capacity) — the screen shows the copyable string plus a note that the QR is unavailable.
     */
    data class UiState(
        val inviteString: String? = null,
        val qr: ImageBitmap? = null,
        val qrUnavailable: Boolean = false,
        val error: String? = null,
    )

    companion object {
        const val NO_CHAT_MESSAGE = "No community to invite to yet."

        /** Shown when the invite string itself could not be built (device error). */
        const val BUILD_FAILED_MESSAGE = "Couldn't build the invite right now — try again."

        /** Shown alongside the copyable string when the QR can't render (token too long for a QR code). */
        const val QR_UNAVAILABLE_MESSAGE = "QR code unavailable — copy and share the invite text instead."
    }
}
