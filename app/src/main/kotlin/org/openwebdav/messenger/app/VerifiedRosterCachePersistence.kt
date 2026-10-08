package org.openwebdav.messenger.app

internal interface VerifiedRosterCachePersistence {
    fun load(
        communityId: String,
        chatId: String,
    ): CachedVerifiedRoster?

    fun put(entry: CachedVerifiedRoster)

    fun remove(
        communityId: String,
        chatId: String,
    )

    fun clear()
}

internal object EmptyRosterCachePersistence : VerifiedRosterCachePersistence {
    override fun load(
        communityId: String,
        chatId: String,
    ): CachedVerifiedRoster? = null

    override fun put(entry: CachedVerifiedRoster) = Unit

    override fun remove(
        communityId: String,
        chatId: String,
    ) = Unit

    override fun clear() = Unit
}
