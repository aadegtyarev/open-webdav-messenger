package org.openwebdav.messenger.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.transport.ConnectionConfig
import java.util.Base64

class AccountBackupCodecTest {
    @Test
    fun version_five_round_trips_exact_access_dm_deletion_and_trust_metadata() {
        val backup =
            AccountBackup(
                activeCommunityId = "community-b",
                communities =
                    listOf(
                        community("community-a", "General", "Group").copy(
                            anchorChatId = "anchor-a",
                            chats =
                                listOf(
                                    ChatBackup("anchor-a", "General", "general", "public"),
                                    ChatBackup("group-a", "Group", "group", "private"),
                                ),
                        ),
                        community("community-b", "General", "Private chat").copy(
                            anchorChatId = "anchor-b",
                            chats =
                                listOf(
                                    ChatBackup("anchor-b", "General", "general", "unknown"),
                                    ChatBackup("dm-b", "Peer", "dm", "private"),
                                ),
                            hiddenDmChatIds = listOf("dm-b"),
                            suppressedDmMessageIds = mapOf("dm-b" to listOf("old-1", "old-2")),
                            genesisRecordBase64 = Base64.getEncoder().encodeToString(ByteArray(128) { it.toByte() }),
                            hostFingerprintVerified = true,
                            highestPolicyRevision = 4,
                            highestPolicyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        ),
                    ),
            )
        val json = ExportPayload.toJson(payload(backup))
        val restored = ExportPayload.fromJson(json)
        assertNotNull(restored)
        val bytes = Base64.getDecoder().decode(restored!!.accountBackupBase64)
        assertEquals(5, java.nio.ByteBuffer.wrap(bytes).int)
        assertEquals(backup, AccountBackupCodec.decode(bytes))
    }

    @Test
    fun version_two_payload_decodes_access_without_inference() {
        val backup =
            AccountBackup(
                "community-a",
                listOf(
                    community("community-a", "General", "Private").copy(
                        anchorChatId = "anchor-a",
                        chats =
                            listOf(
                                ChatBackup("anchor-a", "General", "general", "public"),
                                ChatBackup("group-a", "Private", "group", "private"),
                            ),
                    ),
                ),
            )
        val bytes =
            AccountBackupCodec.encode(backup).copyOf(AccountBackupCodec.encode(backup).size - 19)
                .also { java.nio.ByteBuffer.wrap(it).putInt(2) }

        assertEquals(backup, AccountBackupCodec.decode(bytes))
    }

    @Test
    fun unsupported_account_backup_versions_are_rejected() {
        val bytes = AccountBackupCodec.encode(AccountBackup("community-a", listOf(community("community-a", "General"))))
        java.nio.ByteBuffer.wrap(bytes).putInt(6)

        assertNull(AccountBackupCodec.decode(bytes))
    }

    @Test
    fun outer_backup_payload_v2_through_v5_migrate_and_v5_is_current() {
        val backup = AccountBackup("community-a", listOf(community("community-a", "General")))
        val current = payload(backup)
        val currentBytes = AccountBackupCodec.encode(backup)
        val v4Bytes = currentBytes.copyOf(currentBytes.size - 11).also { java.nio.ByteBuffer.wrap(it).putInt(4) }
        val v4Json =
            ExportPayload.toJson(current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v4Bytes)))
                .replace("\"v\":5", "\"v\":4")
        val v3Bytes = currentBytes.copyOf(currentBytes.size - 19).also { java.nio.ByteBuffer.wrap(it).putInt(3) }
        val v3Payload = current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v3Bytes))
        val v3Json = ExportPayload.toJson(v3Payload).replace("\"v\":5", "\"v\":3")
        val v2Bytes = v3Bytes.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(2) }
        val v2Payload = current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v2Bytes))
        val v2Json = ExportPayload.toJson(v2Payload).replace("\"v\":5", "\"v\":2")
        val v5Json = ExportPayload.toJson(current)

        assertTrue(v5Json.contains("\"v\":5"))
        assertNotNull(ExportPayload.fromJson(v5Json))
        assertNotNull(ExportPayload.fromJson(v4Json))
        assertNotNull(ExportPayload.fromJson(v3Json))
        assertNotNull(ExportPayload.fromJson(v2Json))
        assertNull(ExportPayload.fromJson(v4Json.replace("\"v\":4", "\"v\":3")))
        assertNull(ExportPayload.fromJson(v2Json.replace("\"v\":2", "\"v\":3")))
    }

    @Test
    fun version_one_chat_access_decodes_to_unknown_instead_of_inferred_privilege() {
        val bytes =
            java.io.ByteArrayOutputStream().also { stream ->
                java.io.DataOutputStream(stream).use { out ->
                    out.writeInt(1)
                    out.writeUTF("community-a")
                    out.writeInt(1)
                    out.writeUTF("community-a")
                    out.writeUTF("A")
                    out.writeUTF("chat-a")
                    out.writeUTF("https://a.example")
                    out.writeUTF("user")
                    out.writeUTF("password")
                    out.writeUTF("root")
                    out.writeBoolean(false)
                    out.writeBoolean(false)
                    out.writeInt(60)
                    out.writeInt(14)
                    out.writeInt(1)
                    out.writeUTF("chat-a")
                    out.writeUTF("General")
                    out.writeUTF("general")
                }
            }.toByteArray()

        assertEquals("unknown", AccountBackupCodec.decode(bytes)?.communities?.single()?.chats?.single()?.access)
    }

    @Test
    fun payload_encoder_and_decoder_share_exact_size_limit() {
        val empty = ExportPayload(null, null, emptyMap(), "")
        val base = ExportPayload.toJson(empty).length
        val maxPayload = empty.copy(identitySerialized = "x".repeat(ExportPayload.MAX_PLAINTEXT_BYTES - base))
        assertEquals(ExportPayload.MAX_PLAINTEXT_BYTES, ExportPayload.toJson(maxPayload).length)
        org.junit.Assert.assertThrows(PayloadTooLargeException::class.java) {
            ExportPayload.toJson(maxPayload.copy(identitySerialized = maxPayload.identitySerialized + "x"))
        }
        val valid = """{"v":1,"cc":null,"ck":null,"ch":{},"id":null}"""
        assertNotNull(ExportPayload.fromJson(valid + " ".repeat(ExportPayload.MAX_PLAINTEXT_BYTES - valid.length)))
        assertNull(ExportPayload.fromJson(valid + " ".repeat(ExportPayload.MAX_PLAINTEXT_BYTES - valid.length + 1)))
    }

    @Test
    fun version_one_legacy_payload_remains_parseable() {
        val legacy = """{"v":1,"cc":null,"ck":null,"ch":{},"id":null}"""
        val payload = ExportPayload.fromJson(legacy)
        assertNotNull(payload)
        assertNull(payload!!.accountBackupBase64)
    }

    private fun payload(backup: AccountBackup) =
        ExportPayload(
            connectionConfig = null,
            communityKeyBase64 = null,
            chatKeys = emptyMap(),
            identitySerialized = "legacy-identity",
            accountBackupBase64 = Base64.getEncoder().encodeToString(AccountBackupCodec.encode(backup)),
        )

    private fun community(
        id: String,
        vararg chatNames: String,
    ) = CommunityBackup(
        id = id,
        name = "Community $id",
        anchorChatId = "anchor-$id",
        config = ConnectionConfig("https://$id.example", "user", "password", "root/$id"),
        chats = chatNames.mapIndexed { index, name -> ChatBackup("chat-$id-$index", name, "group") },
    )
}
