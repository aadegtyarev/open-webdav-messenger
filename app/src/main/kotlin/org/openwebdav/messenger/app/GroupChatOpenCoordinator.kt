package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey

internal class GroupChatOpenCoordinator(
    private val selectionGuard: RuntimeSelectionGuard,
    private val currentCommunityId: () -> String,
    private val loadChatKey: (String) -> ChatKey?,
    private val activateCommunity: (String) -> Boolean,
    private val currentGraph: () -> RuntimeGraph?,
) {
    data class Plan(
        val chatId: String,
        val chatName: String,
        val communityId: String,
        val selectionRevision: Long,
        val chatKey: ChatKey,
        val graph: RuntimeGraph,
    )

    fun prepare(
        chatId: String,
        chatName: String,
        communityId: String,
        expectedSelectionRevision: Long?,
    ): Plan? {
        if (expectedSelectionRevision != null && !selectionGuard.isCurrent(expectedSelectionRevision)) return null
        val chatKey = loadChatKey(chatId) ?: return null
        if (currentCommunityId() != communityId && !activateCommunity(communityId)) return null
        val graph = currentGraph()?.takeIf { it.communityId == communityId } ?: return null
        return Plan(chatId, chatName, communityId, selectionGuard.begin(), chatKey, graph)
    }
}
