package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryEntry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VerifiedRosterCacheApplyTest {
    @Test
    fun cache_lookup_invalidated_before_apply_never_applies_cached_ready() {
        val persistence = RosterCacheMemoryPersistence()
        val cache = VerifiedRosterCache(persistence)
        val identity = AppTestSupport.newIdentity()
        val key = ChatKey.fromBytes(ByteArray(32) { 1 })
        val entry = DirectoryEntry("Cached", ByteArray(32) { 2 }, ByteArray(32) { 3 })
        val provenance = RosterCacheProvenance.digest("community", "chat", key, key, identity)
        persistence.put(CachedVerifiedRoster("community", "chat", provenance, listOf(entry)))
        val lookup = cache.lookup("community", "chat", key, key, identity)
        val cached = checkNotNull(lookup.roster)
        val atApply = CountDownLatch(1)
        val continueApply = CountDownLatch(1)
        var readiness: RecipientReadiness = RecipientReadiness.Loading
        var recipients = emptyList<String>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                executor.submit<Boolean> {
                    atApply.countDown()
                    check(continueApply.await(5, TimeUnit.SECONDS))
                    cache.applyCached(lookup, { true }) {
                        recipients = lookup.roster!!.entries.map { it.displayName }
                        readiness = RecipientReadiness.Ready(recipients)
                        true
                    }
                }
            assertTrue(atApply.await(5, TimeUnit.SECONDS))
            cache.invalidateAll()
            continueApply.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertEquals(RecipientReadiness.Loading, readiness)
            assertTrue(recipients.isEmpty())
            assertNull(persistence.load("community", "chat"))
            var currentContext = false
            var remoteApplied = false
            val applyRemote = {
                remoteApplied = true
                true
            }
            assertFalse(cache.commit(cache.generation(), cached, { currentContext }, applyRemote))
            assertFalse(remoteApplied)
            currentContext = true
            assertTrue(cache.commit(cache.generation(), cached, { currentContext }, applyRemote))
            assertTrue(remoteApplied)
        } finally {
            continueApply.countDown()
            executor.shutdownNow()
        }
    }
}
