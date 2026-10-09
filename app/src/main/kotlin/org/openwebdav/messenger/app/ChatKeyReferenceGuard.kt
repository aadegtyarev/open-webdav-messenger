package org.openwebdav.messenger.app

import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry

internal class ChatKeyReferenceGuard(
    private val loadCommunities: () -> List<CommunityRegistry.Entry>,
    private val loadCommunityIds: () -> Set<String>,
    private val loadStoredAnchor: (String) -> String?,
    private val loadChats: (String) -> List<ChatRegistry.Entry>,
) {
    fun decision(
        deletingCommunityId: String,
        chatId: String,
    ): Decision =
        runCatching {
            val communities = loadCommunities()
            if (communities.any { it.chatId == chatId }) return Decision.Referenced
            val communityIds = (communities.map { it.id } + loadCommunityIds()).toSet()
            for (communityId in communityIds) {
                val anchorId = loadStoredAnchor(communityId) ?: return Decision.Unavailable
                if (anchorId == chatId) return Decision.Referenced
                val chats = loadChats(communityId)
                if (communityId != deletingCommunityId && chats.any { it.id == chatId }) return Decision.Referenced
            }
            Decision.Unreferenced
        }.getOrDefault(Decision.Unavailable)

    fun removeIfUnreferenced(
        deletingCommunityId: String,
        chatId: String,
        remove: () -> Unit,
    ): Decision =
        when (decision(deletingCommunityId, chatId)) {
            Decision.Unreferenced ->
                runCatching(remove).fold({ Decision.Unreferenced }, { Decision.Unavailable })
            Decision.Referenced -> Decision.Referenced
            Decision.Unavailable -> Decision.Unavailable
        }

    enum class Decision { Unreferenced, Referenced, Unavailable }
}
