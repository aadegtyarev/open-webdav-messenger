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
) {
    fun start(): Job =
        scope.launch {
            if (!applyIfCurrent {}) return@launch
            try {
                val result = read()
                applyIfCurrent {
                    if (result.listingFailed) {
                        graph.updateRecipientReadiness(RecipientReadiness.Unavailable(UNAVAILABLE))
                    } else {
                        graph.memberNames =
                            result.entries.associate {
                                Hex.encode(it.copySigningPublicKey()) to it.displayName
                            }
                        graph.setMemberNamesError(null)
                        graph.updateRecipientReadiness(
                            RecipientReadiness.Ready(result.entries.map { Hex.encode(it.copySigningPublicKey()) }),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                applyIfCurrent { graph.updateRecipientReadiness(RecipientReadiness.Unavailable(UNAVAILABLE)) }
            }
        }

    private companion object {
        const val UNAVAILABLE = "Verified members unavailable — reconnect and retry"
    }
}
