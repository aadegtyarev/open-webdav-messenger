package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.sync.FakeDisk
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppContainerRestoreRollbackTest {
    @Test
    fun cancellation_while_real_runtime_rollback_waits_restores_previous_graph_and_stores() =
        runTest {
            val server =
                MockWebServer().apply {
                    dispatcher = FakeDisk()
                    start()
                }
            val fixture = AppContainerRestoreFixture(server)
            val barrier = AccountMutationBarrier.process
            val generation = barrier.replacementGeneration()
            val activationEntered = CountDownLatch(1)
            val releaseActivation = CountDownLatch(1)
            val stableEntered = CompletableDeferred<Unit>()
            val stableRelease = CompletableDeferred<Unit>()
            try {
                val oldGraph = checkNotNull(EngineWiring.current())
                val restoring =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.restoreManager(
                            activateRuntime = {
                                fixture.installCurrentAccount()
                                activationEntered.countDown()
                                check(releaseActivation.await(5, TimeUnit.SECONDS))
                                error("controlled runtime activation failure")
                            },
                        ).restore(fixture.restoreBlob(), "restore-password".toCharArray())
                    }
                assertTrue(activationEntered.await(5, TimeUnit.SECONDS))
                val intermediateGraph = checkNotNull(EngineWiring.current())
                assertTrue(intermediateGraph.identity.copySignPublic().contentEquals(fixture.newIdentity.copySignPublic()))
                assertTrue(intermediateGraph.chatKey.export().contentEquals(fixture.newKey.export()))
                assertEquals(fixture.newConfig, fixture.accountStore.snapshot()?.communities?.single()?.config)
                val stableHolder =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        barrier.withStableAccount {
                            stableEntered.complete(Unit)
                            stableRelease.await()
                        }
                    }
                releaseActivation.countDown()
                stableEntered.await()
                restoring.cancel()
                yield()
                assertFalse("rollback must wait for the stable-account holder", restoring.isCompleted)
                stableRelease.complete(Unit)
                stableHolder.await()
                assertTrue(runCatching { restoring.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertEquals(fixture.oldBackup, fixture.accountStore.snapshot())
                assertEquals(fixture.oldConfig, fixture.configStore.load())
                assertTrue(fixture.oldKey.export().contentEquals(fixture.chatKeyStore.load(fixture.chatId)?.export()))
                assertTrue(
                    fixture.oldIdentity.copySignPublic().contentEquals(
                        (fixture.identityStore.load() as IdentityLoadResult.Loaded).identity.copySignPublic(),
                    ),
                )
                val restoredGraph = checkNotNull(EngineWiring.current())
                assertTrue(restoredGraph.identity.copySignPublic().contentEquals(fixture.oldIdentity.copySignPublic()))
                assertTrue(restoredGraph.chatKey.export().contentEquals(fixture.oldKey.export()))
                assertEquals(generation + 2, barrier.replacementGeneration())
                assertEquals(oldGraph.chatId, restoredGraph.chatId)
            } finally {
                releaseActivation.countDown()
                fixture.close()
            }
        }
}
