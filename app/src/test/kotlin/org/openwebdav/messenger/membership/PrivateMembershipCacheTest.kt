package org.openwebdav.messenger.membership

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.app.RosterCommitCoordinator
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport

class PrivateMembershipCacheTest {
    @Test
    fun cache_is_context_bound_and_stale_generation_cannot_commit() {
        val identity = IdentityTestSupport.identityCrypto().generateIdentity()
        val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 3 })
        val store = MemoryPersistence()
        val provenance = PrivateMembershipCacheProvenance(DirectoryTestSupport.native())
        val cache = PrivateMembershipCache(store, provenance, RosterCommitCoordinator())
        val lookup = cache.lookup("community-a", "chat-a", "private", key, identity)
        val record = PrivateMembershipCacheRecord("community-a", "chat-a", lookup.provenance, emptyList())
        assertTrue(cache.commit(lookup, record, { true }) {})
        assertNotNull(cache.lookup("community-a", "chat-a", "private", key, identity).record)

        val otherKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 4 })
        assertNull(cache.lookup("community-a", "chat-a", "private", otherKey, identity).record)
        val stale = cache.lookup("community-a", "chat-a", "private", key, identity)
        cache.invalidate("community-a", "chat-a")
        assertFalse(cache.commit(stale, record, { true }) {})
        assertTrue(store.records.isEmpty())
    }

    private class MemoryPersistence : PrivateMembershipCachePersistence {
        var records = emptyList<PrivateMembershipCacheRecord>()

        override fun loadAll() = records

        override fun replaceAll(records: List<PrivateMembershipCacheRecord>) {
            this.records = records
        }

        override fun clear() {
            records = emptyList()
        }
    }
}
