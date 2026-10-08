package org.openwebdav.messenger.membership

import org.openwebdav.messenger.app.RosterCommitCoordinator
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity

/** Exact account/chat/key/identity/kind cache with generation-fenced apply and encrypted persistence. */
internal class PrivateMembershipCache(
    private val store: PrivateMembershipCachePersistence,
    private val provenance: PrivateMembershipCacheProvenance,
    private val coordinator: RosterCommitCoordinator,
) {
    data class Lookup(val generation: Long, val provenance: ByteArray, val record: PrivateMembershipCacheRecord?)

    private val lock = Any()
    private var generation = 0L

    fun lookup(
        communityId: String,
        chatId: String,
        kind: String,
        key: ChatKey,
        identity: Identity,
    ): Lookup =
        synchronized(lock) {
            val expected = provenance.digest(communityId, chatId, kind, key, identity)
            val record =
                runCatching { store.loadAll() }.getOrNull().orEmpty().firstOrNull {
                    it.communityId == communityId && it.chatId == chatId && it.provenance.contentEquals(expected)
                }
            Lookup(generation, expected, record)
        }

    fun applyCached(
        lookup: Lookup,
        isCurrent: () -> Boolean,
        apply: () -> Unit,
    ): Boolean =
        coordinator.serialized {
            synchronized(lock) {
                if (lookup.record == null || generation != lookup.generation || !isCurrent()) return@synchronized false
                apply()
                true
            }
        }

    fun applyLive(
        lookup: Lookup,
        isCurrent: () -> Boolean,
        apply: () -> Unit,
    ): Boolean =
        coordinator.serialized {
            synchronized(lock) {
                if (generation != lookup.generation || !isCurrent()) return@synchronized false
                apply()
                true
            }
        }

    fun commit(
        lookup: Lookup,
        record: PrivateMembershipCacheRecord,
        isCurrent: () -> Boolean,
        apply: () -> Unit,
    ): Boolean =
        coordinator.serialized {
            synchronized(lock) {
                if (generation != lookup.generation || !isCurrent()) return@synchronized false
                val all = store.loadAll().orEmpty().filterNot { it.communityId == record.communityId && it.chatId == record.chatId }
                store.replaceAll(all + record)
                if (!isCurrent() || generation != lookup.generation) return@synchronized false
                apply()
                true
            }
        }

    fun invalidate(
        communityId: String,
        chatId: String,
    ) = synchronized(lock) {
        generation++
        store.replaceAll(store.loadAll().orEmpty().filterNot { it.communityId == communityId && it.chatId == chatId })
    }

    fun invalidateAll() =
        synchronized(lock) {
            generation++
            store.clear()
        }
}
