package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSelectionGuardTest {
    @Test
    fun stale_async_open_cannot_install_after_a_newer_community_open() =
        runTest {
            val guard = RuntimeSelectionGuard()
            val aStarted = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            var installedCommunity: String? = null

            val openA =
                async {
                    val revision = guard.begin()
                    aStarted.complete(Unit)
                    releaseA.await()
                    if (guard.isCurrent(revision)) installedCommunity = "community-a"
                }
            aStarted.await()

            val revisionB = guard.begin()
            if (guard.isCurrent(revisionB)) installedCommunity = "community-b"
            releaseA.complete(Unit)
            openA.await()

            assertEquals("community-b", installedCommunity)
        }
}
