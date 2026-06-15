package org.openwebdav.messenger.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openwebdav.messenger.app.OnboardingService

/**
 * Member join ViewModel (`ui-chat-surface` arch note Choice 4 `JoinViewModel`; Scenarios 3–4). It accepts
 * a **pasted** invite string or a **scanned** one (same decode path), decodes reject-don't-guess via
 * [OnboardingService], persists the config silently, and signals success — **never** surfacing the disk
 * URL/username/password/folder. Its [UiState] deliberately carries NO credential fields, so the screen
 * cannot leak them (contract `member_join_from_invite_configures_silently_without_exposing_credentials`).
 *
 * The pasted text the user typed IS in [UiState.pasted] (their own input, echoed back as they type) — that
 * is the raw invite they hold, not a decoded credential; the decoded disk fields never enter the state.
 */
internal class JoinViewModel(
    private val onboarding: OnboardingService,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onPasted(v: String) = _state.update { it.copy(pasted = v, error = null) }

    /** Join from the pasted field. */
    fun joinFromPaste(onJoined: () -> Unit) = join(_state.value.pasted, onJoined)

    /** Join from a camera-scanned string (the scan affordance delivers it here — same path as paste). */
    fun joinFromScan(
        scanned: String,
        onJoined: () -> Unit,
    ) = join(scanned, onJoined)

    private fun join(
        invite: String,
        onJoined: () -> Unit,
    ) {
        if (_state.value.joining) return
        _state.update { it.copy(joining = true, error = null) }
        viewModelScope.launch {
            // The join does Keystore wrap + identity-ensure + engine build, any of which can throw (a
            // structurally-valid invite to an unreachable/failing disk). Without this guard a throw leaves
            // `joining` stuck forever and can crash the app via the uncaught coroutine exception (finding 4).
            val result =
                try {
                    onboarding.joinFromInvite(invite)
                } catch (_: Exception) {
                    _state.update { it.copy(joining = false, error = JOIN_FAILED_MESSAGE) }
                    return@launch
                }
            when (result) {
                is OnboardingService.JoinResult.Invalid ->
                    _state.update { it.copy(joining = false, error = INVALID_INVITE_MESSAGE) }

                is OnboardingService.JoinResult.Joined -> {
                    _state.update { it.copy(joining = false) }
                    onJoined()
                }
            }
        }
    }

    /**
     * Hoisted state — only the user's pasted text, the in-progress flag, and an error message. It holds
     * **no** decoded disk fields (URL/login/password/folder), by design.
     */
    data class UiState(
        val pasted: String = "",
        val joining: Boolean = false,
        val error: String? = null,
    )

    companion object {
        /** Plain-language broken/foreign-invite message (ui-guide error display; Scenario 4). */
        const val INVALID_INVITE_MESSAGE = "This invite isn't valid — check it and try again."

        /** Plain-language message when a valid invite fails to apply (disk unreachable / device error). */
        const val JOIN_FAILED_MESSAGE = "Couldn't join right now — check your connection and try again."
    }
}
