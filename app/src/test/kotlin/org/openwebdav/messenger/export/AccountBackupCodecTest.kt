package org.openwebdav.messenger.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.transport.ConnectionConfig
import java.util.Base64

class AccountBackupCodecTest {
    @Test
    fun version_two_round_trips_multiple_communities_and_chats() {
        val backup =
            AccountBackup(
                activeCommunityId = "community-b",
                communities =
                    listOf(
                        community("community-a", "General", "Group"),
                        community("community-b", "General", "Private chat"),
                    ),
            )
        val json = ExportPayload.toJson(payload(backup))
        val restored = ExportPayload.fromJson(json)
        assertNotNull(restored)
        val bytes = Base64.getDecoder().decode(restored!!.accountBackupBase64)
        assertEquals(backup, AccountBackupCodec.decode(bytes))
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
