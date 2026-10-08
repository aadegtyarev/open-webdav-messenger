package org.openwebdav.messenger.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface RecipientReadiness {
    data object Loading : RecipientReadiness

    data class Ready(
        val members: List<String>,
        val participants: List<VerifiedParticipant> = emptyList(),
    ) : RecipientReadiness

    data class Unavailable(val message: String) : RecipientReadiness
}

/** Atomically publishes readiness and the exact verified recipient snapshot used by sends. */
internal class VerifiedRecipientRoster(
    initial: RecipientReadiness,
    private val senderIdentifier: String,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial.normalized())
    val state: StateFlow<RecipientReadiness> = _state.asStateFlow()

    fun snapshot(): RecipientReadiness = synchronized(lock) { _state.value }

    fun update(value: RecipientReadiness) {
        synchronized(lock) { _state.value = value.normalized() }
    }

    private fun RecipientReadiness.normalized(): RecipientReadiness =
        when (this) {
            RecipientReadiness.Loading -> this
            is RecipientReadiness.Ready ->
                copy(
                    members = (listOf(senderIdentifier) + members).distinct().toList(),
                    participants = participants.toList(),
                )
            is RecipientReadiness.Unavailable -> this
        }
}
