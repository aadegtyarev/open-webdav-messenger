package org.openwebdav.messenger.membership

internal data class PrivateClaimRetryCandidate(val chatId: String, val pending: Boolean)

/** Rotates pending retries first, then new publications; completed claims are omitted by the caller. */
internal object PrivateClaimRetryQueue {
    fun select(
        candidates: List<PrivateClaimRetryCandidate>,
        lastChatId: String?,
        pendingLimit: Int,
        newLimit: Int,
    ): List<PrivateClaimRetryCandidate> {
        val pending = rotate(candidates.filter { it.pending }, lastChatId).take(pendingLimit)
        val fresh = rotate(candidates.filterNot { it.pending }, lastChatId).take(newLimit)
        return pending + fresh
    }

    private fun rotate(
        rows: List<PrivateClaimRetryCandidate>,
        lastChatId: String?,
    ): List<PrivateClaimRetryCandidate> {
        val sorted = rows.distinctBy { it.chatId }.sortedBy { it.chatId }
        val next = sorted.indexOfFirst { it.chatId > (lastChatId ?: "") }.let { if (it < 0) 0 else it }
        return sorted.drop(next) + sorted.take(next)
    }
}
