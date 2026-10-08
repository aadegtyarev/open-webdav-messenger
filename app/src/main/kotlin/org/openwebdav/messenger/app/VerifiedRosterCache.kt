package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity

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

internal class VerifiedRosterCache(private val store: VerifiedRosterCachePersistence) {
    data class Lookup(val roster: CachedVerifiedRoster?, val generation: Long)

    private val lock = Any()
    private var generation = 0L

    fun generation(): Long = synchronized(lock) { generation }

    fun lookup(
        communityId: String,
        chatId: String,
        communityKey: ChatKey,
        chatKey: ChatKey,
        identity: Identity,
    ): Lookup =
        synchronized(lock) {
            val provenance = RosterCacheProvenance.digest(communityId, chatId, communityKey, chatKey, identity)
            val cached = store.load(communityId, chatId)
            val accepted = cached?.takeIf { it.provenance.contentEquals(provenance) }
            if (cached != null && accepted == null) {
                generation++
                runCatching { store.remove(communityId, chatId) }
            }
            Lookup(accepted, generation)
        }

    fun load(
        communityId: String,
        chatId: String,
        communityKey: ChatKey,
        chatKey: ChatKey,
        identity: Identity,
    ): CachedVerifiedRoster? = lookup(communityId, chatId, communityKey, chatKey, identity).roster

    fun commit(
        token: Long,
        roster: CachedVerifiedRoster,
        apply: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            if (token != generation) return@synchronized false
            runCatching { store.put(roster) }.onFailure {
                runCatching { store.remove(roster.communityId, roster.chatId) }
            }
            apply()
        }

    fun invalidate(
        communityId: String,
        chatId: String,
    ) = synchronized(lock) {
        generation++
        store.remove(communityId, chatId)
    }

    fun invalidateAll() =
        synchronized(lock) {
            generation++
            store.clear()
        }
}
