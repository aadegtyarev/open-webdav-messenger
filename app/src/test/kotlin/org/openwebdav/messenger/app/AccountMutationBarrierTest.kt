package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier

class AccountMutationBarrierTest {
    @Test
    fun account_replacement_generation_invalidates_prior_snapshots() =
        runTest {
            val barrier = AccountMutationBarrier()
            val captured = barrier.replacementGeneration()
            barrier.withStableAccount { }
            assertEquals(captured, barrier.replacementGeneration())
            barrier.withAccountReplacement { }
            assertTrue(barrier.replacementGeneration() > captured)
        }

    @Test
    fun local_open_is_not_blocked_by_poll_but_does_not_overlap_account_replacement() =
        runTest {
            val barrier = AccountMutationBarrier()
            val pollStarted = CompletableDeferred<Unit>()
            val pollRelease = CompletableDeferred<Unit>()
            val openStarted = CompletableDeferred<Unit>()
            val openRelease = CompletableDeferred<Unit>()
            val replacementStarted = CompletableDeferred<Unit>()
            val poll =
                launch {
                    barrier.withExclusive {
                        pollStarted.complete(Unit)
                        pollRelease.await()
                    }
                }
            pollStarted.await()
            val open =
                launch {
                    barrier.withStableAccount {
                        openStarted.complete(Unit)
                        openRelease.await()
                    }
                }
            yield()
            assertTrue(openStarted.isCompleted)
            val replacement =
                launch {
                    barrier.withAccountReplacement { replacementStarted.complete(Unit) }
                }
            yield()
            assertFalse(replacementStarted.isCompleted)
            openRelease.complete(Unit)
            replacementStarted.await()
            pollRelease.complete(Unit)
            poll.join()
            open.join()
            replacement.join()
        }

    @Test
    fun restore_waits_for_in_flight_rotation() =
        runTest {
            val barrier = AccountMutationBarrier.process
            val rotationStarted = CompletableDeferred<Unit>()
            val rotationRelease = CompletableDeferred<Unit>()
            val restoreStarted = CompletableDeferred<Unit>()
            val rotation =
                launch {
                    barrier.withExclusive {
                        rotationStarted.complete(Unit)
                        rotationRelease.await()
                    }
                }
            rotationStarted.await()
            val restore = launch { barrier.withExclusive { restoreStarted.complete(Unit) } }
            yield()
            assertFalse(restoreStarted.isCompleted)
            rotationRelease.complete(Unit)
            restoreStarted.await()
            rotation.join()
            restore.join()
        }

    @Test
    fun restore_drains_in_flight_poll_and_holds_rotation_until_release() =
        runTest {
            val barrier = AccountMutationBarrier.process
            val pollStarted = CompletableDeferred<Unit>()
            val pollRelease = CompletableDeferred<Unit>()
            val restoreStarted = CompletableDeferred<Unit>()
            val restoreRelease = CompletableDeferred<Unit>()
            var rotationStarted = false
            val poll =
                launch {
                    barrier.withExclusive {
                        pollStarted.complete(Unit)
                        pollRelease.await()
                    }
                }
            pollStarted.await()
            val restore =
                launch {
                    barrier.withExclusive {
                        restoreStarted.complete(Unit)
                        restoreRelease.await()
                    }
                }
            yield()
            val rotation = launch { barrier.withExclusive { rotationStarted = true } }
            yield()
            assertFalse(restoreStarted.isCompleted)
            assertFalse(rotationStarted)
            pollRelease.complete(Unit)
            restoreStarted.await()
            assertFalse(rotationStarted)
            restoreRelease.complete(Unit)
            poll.join()
            restore.join()
            rotation.join()
            assertTrue(rotationStarted)
        }
}
