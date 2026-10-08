package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestCommunityWriteCoordinatorTest {
    @Test
    fun queued_stale_value_is_skipped_and_latest_value_wins() =
        runTest {
            val coordinator = LatestCommunityWriteCoordinator()
            val stale = coordinator.submit("community-a")
            val latest = coordinator.submit("community-a")
            val writes = mutableListOf<Int>()

            assertFalse(coordinator.runIfLatest("community-a", stale) { writes += 1 })
            assertTrue(coordinator.runIfLatest("community-a", latest) { writes += 2 })
            assertEquals(listOf(2), writes)
        }

    @Test
    fun in_flight_write_finishes_before_its_newer_successor() =
        runTest {
            val coordinator = LatestCommunityWriteCoordinator()
            val first = coordinator.submit("community-b")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val firstJob =
                async {
                    coordinator.runIfLatest("community-b", first) {
                        entered.complete(Unit)
                        release.await()
                        writes += 1
                    }
                }
            entered.await()
            val second = coordinator.submit("community-b")
            val secondJob =
                async {
                    coordinator.runIfLatest("community-b", second) { writes += 2 }
                }
            release.complete(Unit)
            firstJob.await()
            secondJob.await()
            assertEquals(listOf(1, 2), writes)
        }
}
