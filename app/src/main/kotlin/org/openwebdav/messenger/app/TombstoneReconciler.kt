package org.openwebdav.messenger.app

import org.openwebdav.messenger.governance.CommunityTombstoneChatScanner

/** Coordinates bounded remote discovery, authority validation, and a generation-fenced local apply. */
internal class TombstoneReconciler(
    private val isCurrent: () -> Boolean,
    private val scan: suspend () -> CommunityTombstoneChatScanner.Result,
    private val authorize: suspend (CommunityTombstoneChatScanner.Result.Found) -> Boolean,
    private val apply: suspend (CommunityTombstoneChatScanner.Result.Found) -> Boolean,
) {
    suspend fun reconcile(): Result {
        if (!isCurrent()) return Result.Stale
        return when (val candidate = scan()) {
            CommunityTombstoneChatScanner.Result.Empty -> Result.NoTombstone
            CommunityTombstoneChatScanner.Result.Unavailable -> Result.Unavailable
            CommunityTombstoneChatScanner.Result.Conflict -> Result.Conflict
            is CommunityTombstoneChatScanner.Result.Found ->
                try {
                    if (!authorize(candidate)) return Result.Unauthorized
                    if (!isCurrent()) return Result.Stale
                    if (apply(candidate)) Result.Applied else Result.Stale
                } finally {
                    candidate.signedBytes.fill(0)
                    candidate.storedBytes.fill(0)
                }
        }
    }

    enum class Result { NoTombstone, Applied, Unavailable, Unauthorized, Conflict, Stale }
}
