package org.openwebdav.messenger.export

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.keystore.AccountBackupStore
import org.openwebdav.messenger.keystore.FakeAccountBackupConfigStore
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductionAdapterLegacyRestoreTest {
    @Test
    fun legacy_restore_on_empty_target_activates_production_account_adapter_with_anchor() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val targetConfig = FakeAccountBackupConfigStore()
            val accountStore = AccountBackupStore(context, targetConfig)
            accountStore.clear()
            try {
                val native = ExportTestSupport.native()
                val sourceConfig = ExportTestSupport.inMemoryConnectionConfigStore().also { it.store(ExportTestSupport.sampleConfig()) }
                val sourceCommunityKeys =
                    ExportTestSupport.inMemoryCommunityKeyStore().also {
                        it.store(CryptoTestSupport.fixedKey(seed = 7))
                    }
                val sourceChatKeys =
                    ExportTestSupport.inMemoryChatKeyStore().also {
                        it.store("legacy-chat", CryptoTestSupport.fixedKey(seed = 8))
                    }
                val sourceIdentity = ExportTestSupport.inMemoryIdentityStore().also { it.store(ExportTestSupport.freshIdentity()) }
                val blob =
                    (
                        ExportManager(native, sourceConfig, sourceCommunityKeys, sourceChatKeys, sourceIdentity)
                            .export("test-password-123".toCharArray()) as ExportResult.Ready
                    ).blob
                val targetCommunityKeys = ExportTestSupport.inMemoryCommunityKeyStore()
                val targetChatKeys = ExportTestSupport.inMemoryChatKeyStore()
                val targetIdentity = ExportTestSupport.inMemoryIdentityStore()

                val result =
                    RestoreManager(
                        native,
                        targetConfig,
                        targetCommunityKeys,
                        targetChatKeys,
                        targetIdentity,
                        accountBackupStore = accountStore,
                    ).restore(blob, "test-password-123".toCharArray())

                assertEquals(RestoreResult.Restored, result)
                assertEquals("legacy-chat", targetConfig.loadStored()?.chatId)
                assertEquals("legacy-chat", accountStore.snapshot()?.communities?.single()?.anchorChatId)
            } finally {
                accountStore.clear()
            }
        }
}
