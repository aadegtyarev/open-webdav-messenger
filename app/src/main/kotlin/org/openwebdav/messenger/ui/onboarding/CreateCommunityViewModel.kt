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
 * Owner create-community ViewModel (`ui-chat-surface` arch note Choice 4 `CreateCommunityViewModel`). It
 * owns all the blocking work — HTTPS validation, identity ensure, random-key mint, Keystore persist,
 * engine reconfigure — behind [OnboardingService] (off the UI thread). The composable only renders the
 * hoisted [UiState] and raises field/submit events; no I/O or KDF runs in the composable (stack-notes
 * Compose). A non-HTTPS URL is refused **before any persist** (SC13) and surfaces as an inline error.
 */
internal class CreateCommunityViewModel(
    private val onboarding: OnboardingService,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onBaseUrl(v: String) = _state.update { it.copy(baseUrl = v, urlError = null, generalError = null) }

    fun onUsername(v: String) = _state.update { it.copy(username = v, generalError = null) }

    fun onAppPassword(v: String) = _state.update { it.copy(appPassword = v, generalError = null) }

    fun onChatRoot(v: String) = _state.update { it.copy(chatRoot = v, generalError = null) }

    fun onCommunityName(v: String) = _state.update { it.copy(communityName = v, generalError = null) }

    /** Validate + create; on success [onCreated] fires (navigate to the feed). */
    fun submit(onCreated: () -> Unit) {
        val s = _state.value
        if (s.submitting) return
        _state.update { it.copy(submitting = true, urlError = null, generalError = null) }
        viewModelScope.launch {
            val result =
                onboarding.createCommunity(
                    baseUrl = s.baseUrl,
                    username = s.username,
                    appPassword = s.appPassword,
                    chatRoot = s.chatRoot,
                    communityName = s.communityName,
                )
            when (result) {
                is OnboardingService.CreateResult.CleartextRefused ->
                    _state.update { it.copy(submitting = false, urlError = HTTPS_REQUIRED_MESSAGE) }

                is OnboardingService.CreateResult.Created -> {
                    _state.update { it.copy(submitting = false) }
                    onCreated()
                }
            }
        }
    }

    /**
     * Hoisted form state (arch note Choice 4: state in the ViewModel). The [appPassword] lives here only
     * transiently for the form; it is never logged and is handed straight to the Keystore-wrapped store.
     */
    data class UiState(
        val baseUrl: String = "",
        val username: String = "",
        val appPassword: String = "",
        val chatRoot: String = "",
        val communityName: String = "",
        val submitting: Boolean = false,
        val urlError: String? = null,
        val generalError: String? = null,
    ) {
        /** Enable submit only when every required field is non-blank (basic completeness, not validation). */
        val canSubmit: Boolean
            get() =
                !submitting &&
                    baseUrl.isNotBlank() &&
                    username.isNotBlank() &&
                    appPassword.isNotBlank() &&
                    communityName.isNotBlank()
    }

    companion object {
        /** Plain-language SC13 refusal (ui-guide: inline, non-technical). */
        const val HTTPS_REQUIRED_MESSAGE = "Use an https:// address — your password must travel encrypted."
    }
}
