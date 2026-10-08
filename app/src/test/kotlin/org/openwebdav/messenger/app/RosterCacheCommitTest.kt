package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.identity.Identity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class RosterCacheCommitTest {
    @Test
    fun stale_general_refresh_cannot_replace_a_newer_chat_roster() {
        val persistence = RosterCacheMemoryPersistence()
        val coordinator = RosterCommitCoordinator()
        val requests = ChatOpenRequestCoordinator(coordinator)
        val selection = RuntimeSelectionGuard(coordinator)
        val cache = VerifiedRosterCache(persistence, coordinator)
        val identity = AppTestSupport.newIdentity()
        val communityKey = ChatKey.fromBytes(ByteArray(32) { 1 })
        val chatKey = ChatKey.fromBytes(ByteArray(32) { 2 })
        val first = roster("G1", identity, communityKey, chatKey)
        val second = roster("G2", identity, communityKey, chatKey)
        val firstToken = requests.begin()
        val firstRevision = selection.current()
        val firstGeneration = cache.generation()
        var currentGraph = Any()
        val firstGraph = currentGraph
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstResult = AtomicReference<Boolean>()
        var runtimeRoster = first
        val staleRefresh =
            Thread {
                started.countDown()
                check(release.await(2, SECONDS))
                firstResult.set(
                    cache.commit(firstGeneration, first, {
                        requests.isCurrent(firstToken) && selection.isCurrent(firstRevision) && currentGraph === firstGraph
                    }) {
                        runtimeRoster = first
                        true
                    },
                )
            }
        staleRefresh.start()
        assertTrue(started.await(2, SECONDS))

        requests.begin()
        selection.begin()
        currentGraph = Any()
        val secondGraph = currentGraph
        val secondToken = requests.begin()
        val secondRevision = selection.begin()
        assertTrue(
            cache.commit(cache.generation(), second, {
                requests.isCurrent(secondToken) && selection.isCurrent(secondRevision) && currentGraph === secondGraph
            }) {
                runtimeRoster = second
                true
            },
        )
        val writesAfterG2 = persistence.writes
        release.countDown()
        staleRefresh.join(2_000)
        assertFalse(staleRefresh.isAlive)
        assertFalse(firstResult.get() == true)
        assertEquals(writesAfterG2, persistence.writes)
        assertEquals("G2", runtimeRoster.entries.single().displayName)
        assertEquals("G2", persistence.load("community", "chat")!!.entries.single().displayName)
        assertEquals("G2", cache.load("community", "chat", communityKey, chatKey, identity)!!.entries.single().displayName)
    }

    private fun roster(
        name: String,
        identity: Identity,
        communityKey: ChatKey,
        chatKey: ChatKey,
    ): CachedVerifiedRoster {
        val provenance = RosterCacheProvenance.digest("community", "chat", communityKey, chatKey, identity)
        return CachedVerifiedRoster("community", "chat", provenance, listOf(DirectoryEntry(name, ByteArray(32) { 3 }, ByteArray(32) { 4 })))
    }
}
