package org.openwebdav.messenger.app

internal class RosterCacheMemoryPersistence : VerifiedRosterCachePersistence {
    private val entries = mutableMapOf<Pair<String, String>, CachedVerifiedRoster>()
    var writes = 0

    override fun load(
        communityId: String,
        chatId: String,
    ) = entries[communityId to chatId]

    override fun put(entry: CachedVerifiedRoster) {
        writes++
        entries[entry.communityId to entry.chatId] = entry
    }

    override fun remove(
        communityId: String,
        chatId: String,
    ) {
        entries.remove(communityId to chatId)
    }

    override fun clear() = entries.clear()
}
