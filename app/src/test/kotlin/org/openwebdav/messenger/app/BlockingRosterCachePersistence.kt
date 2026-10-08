package org.openwebdav.messenger.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

internal class BlockingRosterCachePersistence : VerifiedRosterCachePersistence {
    val writeStarted = CountDownLatch(1)
    val releaseWrite = CountDownLatch(1)
    private val firstWrite = AtomicBoolean(true)
    private val entries = mutableMapOf<Pair<String, String>, CachedVerifiedRoster>()

    override fun load(
        communityId: String,
        chatId: String,
    ) = entries[communityId to chatId]

    override fun put(entry: CachedVerifiedRoster) {
        if (firstWrite.compareAndSet(true, false)) {
            writeStarted.countDown()
            check(releaseWrite.await(5, SECONDS))
        }
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
