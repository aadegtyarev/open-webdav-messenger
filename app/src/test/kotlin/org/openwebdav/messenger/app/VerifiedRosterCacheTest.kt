package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryEntry

class VerifiedRosterCacheTest {
    private val persistence = MemoryPersistence()
    private val cache = VerifiedRosterCache(persistence)
    private val identity = AppTestSupport.newIdentity()
    private val communityKey = ChatKey.fromBytes(ByteArray(32) { 1 })
    private val chatKey = ChatKey.fromBytes(ByteArray(32) { 2 })
    private val entry = DirectoryEntry("Peer", ByteArray(32) { 3 }, ByteArray(32) { 4 })

    @Test
    fun cache_isolated_by_pair_and_provenance_and_restart_keeps_valid_entry() {
        val stored = roster("community", "chat")
        val token = cache.generation()
        assertTrue(cache.commit(token, stored) { true })
        val restarted = VerifiedRosterCache(persistence)
        assertEquals("Peer", restarted.load("community", "chat", communityKey, chatKey, identity)!!.entries.single().displayName)
        assertNull(restarted.load("other", "chat", communityKey, chatKey, identity))
        assertNull(restarted.load("community", "chat", communityKey, ChatKey.fromBytes(ByteArray(32) { 8 }), identity))
        assertNull(restarted.load("community", "chat", communityKey, chatKey, AppTestSupport.newIdentity()))
        assertNull(persistence.load("community", "chat"))
    }

    @Test
    fun cache_write_failure_keeps_verified_runtime_update_but_removes_old_entry() {
        persistence.put(roster("community", "chat"))
        persistence.failWrites = true
        var applied = false
        assertTrue(
            cache.commit(cache.generation(), roster("community", "chat")) {
                applied = true
                true
            },
        )
        assertTrue(applied)
        assertNull(persistence.load("community", "chat"))
    }

    @Test
    fun result_after_invalidation_cannot_repopulate_cache() {
        val token = cache.generation()
        cache.invalidate("community", "chat")
        assertFalse(cache.commit(token, roster("community", "chat")) { true })
        assertNull(persistence.load("community", "chat"))
    }

    private fun roster(
        community: String,
        chat: String,
    ) = CachedVerifiedRoster(
        community,
        chat,
        RosterCacheProvenance.digest(community, chat, communityKey, chatKey, identity),
        listOf(entry),
    )

    private class MemoryPersistence : VerifiedRosterCachePersistence {
        private val records = mutableMapOf<Pair<String, String>, CachedVerifiedRoster>()
        var failWrites = false

        override fun load(
            communityId: String,
            chatId: String,
        ) = records[communityId to chatId]

        override fun put(entry: CachedVerifiedRoster) {
            if (failWrites) error("disk full")
            records[entry.communityId to entry.chatId] = entry
        }

        override fun remove(
            communityId: String,
            chatId: String,
        ) {
            records.remove(communityId to chatId)
        }

        override fun clear() = records.clear()
    }
}
