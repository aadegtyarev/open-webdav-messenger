package org.openwebdav.messenger.export

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityLoadResult
import java.util.Base64

/**
 * JVM unit tests for [RestoreManager] — restore-specific edge cases: no identity in payload,
 * empty chat keys, partial payload validation, store not overwritten on failure.
 */
class RestoreManagerTest {
    private val native = ExportTestSupport.native()

    private fun newRestoreManager(
        cc: ExportableConnectionConfigStore = ExportTestSupport.inMemoryConnectionConfigStore(),
        ck: ExportableCommunityKeyStore = ExportTestSupport.inMemoryCommunityKeyStore(),
        ch: ExportableChatKeyStore = ExportTestSupport.inMemoryChatKeyStore(),
        id: ExportableIdentityStore = ExportTestSupport.inMemoryIdentityStore(),
    ): RestoreManager = RestoreManager(native, cc, ck, ch, id)

    private fun newExportManager(
        cc: ExportableConnectionConfigStore = ExportTestSupport.inMemoryConnectionConfigStore(),
        ck: ExportableCommunityKeyStore = ExportTestSupport.inMemoryCommunityKeyStore(),
        ch: ExportableChatKeyStore = ExportTestSupport.inMemoryChatKeyStore(),
        id: ExportableIdentityStore = ExportTestSupport.inMemoryIdentityStore(),
    ): ExportManager = ExportManager(native, cc, ck, ch, id)

    /** Produce an export blob with the given stores populated. */
    private suspend fun exportBlob(
        cc: ExportableConnectionConfigStore =
            ExportTestSupport.inMemoryConnectionConfigStore().also {
                it.store(ExportTestSupport.sampleConfig())
            },
        ck: ExportableCommunityKeyStore =
            ExportTestSupport.inMemoryCommunityKeyStore().also {
                it.store(CryptoTestSupport.fixedKey(seed = 99))
            },
        ch: ExportableChatKeyStore =
            ExportTestSupport.inMemoryChatKeyStore().also {
                it.store("chat-a", CryptoTestSupport.fixedKey(seed = 10))
            },
        id: ExportableIdentityStore =
            ExportTestSupport.inMemoryIdentityStore().also {
                it.store(ExportTestSupport.freshIdentity())
            },
    ): String {
        val manager = newExportManager(cc, ck, ch, id)
        return (manager.export("test-password-123".toCharArray()) as ExportResult.Ready).blob
    }

    // -- full restore with all stores ----------------------------------------

    @Test
    fun full_restore_populates_all_stores() =
        runTest {
            val blob = exportBlob()
            val ccRestore = ExportTestSupport.inMemoryConnectionConfigStore()
            val ckRestore = ExportTestSupport.inMemoryCommunityKeyStore()
            val chRestore = ExportTestSupport.inMemoryChatKeyStore()
            val idRestore = ExportTestSupport.inMemoryIdentityStore()

            val result =
                newRestoreManager(ccRestore, ckRestore, chRestore, idRestore)
                    .restore(blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.Restored, result)
            assertTrue("connection config should be restored", ccRestore.load() != null)
            assertTrue("community key should be restored", ckRestore.load() != null)
            assertTrue("chat key should be restored", chRestore.load("chat-a") != null)
            assertTrue("identity should be restored", idRestore.load() is IdentityLoadResult.Loaded)
        }

    @Test
    fun encrypted_multi_community_backup_restores_configs_registries_and_active_selection() =
        runTest {
            val communities =
                listOf(
                    CommunityBackup(
                        "community-a",
                        "A",
                        "anchor-a",
                        ExportTestSupport.sampleConfig(),
                        listOf(ChatBackup("anchor-a", "General", "general")),
                        Base64.getEncoder().encodeToString(CryptoTestSupport.fixedKey(seed = 11).export()),
                    ),
                    CommunityBackup(
                        "community-b",
                        "B",
                        "anchor-b",
                        ExportTestSupport.sampleConfig().copy(chatRoot = "root-b"),
                        listOf(ChatBackup("anchor-b", "General", "general"), ChatBackup("group-b", "Group", "group")),
                        Base64.getEncoder().encodeToString(CryptoTestSupport.fixedKey(seed = 12).export()),
                    ),
                )
            val accountBackup = AccountBackup("community-b", communities)
            val sourceAccount = ExportTestSupport.InMemoryAccountBackupStore(accountBackup)
            val sourceChatKeys =
                ExportTestSupport.inMemoryChatKeyStore().also {
                    it.store("anchor-a", CryptoTestSupport.fixedKey(seed = 21))
                    it.store("anchor-b", CryptoTestSupport.fixedKey(seed = 22))
                    it.store("group-b", CryptoTestSupport.fixedKey(seed = 23))
                }
            val sourceIdentity = ExportTestSupport.inMemoryIdentityStore().also { it.store(ExportTestSupport.freshIdentity()) }
            val blob =
                ExportManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    sourceChatKeys,
                    sourceIdentity,
                    accountBackupStore = sourceAccount,
                ).export("test-password-123".toCharArray()) as ExportResult.Ready

            val targetAccount = ExportTestSupport.InMemoryAccountBackupStore()
            val targetCommunityKeys = ExportTestSupport.inMemoryCommunityKeyStore()
            val targetChatKeys = ExportTestSupport.inMemoryChatKeyStore()
            val result =
                RestoreManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    targetCommunityKeys,
                    targetChatKeys,
                    ExportTestSupport.inMemoryIdentityStore(),
                    accountBackupStore = targetAccount,
                ).restore(blob.blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.Restored, result)
            assertEquals(accountBackup, targetAccount.snapshot())
            assertTrue(CryptoTestSupport.fixedKey(seed = 11).export().contentEquals(targetCommunityKeys.load("community-a")?.export()))
            assertTrue(CryptoTestSupport.fixedKey(seed = 12).export().contentEquals(targetCommunityKeys.load("community-b")?.export()))
            assertTrue(CryptoTestSupport.fixedKey(seed = 21).export().contentEquals(targetChatKeys.load("anchor-a")?.export()))
            assertTrue(CryptoTestSupport.fixedKey(seed = 22).export().contentEquals(targetChatKeys.load("anchor-b")?.export()))
            assertTrue(CryptoTestSupport.fixedKey(seed = 23).export().contentEquals(targetChatKeys.load("group-b")?.export()))
        }

    @Test
    fun registry_write_failure_rolls_back_multi_community_restore() =
        runTest {
            val newBackup =
                AccountBackup(
                    "community-new",
                    listOf(
                        CommunityBackup(
                            "community-new",
                            "New",
                            "anchor-new",
                            ExportTestSupport.sampleConfig(),
                            listOf(ChatBackup("anchor-new", "General", "general")),
                        ),
                    ),
                )
            val sourceAccount = ExportTestSupport.InMemoryAccountBackupStore(newBackup)
            val sourceIdentity = ExportTestSupport.inMemoryIdentityStore().also { it.store(ExportTestSupport.freshIdentity()) }
            val blob =
                ExportManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    ExportTestSupport.inMemoryChatKeyStore(),
                    sourceIdentity,
                    accountBackupStore = sourceAccount,
                ).export("test-password-123".toCharArray()) as ExportResult.Ready
            val oldBackup =
                AccountBackup(
                    "community-old",
                    listOf(
                        CommunityBackup(
                            "community-old",
                            "Old",
                            "anchor-old",
                            ExportTestSupport.sampleConfig(),
                            listOf(ChatBackup("anchor-old", "General", "general")),
                        ),
                    ),
                )
            val backingStore = ExportTestSupport.InMemoryAccountBackupStore(oldBackup)
            var failOnce = true
            val failingStore =
                object : ExportableAccountBackupStore by backingStore {
                    override fun replace(backup: AccountBackup) {
                        if (failOnce) {
                            failOnce = false
                            error("injected registry write failure")
                        }
                        backingStore.replace(backup)
                    }
                }
            val oldKey = CryptoTestSupport.fixedKey(seed = 31)
            val communityKeys = ExportTestSupport.inMemoryCommunityKeyStore().also { it.store("community-old", oldKey) }
            val result =
                RestoreManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    communityKeys,
                    ExportTestSupport.inMemoryChatKeyStore(),
                    ExportTestSupport.inMemoryIdentityStore(),
                    accountBackupStore = failingStore,
                ).restore(blob.blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.StoreFailure(rollbackSucceeded = true), result)
            assertEquals(oldBackup, backingStore.snapshot())
            assertTrue(oldKey.export().contentEquals(communityKeys.load("community-old")?.export()))
            assertEquals(setOf("community-old"), communityKeys.listCommunityIds())
        }

    // -- restore does not partially populate on failure -----------------------

    @Test
    fun restore_failure_does_not_partially_populate() =
        runTest {
            val blob = exportBlob()
            val ccRestore = ExportTestSupport.inMemoryConnectionConfigStore()
            val idRestore = ExportTestSupport.inMemoryIdentityStore()

            // Use wrong password — stores should remain empty.
            newRestoreManager(ccRestore, id = idRestore)
                .restore(blob, "wrong-password".toCharArray())

            assertEquals("connection config must not be populated on failure", null, ccRestore.load())
            assertEquals("identity must not be populated on failure", IdentityLoadResult.None, idRestore.load())
        }

    @Test
    fun store_failure_rolls_back_all_previous_values() =
        runTest {
            val blob = exportBlob()
            val oldConfig = ExportTestSupport.sampleConfig().copy(username = "old-user")
            val cc = ExportTestSupport.inMemoryConnectionConfigStore().also { it.store(oldConfig) }
            val ck =
                ExportTestSupport.inMemoryCommunityKeyStore().also {
                    it.store(CryptoTestSupport.fixedKey(seed = 4))
                }
            val chats =
                ExportTestSupport.inMemoryChatKeyStore().also {
                    it.store("old-chat", CryptoTestSupport.fixedKey(seed = 5))
                }
            val previousIdentityStore =
                ExportTestSupport.inMemoryIdentityStore().also { it.store(ExportTestSupport.freshIdentity()) }
            var failNextWrite = true
            val failingIdentity =
                object : ExportableIdentityStore by previousIdentityStore {
                    override fun store(identity: org.openwebdav.messenger.identity.Identity) {
                        if (failNextWrite) {
                            failNextWrite = false
                            error("injected identity store failure")
                        }
                        previousIdentityStore.store(identity)
                    }
                }

            val result =
                newRestoreManager(cc, ck, chats, failingIdentity)
                    .restore(blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.StoreFailure(rollbackSucceeded = true), result)
            assertEquals(oldConfig, cc.load())
            assertTrue(CryptoTestSupport.fixedKey(seed = 4).export().contentEquals(ck.load()?.export()))
            assertEquals(listOf("old-chat"), chats.listChatIds())
            assertTrue(previousIdentityStore.load() is IdentityLoadResult.Loaded)
        }

    // -- empty base64 blob ---------------------------------------------------

    @Test
    fun empty_blob_rejected() =
        runTest {
            val result = newRestoreManager().restore("", "strong-password".toCharArray())
            // Empty string → Base64 decode fails → BadFormat (not WeakPassword because pw is long enough)
            assertEquals(RestoreResult.BadFormat, result)
        }

    // -- oversized / random data ----------------------------------------------

    @Test
    fun random_bytes_rejected() =
        runTest {
            val randomB64 = Base64.getEncoder().encodeToString(ByteArray(1024) { it.toByte() })
            val result = newRestoreManager().restore(randomB64, "strong-password".toCharArray())
            // Wrong magic → BadFormat (or AEAD rejection → WrongPasswordOrTampered)
            assertTrue(result is RestoreResult.BadFormat || result is RestoreResult.WrongPasswordOrTampered)
        }

    // -- too-short blob -------------------------------------------------------

    @Test
    fun too_short_blob_rejected() =
        runTest {
            // A blob shorter than MAGIC+SALT+NONCE+tag → BadFormat
            val short = ByteArray(10) { 0x41.toByte() }
            val shortB64 = Base64.getEncoder().encodeToString(short)
            val result = newRestoreManager().restore(shortB64, "strong-password".toCharArray())
            assertEquals(RestoreResult.BadFormat, result)
        }
}
