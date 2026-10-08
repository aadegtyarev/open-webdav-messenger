package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey

internal class GroupChatOpenCoordinator(
    private val selectionGuard: RuntimeSelectionGuard,
    private val requestCoordinator: ChatOpenRequestCoordinator,
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
        val requestToken: ChatOpenRequestCoordinator.Token,
        val chatKey: ChatKey,
        val graph: RuntimeGraph,
    )

    fun prepare(
        chatId: String,
        chatName: String,
        communityId: String,
        expectedSelectionRevision: Long?,
        requestToken: ChatOpenRequestCoordinator.Token,
    ): Plan? {
        if (!requestCoordinator.isCurrent(requestToken)) return null
        if (expectedSelectionRevision != null && !selectionGuard.isCurrent(expectedSelectionRevision)) return null
        val chatKey = loadChatKey(chatId) ?: return null
        if (currentCommunityId() != communityId &&
            !requestCoordinator.runIfCurrent(requestToken) { activateCommunity(communityId) }
        ) {
            return null
        }
        var plan: Plan? = null
        if (!requestCoordinator.runIfCurrent(requestToken) {
                val graph = currentGraph()?.takeIf { it.communityId == communityId } ?: return@runIfCurrent false
                val revision = selectionGuard.begin()
                plan = Plan(chatId, chatName, communityId, revision, requestToken, chatKey, graph)
                true
            }
        ) {
            return null
        }
        return plan
    }
}
