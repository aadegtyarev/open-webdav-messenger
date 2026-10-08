package org.openwebdav.messenger.export

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.crypto.ChatKey

class RestoreActivationFailureTest {
    @Test
    fun activation_failure_returns_store_failure_after_restoring_previous_account_and_runtime() =
        runTest {
            val native = ExportTestSupport.native()
            val identity = ExportTestSupport.freshIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 17 })
            val previous = restoreTestBackup("previous-community", "previous-anchor")
            val replacement = restoreTestBackup("replacement-community", "replacement-anchor")
            val payload = ExportPayload.build(null, null, mapOf("replacement-anchor" to key), identity, replacement)
            val blob = encryptedExportTestPayload(native, ExportPayload.toJson(payload), "restore-password")
            val account = ExportTestSupport.InMemoryAccountBackupStore(previous)
            val barrier = AccountMutationBarrier.process
            val generation = barrier.replacementGeneration()
            var runtimeRestored = false
            val result =
                RestoreManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    ExportTestSupport.inMemoryChatKeyStore(),
                    ExportTestSupport.inMemoryIdentityStore(),
                    accountBackupStore = account,
                    activateRuntime = { error("controlled activation failure") },
                    restorePreviousRuntime = {
                        assertEquals(generation + 2, barrier.replacementGeneration())
                        runtimeRestored = true
                    },
                    afterRuntimeRestored = { barrier.withStableAccount { } },
                ).restore(blob, "restore-password".toCharArray())

            assertEquals(RestoreResult.StoreFailure(rollbackSucceeded = true), result)
            assertEquals(previous, account.snapshot())
            assertTrue(runtimeRestored)
        }
}
