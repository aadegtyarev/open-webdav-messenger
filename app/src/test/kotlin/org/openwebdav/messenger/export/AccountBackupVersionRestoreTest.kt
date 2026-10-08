package org.openwebdav.messenger.export

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import java.nio.ByteBuffer
import java.util.Base64

class AccountBackupVersionRestoreTest {
    @Test
    fun authenticated_v2_and_v3_backups_restore_exact_access_metadata() =
        runTest {
            val native = ExportTestSupport.native()
            val identity = ExportTestSupport.freshIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 52 })
            val backup =
                AccountBackup(
                    "community-a",
                    listOf(
                        CommunityBackup(
                            "community-a",
                            "A",
                            "anchor-a",
                            ExportTestSupport.sampleConfig(),
                            listOf(
                                ChatBackup("anchor-a", "General", "general", "public"),
                                ChatBackup("group-a", "Private", "group", "private"),
                            ),
                        ),
                    ),
                )
            for (version in listOf(2, 3)) {
                val encoded = AccountBackupCodec.encode(backup).also { ByteBuffer.wrap(it).putInt(version) }
                val payload =
                    ExportPayload(
                        null,
                        null,
                        mapOf(
                            "anchor-a" to Base64.getEncoder().encodeToString(key.export()),
                            "group-a" to Base64.getEncoder().encodeToString(key.export()),
                        ),
                        Base64.getEncoder().encodeToString(Identity.serialize(identity)),
                        Base64.getEncoder().encodeToString(encoded),
                    )
                val json = ExportPayload.toJson(payload).replace("\"v\":3", "\"v\":$version")
                val blob = encryptedPayload(native, json, "restore-password")
                val account = ExportTestSupport.InMemoryAccountBackupStore()
                val result =
                    RestoreManager(
                        native,
                        ExportTestSupport.inMemoryConnectionConfigStore(),
                        ExportTestSupport.inMemoryCommunityKeyStore(),
                        ExportTestSupport.inMemoryChatKeyStore(),
                        ExportTestSupport.inMemoryIdentityStore(),
                        accountBackupStore = account,
                    ).restore(blob, "restore-password".toCharArray())

                assertEquals(RestoreResult.Restored, result)
                assertEquals(backup, account.snapshot())
            }
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
            val ciphertext = native.aeadEncrypt(json.toByteArray(Charsets.UTF_8), ExportManager.MAGIC, nonce, key)
            Base64.getEncoder().encodeToString(ExportManager.MAGIC + salt + nonce + ciphertext)
        } finally {
            passphrase.fill(0)
            key.fill(0)
        }
    }
}
