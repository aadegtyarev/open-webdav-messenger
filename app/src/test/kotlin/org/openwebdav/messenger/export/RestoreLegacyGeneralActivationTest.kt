package org.openwebdav.messenger.export

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.app.LegacyGeneralAnchorAccess
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.keystore.ChatRegistry
import java.util.Base64

class RestoreLegacyGeneralActivationTest {
    @Test
    fun restore_activation_migrates_exact_unknown_general_after_replacement() =
        runTest {
            val native = ExportTestSupport.native()
            val identity = ExportTestSupport.freshIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 32 })
            val anchor = "legacy-anchor-0001"
            val backup =
                AccountBackup(
                    "community-a",
                    listOf(
                        CommunityBackup(
                            "community-a",
                            "Legacy",
                            anchor,
                            ExportTestSupport.sampleConfig(),
                            listOf(ChatBackup(anchor, "General", "general", "unknown")),
                        ),
                    ),
                )
            val payload = ExportPayload.build(null, null, mapOf(anchor to key), identity, backup)
            val blob = encryptedPayload(native, ExportPayload.toJson(payload), "restore-password")
            val account = ExportTestSupport.InMemoryAccountBackupStore()
            val generation = AccountMutationBarrier.process.replacementGeneration()
            var activationGeneration = -1L
            val result =
                RestoreManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    ExportTestSupport.inMemoryChatKeyStore(),
                    ExportTestSupport.inMemoryIdentityStore(),
                    accountBackupStore = account,
                    activateRuntime = {
                        activationGeneration = AccountMutationBarrier.process.replacementGeneration()
                        val restoredAccount = checkNotNull(account.snapshot())
                        val restored = restoredAccount.communities.single()
                        val rows = restored.chats.map { ChatRegistry.Entry(it.id, it.name, it.kind, it.access) }
                        val migrated =
                            LegacyGeneralAnchorAccess.migrate(
                                restored.anchorChatId,
                                restored.anchorChatId,
                                restored.anchorChatId,
                                rows,
                            )
                        assertNotNull(migrated)
                        val chats = checkNotNull(migrated).map { ChatBackup(it.id, it.name, it.kind, it.access) }
                        account.replace(restoredAccount.copy(communities = listOf(restored.copy(chats = chats))))
                    },
                ).restore(blob, "restore-password".toCharArray())

            assertEquals(RestoreResult.Restored, result)
            assertEquals(generation + 1, activationGeneration)
            assertEquals("public", account.snapshot()?.communities?.single()?.chats?.single()?.access)
        }

    private fun encryptedPayload(
        native: org.openwebdav.messenger.crypto.NativeCrypto,
        json: String,
        password: String,
    ): String {
        val salt = native.randomBytes(ExportManager.SALT_BYTES)
        val passphrase = password.toByteArray(Charsets.UTF_8)
        val key =
            native.argon2id(
                passphrase,
                salt,
                ChatKey.KEY_BYTES,
                ExportManager.ARGON2ID_OPS_INTERACTIVE,
                ExportManager.ARGON2ID_MEM_INTERACTIVE,
            )
        val nonce = native.randomBytes(ExportManager.NONCE_BYTES)
        return try {
            Base64.getEncoder().encodeToString(
                ExportManager.MAGIC + salt + nonce + native.aeadEncrypt(json.toByteArray(Charsets.UTF_8), ExportManager.MAGIC, nonce, key),
            )
        } finally {
            passphrase.fill(0)
            key.fill(0)
        }
    }
}
