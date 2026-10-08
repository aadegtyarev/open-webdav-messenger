package org.openwebdav.messenger.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.protocol.Hex

/** Applies verified remote roster results only through the caller's exact-context guard. */
internal class RecipientRosterEnricher(
    private val scope: CoroutineScope,
    private val graph: RuntimeGraph,
    private val applyIfCurrent: (() -> Unit) -> Boolean,
    private val read: suspend () -> DirectoryReadResult,
    private val preserveReadyOnFailure: Boolean = false,
    private val commitVerified: ((DirectoryReadResult, () -> Boolean, () -> Boolean) -> Boolean)? = null,
) {
    fun start(): Job =
        scope.launch {
            if (!applyIfCurrent {}) return@launch
            try {
                val result = read()
                if (result.listingFailed) {
                    if (!preserveReadyOnFailure) {
                        applyIfCurrent { graph.updateRecipientReadiness(RecipientReadiness.Unavailable(UNAVAILABLE)) }
                    }
                } else {
                    val update = {
                        graph.memberNames =
                            result.entries.associate {
                                Hex.encode(it.copySigningPublicKey()) to it.displayName
                            }
                        graph.setMemberNamesError(null)
                        graph.updateRecipientReadiness(
                            RecipientReadiness.Ready(
                                members = result.entries.map { Hex.encode(it.copySigningPublicKey()) },
                                participants =
                                    verifiedParticipants(
                                        result.entries,
                                        graph.senderIdentifier,
                                        graph.identity.copySignPublic(),
                                    ),
                            ),
                        )
                    }
                    val commit = commitVerified
                    if (commit == null) {
                        applyIfCurrent(update)
                    } else {
                        commit(result, { applyIfCurrent {} }, { applyIfCurrent(update) })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!preserveReadyOnFailure) {
                    applyIfCurrent { graph.updateRecipientReadiness(RecipientReadiness.Unavailable(UNAVAILABLE)) }
                }
            }
        }

    private companion object {
        const val UNAVAILABLE = "Verified members unavailable — reconnect and retry"
    }
}
