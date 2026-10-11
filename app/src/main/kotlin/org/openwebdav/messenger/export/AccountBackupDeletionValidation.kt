package org.openwebdav.messenger.export

internal object AccountBackupDeletionValidation {
    fun isValid(community: CommunityBackup): Boolean {
        val directMessages = community.chats.filter { it.kind == "dm" }.mapTo(mutableSetOf()) { it.id }
        val hidden = community.hiddenDmChatIds
        val suppressed = community.suppressedDmMessageIds
        return hidden.distinct().size == hidden.size && hidden.size <= MAX_CHATS &&
            hidden.all(directMessages::contains) && suppressed.size <= MAX_CHATS &&
            suppressed.all { (chatId, ids) ->
                chatId in directMessages && ids.size <= MAX_IDS_PER_CHAT && ids.distinct().size == ids.size
            } && suppressed.values.sumOf { it.size } <= MAX_IDS_TOTAL &&
            AccountBackupGraveyardValidation.isValid(community.chatDeletions)
    }

    private const val MAX_CHATS = 1000
    private const val MAX_IDS_PER_CHAT = 10_000
    private const val MAX_IDS_TOTAL = 100_000
}
