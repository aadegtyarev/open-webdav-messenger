package org.openwebdav.messenger.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityLoadResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RestoreRollbackCancellationTest {
    @Test
    fun cancellation_while_rollback_waits_still_restores_stores_and_runtime() =
        runTest {
            val native = ExportTestSupport.native()
            val oldIdentity = ExportTestSupport.freshIdentity()
            val newIdentity = ExportTestSupport.freshIdentity()
            val oldKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 11 })
            val newKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 22 })
            val oldBackup = restoreTestBackup("old-community", "old-anchor")
            val newBackup = restoreTestBackup("new-community", "new-anchor")
            val payload = ExportPayload.build(null, null, mapOf("new-anchor" to newKey), newIdentity, newBackup)
            val blob = encryptedExportTestPayload(native, ExportPayload.toJson(payload), "restore-password")
            val configStore = ExportTestSupport.inMemoryConnectionConfigStore().also { it.store(oldBackup.communities.single().config) }
            val communityStore = ExportTestSupport.inMemoryCommunityKeyStore().also { it.store("old-community", oldKey) }
            val chatStore = ExportTestSupport.inMemoryChatKeyStore().also { it.store("old-anchor", oldKey) }
            val identityStore = ExportTestSupport.inMemoryIdentityStore().also { it.store(oldIdentity) }
            val rollbackStored = CompletableDeferred<Unit>()
            val accountStore =
                ExportTestSupport.InMemoryAccountBackupStore(oldBackup) {
                    if (it == oldBackup) rollbackStored.complete(Unit)
                }
            val activationEntered = CountDownLatch(1)
            val activationRelease = CountDownLatch(1)
            val stableEntered = CompletableDeferred<Unit>()
            val stableRelease = CompletableDeferred<Unit>()
            val runtimeRestored = CompletableDeferred<Unit>()
            val barrier = AccountMutationBarrier.process
            val generation = barrier.replacementGeneration()
            var activationGeneration = -1L
            var rollbackGeneration = -1L
            var postCleanupGateAvailable = false
            val restoring =
                async(start = CoroutineStart.UNDISPATCHED) {
                    RestoreManager(
                        native, configStore, communityStore, chatStore, identityStore,
                        accountBackupStore = accountStore,
                        activateRuntime = {
                            activationGeneration = barrier.replacementGeneration()
                            activationEntered.countDown()
                            check(activationRelease.await(5, TimeUnit.SECONDS))
                            error("runtime activation failed")
                        },
                        restorePreviousRuntime = {
                            rollbackGeneration = barrier.replacementGeneration()
                            runtimeRestored.complete(Unit)
                        },
                        afterRuntimeRestored = { barrier.withStableAccount { postCleanupGateAvailable = true } },
                    ).restore(blob, "restore-password".toCharArray())
                }
            assertTrue(activationEntered.await(5, TimeUnit.SECONDS))
            val stableOpen =
                async(start = CoroutineStart.UNDISPATCHED) {
                    barrier.withStableAccount {
                        stableEntered.complete(Unit)
                        stableRelease.await()
                    }
                }
            activationRelease.countDown()
            stableEntered.await()
            restoring.cancel()
            yield()
            assertFalse("rollback must remain active while the stable-account holder owns the gate", restoring.isCompleted)

            stableRelease.complete(Unit)
            rollbackStored.await()
            runtimeRestored.await()
            stableOpen.await()
            assertTrue(runCatching { restoring.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(oldBackup, accountStore.snapshot())
            assertEquals(setOf("old-community"), communityStore.listCommunityIds())
            assertTrue(oldKey.export().contentEquals(chatStore.load("old-anchor")?.export()))
            assertTrue(
                oldIdentity.copySignPublic().contentEquals((identityStore.load() as IdentityLoadResult.Loaded).identity.copySignPublic()),
            )
            assertEquals(generation + 1, activationGeneration)
            assertEquals(generation + 2, rollbackGeneration)
            assertTrue(postCleanupGateAvailable)
        }
}
