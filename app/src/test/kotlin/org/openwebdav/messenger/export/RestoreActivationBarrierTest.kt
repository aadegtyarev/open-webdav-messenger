package org.openwebdav.messenger.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RestoreActivationBarrierTest {
    @Test
    fun stable_account_open_waits_until_restored_runtime_activation_commits() =
        runTest {
            val native = ExportTestSupport.native()
            val identity: Identity = ExportTestSupport.freshIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 73 })
            val backup = restoreTestBackup("restored-community", "restored-anchor")
            val payload = ExportPayload.build(null, null, mapOf("restored-anchor" to key), identity, backup)
            val blob = encryptedExportTestPayload(native, ExportPayload.toJson(payload), "restore-password")
            val account = ExportTestSupport.InMemoryAccountBackupStore()
            val barrier = AccountMutationBarrier.process
            val generation = barrier.replacementGeneration()
            val activationEntered = CountDownLatch(1)
            val activationRelease = CountDownLatch(1)
            val stableEntered = CompletableDeferred<Unit>()
            var runtimeInstalled = false
            var generationAtActivation = -1L
            var generationAtOpen = -1L
            var openSawInstalledRuntime = false
            val restored =
                async(start = CoroutineStart.UNDISPATCHED) {
                    RestoreManager(
                        native,
                        ExportTestSupport.inMemoryConnectionConfigStore(),
                        ExportTestSupport.inMemoryCommunityKeyStore(),
                        ExportTestSupport.inMemoryChatKeyStore(),
                        ExportTestSupport.inMemoryIdentityStore(),
                        accountBackupStore = account,
                        activateRuntime = {
                            generationAtActivation = barrier.replacementGeneration()
                            activationEntered.countDown()
                            check(activationRelease.await(5, TimeUnit.SECONDS))
                            runtimeInstalled = true
                        },
                        afterRuntimeActivated = { barrier.withStableAccount { } },
                    ).restore(blob, "restore-password".toCharArray())
                }
            assertTrue(activationEntered.await(5, TimeUnit.SECONDS))
            val open =
                async(start = CoroutineStart.UNDISPATCHED) {
                    barrier.withStableAccount {
                        generationAtOpen = barrier.replacementGeneration()
                        openSawInstalledRuntime = runtimeInstalled && account.snapshot()?.activeCommunityId == "restored-community"
                        stableEntered.complete(Unit)
                    }
                }
            assertFalse("stable open must remain gated during runtime installation", stableEntered.isCompleted)
            activationRelease.countDown()
            stableEntered.await()
            open.await()
            assertEquals(RestoreResult.Restored, restored.await())
            assertEquals(generation + 1, generationAtActivation)
            assertEquals(generation + 1, generationAtOpen)
            assertTrue(openSawInstalledRuntime)
        }
}
