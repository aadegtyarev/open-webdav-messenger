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
        account: ExportableAccountBackupStore? = null,
    ): RestoreManager = RestoreManager(native, cc, ck, ch, id, accountBackupStore = account)

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
    fun unreadable_enumerated_config_or_key_aborts_snapshot_before_any_write() =
        runTest {
            val blob = exportBlob()
            for (fault in listOf("config", "community", "chat")) {
                var writes = 0
                val configBacking = ExportTestSupport.inMemoryConnectionConfigStore()
                val configStore =
                    object : ExportableConnectionConfigStore by configBacking {
                        override fun hasStored(): Boolean = fault == "config" || configBacking.hasStored()

                        override fun load() = if (fault == "config") null else configBacking.load()

                        override fun store(config: org.openwebdav.messenger.transport.ConnectionConfig) {
                            writes++
                            configBacking.store(config)
                        }

                        override fun clear() {
                            writes++
                            configBacking.clear()
                        }
                    }
                val communityBacking = ExportTestSupport.inMemoryCommunityKeyStore()
                val communityStore =
                    object : ExportableCommunityKeyStore by communityBacking {
                        override fun listCommunityIds(): Set<String> =
                            if (fault == "community") setOf("unreadable-community") else communityBacking.listCommunityIds()

                        override fun load(communityId: String) = if (fault == "community") null else communityBacking.load(communityId)

                        override fun replaceAllStrict(keys: Map<String, org.openwebdav.messenger.crypto.ChatKey>) {
                            writes++
                            communityBacking.replaceAllStrict(keys)
                        }
                    }
                val chatBacking = ExportTestSupport.inMemoryChatKeyStore()
                val chatStore =
                    object : ExportableChatKeyStore by chatBacking {
                        override fun listChatIds(): List<String> =
                            if (fault == "chat") listOf("unreadable-chat") else chatBacking.listChatIds()

                        override fun load(chatId: String) = if (fault == "chat") null else chatBacking.load(chatId)

                        override fun replaceAllStrict(chatKeys: Map<String, org.openwebdav.messenger.crypto.ChatKey>) {
                            writes++
                            chatBacking.replaceAllStrict(chatKeys)
                        }
                    }
                val identityBacking = ExportTestSupport.inMemoryIdentityStore()
                val identityStore =
                    object : ExportableIdentityStore by identityBacking {
                        override fun store(identity: org.openwebdav.messenger.identity.Identity) {
                            writes++
                            identityBacking.store(identity)
                        }

                        override fun clear() {
                            writes++
                            identityBacking.clear()
                        }
                    }
                val accountBacking = ExportTestSupport.InMemoryAccountBackupStore()
                val accountStore =
                    object : ExportableAccountBackupStore by accountBacking {
                        override fun replace(backup: AccountBackup) {
                            writes++
                            accountBacking.replace(backup)
                        }
                    }

                val result =
                    newRestoreManager(configStore, communityStore, chatStore, identityStore, accountStore)
                        .restore(blob, "test-password-123".toCharArray())

                assertEquals("$fault snapshot must fail", RestoreResult.StoreFailure(rollbackSucceeded = true), result)
                assertEquals("$fault snapshot must not mutate any store", 0, writes)
            }
        }

    @Test
    fun full_restore_populates_all_stores() =
        runTest {
            val blob = exportBlob()
            val ccRestore = ExportTestSupport.inMemoryConnectionConfigStore()
            val ckRestore = ExportTestSupport.inMemoryCommunityKeyStore()
            val chRestore = ExportTestSupport.inMemoryChatKeyStore()
            val idRestore = ExportTestSupport.inMemoryIdentityStore()
            val accountRestore =
                ExportTestSupport.InMemoryAccountBackupStore(onReplace = { backup ->
                    ccRestore.store(backup.communities.single().config)
                })

            val result =
                newRestoreManager(ccRestore, ckRestore, chRestore, idRestore, accountRestore)
                    .restore(blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.Restored, result)
            assertTrue("connection config should be restored", ccRestore.load() != null)
            assertTrue("community key should be restored", ckRestore.load() != null)
            assertTrue("chat key should be restored", chRestore.load("chat-a") != null)
            assertTrue("identity should be restored", idRestore.load() is IdentityLoadResult.Loaded)
            assertEquals("default", accountRestore.snapshot()?.activeCommunityId)
        }

    @Test
    fun encrypted_backup_rejects_invalid_unregistered_chat_key_before_any_write() =
        runTest {
            val chatId = "anchor-a"
            val backup =
                AccountBackup(
                    "community-a",
                    listOf(
                        CommunityBackup(
                            "community-a",
                            "A",
                            chatId,
                            ExportTestSupport.sampleConfig(),
                            listOf(ChatBackup(chatId, "General", "general")),
                        ),
                    ),
                )
            val sourceKeys =
                ExportTestSupport.inMemoryChatKeyStore().also {
                    it.store(chatId, CryptoTestSupport.fixedKey(seed = 70))
                    it.store("bad/id", CryptoTestSupport.fixedKey(seed = 71))
                }
            val blob =
                ExportManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    sourceKeys,
                    ExportTestSupport.inMemoryIdentityStore().also { it.store(ExportTestSupport.freshIdentity()) },
                    accountBackupStore = ExportTestSupport.InMemoryAccountBackupStore(backup),
                ).export("test-password-123".toCharArray()) as ExportResult.Ready

            var writes = 0
            val configBacking = ExportTestSupport.inMemoryConnectionConfigStore()
            val configStore =
                object : ExportableConnectionConfigStore by configBacking {
                    override fun store(config: org.openwebdav.messenger.transport.ConnectionConfig) {
                        writes++
                        configBacking.store(config)
                    }

                    override fun clear() {
                        writes++
                        configBacking.clear()
                    }
                }
            val communityBacking = ExportTestSupport.inMemoryCommunityKeyStore()
            val communityStore =
                object : ExportableCommunityKeyStore by communityBacking {
                    override fun replaceAllStrict(keys: Map<String, org.openwebdav.messenger.crypto.ChatKey>) {
                        writes++
                        communityBacking.replaceAllStrict(keys)
                    }
                }
            val chatBacking = ExportTestSupport.inMemoryChatKeyStore()
            val chatStore =
                object : ExportableChatKeyStore by chatBacking {
                    override fun replaceAllStrict(chatKeys: Map<String, org.openwebdav.messenger.crypto.ChatKey>) {
                        writes++
                        chatBacking.replaceAllStrict(chatKeys)
                    }
                }
            val identityBacking = ExportTestSupport.inMemoryIdentityStore()
            val identityStore =
                object : ExportableIdentityStore by identityBacking {
                    override fun store(identity: org.openwebdav.messenger.identity.Identity) {
                        writes++
                        identityBacking.store(identity)
                    }
                }
            val accountBacking = ExportTestSupport.InMemoryAccountBackupStore()
            val accountStore =
                object : ExportableAccountBackupStore by accountBacking {
                    override fun replace(backup: AccountBackup) {
                        writes++
                        accountBacking.replace(backup)
                    }
                }

            val result =
                RestoreManager(native, configStore, communityStore, chatStore, identityStore, accountBackupStore = accountStore)
                    .restore(blob.blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.CorruptPayload, result)
            assertEquals(0, writes)
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
            val sourceChatKeys =
                ExportTestSupport.inMemoryChatKeyStore().also {
                    it.store("anchor-new", CryptoTestSupport.fixedKey(seed = 32))
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

    @Test
    fun persistent_registry_rollback_failure_still_restores_other_present_stores() =
        runTest {
            val backup =
                AccountBackup(
                    "community-new",
                    listOf(
                        CommunityBackup(
                            "community-new",
                            "New",
                            "new-chat",
                            ExportTestSupport.sampleConfig(),
                            listOf(ChatBackup("new-chat", "General", "general")),
                        ),
                    ),
                )
            val sourceKeys =
                ExportTestSupport.inMemoryChatKeyStore().also {
                    it.store("new-chat", CryptoTestSupport.fixedKey(seed = 61))
                }
            val sourceIdentity = ExportTestSupport.inMemoryIdentityStore().also { it.store(ExportTestSupport.freshIdentity()) }
            val blob =
                ExportManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    sourceKeys,
                    sourceIdentity,
                    accountBackupStore = ExportTestSupport.InMemoryAccountBackupStore(backup),
                ).export("test-password-123".toCharArray()) as ExportResult.Ready
            val oldBackup =
                AccountBackup(
                    "community-old",
                    listOf(
                        CommunityBackup(
                            "community-old",
                            "Old",
                            "old-chat",
                            ExportTestSupport.sampleConfig(),
                            listOf(ChatBackup("old-chat", "General", "general")),
                        ),
                    ),
                )
            val registry = ExportTestSupport.InMemoryAccountBackupStore(oldBackup)
            val persistentRegistryFailure =
                object : ExportableAccountBackupStore by registry {
                    override fun replace(backup: AccountBackup) = error("persistent registry failure")
                }
            val oldConfig = ExportTestSupport.sampleConfig().copy(username = "existing-user")
            val configBacking = ExportTestSupport.inMemoryConnectionConfigStore().also { it.store(oldConfig) }
            var configClears = 0
            val configStore =
                object : ExportableConnectionConfigStore by configBacking {
                    override fun clear() {
                        configClears++
                        error("present config must not be cleared")
                    }
                }
            val oldCommunityKey = CryptoTestSupport.fixedKey(seed = 62)
            val communityKeys = ExportTestSupport.inMemoryCommunityKeyStore().also { it.store("community-old", oldCommunityKey) }
            val chats = ExportTestSupport.inMemoryChatKeyStore().also { it.store("old-chat", CryptoTestSupport.fixedKey(seed = 63)) }
            val oldIdentity = ExportTestSupport.freshIdentity()
            val backingIdentity = ExportTestSupport.inMemoryIdentityStore().also { it.store(oldIdentity) }
            var identityStores = 0
            val identity =
                object : ExportableIdentityStore by backingIdentity {
                    override fun store(identity: org.openwebdav.messenger.identity.Identity) {
                        identityStores++
                        backingIdentity.store(identity)
                    }

                    override fun clear() = error("present identity must not be cleared")
                }

            val result =
                RestoreManager(
                    native,
                    configStore,
                    communityKeys,
                    chats,
                    identity,
                    accountBackupStore = persistentRegistryFailure,
                ).restore(blob.blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.StoreFailure(rollbackSucceeded = false), result)
            assertEquals(oldBackup, registry.snapshot())
            assertEquals(oldConfig, configBacking.load())
            assertEquals(0, configClears)
            assertTrue(CryptoTestSupport.fixedKey(seed = 62).export().contentEquals(communityKeys.load("community-old")?.export()))
            assertEquals(listOf("old-chat"), chats.listChatIds())
            assertTrue(
                oldIdentity.copySignPublic().contentEquals((backingIdentity.load() as IdentityLoadResult.Loaded).identity.copySignPublic()),
            )
            assertEquals("restore write plus independent rollback store", 2, identityStores)
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
    fun legacy_restore_rejects_nonempty_target_before_writes() =
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

            val account = ExportTestSupport.InMemoryAccountBackupStore()
            val result =
                newRestoreManager(cc, ck, chats, failingIdentity, account)
                    .restore(blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.IncompatibleTarget, result)
            assertEquals(oldConfig, cc.load())
            assertTrue(CryptoTestSupport.fixedKey(seed = 4).export().contentEquals(ck.load()?.export()))
            assertEquals(listOf("old-chat"), chats.listChatIds())
            assertTrue(failNextWrite)
            assertTrue(previousIdentityStore.load() is IdentityLoadResult.Loaded)
        }

    @Test
    fun oversized_blob_rejected_before_base64_decode_or_store_access() =
        runTest {
            val result =
                newRestoreManager().restore(
                    "A".repeat(ExportManager.MAX_BLOB_BASE64_CHARS + 1),
                    "strong-password".toCharArray(),
                )
            assertEquals(RestoreResult.BadFormat, result)
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
