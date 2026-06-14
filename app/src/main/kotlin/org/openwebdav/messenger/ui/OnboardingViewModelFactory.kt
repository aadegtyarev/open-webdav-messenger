package org.openwebdav.messenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.ui.onboarding.CreateCommunityViewModel
import org.openwebdav.messenger.ui.onboarding.JoinViewModel

/**
 * A tiny [ViewModelProvider.Factory] that builds the onboarding ViewModels from the process-scoped
 * [AppContainer] services (the project does not use a DI framework — this matches the existing factory
 * convention). The feed ViewModel is built from the runtime graph at its own call site (it needs the
 * already-joined graph), so it is not produced here.
 */
internal object OnboardingViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(CreateCommunityViewModel::class.java) ->
                CreateCommunityViewModel(AppContainer.onboarding()) as T

            modelClass.isAssignableFrom(JoinViewModel::class.java) ->
                JoinViewModel(AppContainer.onboarding()) as T

            else -> error("Unknown ViewModel: $modelClass")
        }
}
