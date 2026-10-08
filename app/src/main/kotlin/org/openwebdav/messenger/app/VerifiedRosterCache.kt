package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity

internal class VerifiedRosterCache(
    private val store: VerifiedRosterCachePersistence,
    private val commitCoordinator: RosterCommitCoordinator = RosterCommitCoordinator(),
) {
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
        isCurrent: () -> Boolean = { true },
        apply: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            commitCoordinator.serialized {
                if (token != generation || !isCurrent()) return@serialized false
                val previous = runCatching { store.load(roster.communityId, roster.chatId) }.getOrNull()
                if (!isCurrent()) return@serialized false
                val persisted = runCatching { store.put(roster) }.isSuccess
                val applied =
                    try {
                        apply()
                    } catch (failure: Throwable) {
                        if (persisted) restorePrevious(roster, previous)
                        throw failure
                    }
                if (!applied && persisted) restorePrevious(roster, previous)
                applied
            }
        }

    private fun restorePrevious(
        roster: CachedVerifiedRoster,
        previous: CachedVerifiedRoster?,
    ) {
        runCatching {
            if (previous == null) store.remove(roster.communityId, roster.chatId) else store.put(previous)
        }
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
