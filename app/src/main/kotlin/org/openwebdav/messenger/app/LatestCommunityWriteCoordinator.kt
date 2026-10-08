package org.openwebdav.messenger.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Serializes writes per community and suppresses queued requests superseded by a newer value. */
internal class LatestCommunityWriteCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val revisions = ConcurrentHashMap<String, AtomicLong>()

    fun submit(communityId: String): Long = revisions.computeIfAbsent(communityId) { AtomicLong() }.incrementAndGet()

    fun isLatest(
        communityId: String,
        revision: Long,
    ): Boolean = revisions[communityId]?.get() == revision

    suspend fun runIfLatest(
        communityId: String,
        revision: Long,
        write: suspend () -> Unit,
    ): Boolean =
        locks.computeIfAbsent(communityId) { Mutex() }.withLock {
            if (!isLatest(communityId, revision)) return@withLock false
            write()
            true
        }
}
