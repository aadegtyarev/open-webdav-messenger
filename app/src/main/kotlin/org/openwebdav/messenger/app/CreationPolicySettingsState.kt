package org.openwebdav.messenger.app

internal sealed interface CreationPolicySettingsState {
    data object Loading : CreationPolicySettingsState

    data class Policy(val allowPublic: Boolean, val allowPrivate: Boolean, val revision: Long) : CreationPolicySettingsState

    data object DefaultAllowed : CreationPolicySettingsState

    data object Unavailable : CreationPolicySettingsState

    data object Blocked : CreationPolicySettingsState
}

internal enum class CreationPolicyUpdateResult { Saved, NotAuthorized, Unavailable, Conflict, TooManyOrigins }
