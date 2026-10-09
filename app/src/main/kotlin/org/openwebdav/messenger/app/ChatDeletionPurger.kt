package org.openwebdav.messenger.app

internal class ChatDeletionPurger(
    private val isCurrent: () -> Boolean,
    private val fenceGraph: (String, String) -> Unit,
    private val invalidateRoster: (String, String) -> Unit,
    private val invalidatePrivateMembership: (String, String) -> Unit,
    private val invalidatePendingClaim: (String, String) -> Unit,
    private val invalidateRetryCursor: (String, String) -> Unit,
    private val removeChatKey: (String) -> Unit,
    private val removeRegistryEntry: (String, String) -> Unit,
) {
    fun purge(
        communityId: String,
        chatId: String,
        anchorChatId: String,
        kind: String,
        removeKey: Boolean,
    ): Boolean {
        if (kind == "general" || chatId == anchorChatId || !isCurrent()) return false
        var succeeded = true

        fun attempt(action: () -> Unit) {
            if (runCatching(action).isFailure) succeeded = false
        }
        attempt { fenceGraph(communityId, chatId) }
        attempt { invalidateRoster(communityId, chatId) }
        attempt { invalidatePrivateMembership(communityId, chatId) }
        attempt { invalidatePendingClaim(communityId, chatId) }
        attempt { invalidateRetryCursor(communityId, chatId) }
        if (removeKey) attempt { removeChatKey(chatId) }
        attempt { removeRegistryEntry(communityId, chatId) }
        return succeeded && isCurrent()
    }
}
