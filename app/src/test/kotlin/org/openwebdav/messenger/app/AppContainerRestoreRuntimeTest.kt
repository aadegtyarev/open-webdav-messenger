package org.openwebdav.messenger.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.directory.DirectoryFakeDisk
import org.openwebdav.messenger.export.RestoreResult
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppContainerRestoreRuntimeTest {
    @Test
    fun restore_blocks_foreground_open_and_publishes_only_the_new_identity_claim() =
        runTest {
            val server =
                MockWebServer().apply {
                    dispatcher = DirectoryFakeDisk("chat-root")
                    start()
                }
            val fixture = AppContainerRestoreFixture(server)
            val enteredActivation = CountDownLatch(1)
            val releaseActivation = CountDownLatch(1)
            val context = RuntimeEnvironment.getApplication()
            val publication = RestoredPrivateClaimPublication(context, fixture.native)
            try {
                val oldGraph = checkNotNull(EngineWiring.current())
                val restoring =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.restoreManager(
                            activateRuntime = {
                                enteredActivation.countDown()
                                check(releaseActivation.await(5, TimeUnit.SECONDS))
                                fixture.installCurrentAccount()
                            },
                            afterRuntimeActivated = {
                                publication.publish(
                                    checkNotNull(EngineWiring.current()),
                                    fixture.communityId,
                                    fixture.chatId,
                                )
                            },
                        ).restore(fixture.restoreBlob(), "restore-password".toCharArray())
                    }
                assertTrue(enteredActivation.await(5, TimeUnit.SECONDS))
                val storedIdentity = (fixture.identityStore.load() as IdentityLoadResult.Loaded).identity
                assertTrue(fixture.newIdentity.copySignPublic().contentEquals(storedIdentity.copySignPublic()))
                assertTrue(fixture.newKey.export().contentEquals(fixture.chatKeyStore.load(fixture.chatId)?.export()))
                assertEquals(fixture.newConfig, fixture.accountStore.snapshot()?.communities?.single()?.config)
                assertSame(oldGraph, EngineWiring.current())
                val open = async(start = CoroutineStart.UNDISPATCHED) { AppContainer.switchToCommunity(fixture.communityId) }
                assertFalse("foreground open must wait while stores and runtime are being replaced", open.isCompleted)
                releaseActivation.countDown()
                assertEquals(RestoreResult.Restored, restoring.await())
                assertFalse(open.await())
                val graph = checkNotNull(EngineWiring.current())
                assertNotSame(oldGraph, graph)
                assertEquals(fixture.communityId, graph.communityId)
                assertEquals(fixture.chatId, graph.chatId)
                assertTrue(graph.chatKey.export().contentEquals(fixture.newKey.export()))
                assertTrue(graph.identity.copySignPublic().contentEquals(fixture.newIdentity.copySignPublic()))
                assertEquals(0, publication.previousClaimCount)
                assertEquals(org.openwebdav.messenger.membership.PrivateClaimPublicationStatus.UPLOADED, publication.status)
                assertTrue(publication.firstSigner!!.contentEquals(fixture.newIdentity.copySignPublic()))
                assertFalse(publication.firstSigner!!.contentEquals(fixture.oldIdentity.copySignPublic()))
            } finally {
                releaseActivation.countDown()
                publication.clear()
                fixture.close()
            }
        }
}
