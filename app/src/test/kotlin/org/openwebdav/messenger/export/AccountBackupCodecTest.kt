package org.openwebdav.messenger.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.protocol.Base32
import org.openwebdav.messenger.transport.ConnectionConfig
import java.security.MessageDigest
import java.util.Base64

class AccountBackupCodecTest {
    @Test
    fun version_seven_round_trips_trust_and_authenticated_chat_deletion_evidence() {
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
                            chatDeletions =
                                listOf(
                                    ChatDeletionBackup(
                                        "deleted-dm",
                                        "DM",
                                        "ORIGIN_HASH",
                                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                        deletionHash(ByteArray(64) { it.toByte() }),
                                        Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }),
                                        "CLEANUP_PENDING",
                                        2,
                                        1234L,
                                        "retry later",
                                        5678L,
                                    ),
                                ),
                        ),
                    ),
            )
        val json = ExportPayload.toJson(payload(backup))
        val restored = ExportPayload.fromJson(json)
        assertNotNull(restored)
        val bytes = Base64.getDecoder().decode(restored!!.accountBackupBase64)
        assertEquals(7, java.nio.ByteBuffer.wrap(bytes).int)
        assertEquals(backup, AccountBackupCodec.decode(bytes))
    }

    @Test
    fun version_five_decodes_verified_and_unverified_trust_state() {
        val backup =
            AccountBackup(
                "community-a",
                listOf(
                    community("community-a", "General").copy(
                        genesisRecordBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                        hostFingerprintVerified = true,
                        highestPolicyRevision = 4,
                        highestPolicyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    ),
                    community("community-b", "General").copy(
                        genesisRecordBase64 = Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)),
                        hostFingerprintVerified = false,
                    ),
                ),
            )
        assertEquals(backup, AccountBackupCodec.decode(encodeVersionFive(backup)))
    }

    private fun encodeVersionFive(backup: AccountBackup): ByteArray =
        java.io.ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use { output ->
                output.writeInt(5)
                output.writeUTF(backup.activeCommunityId)
                output.writeInt(backup.communities.size)
                backup.communities.forEach { community ->
                    output.writeUTF(community.id)
                    output.writeUTF(community.name)
                    output.writeUTF(community.anchorChatId)
                    output.writeUTF(community.config.baseUrl)
                    output.writeUTF(community.config.username)
                    output.writeUTF(community.config.appPassword)
                    output.writeUTF(community.config.chatRoot)
                    output.writeBoolean(community.communityKeyBase64 != null)
                    community.communityKeyBase64?.let(output::writeUTF)
                    output.writeBoolean(community.isHost)
                    output.writeInt(community.pollFloorSeconds)
                    output.writeInt(community.retentionWindowDays)
                    output.writeInt(community.chats.size)
                    community.chats.forEach { chat ->
                        output.writeUTF(chat.id)
                        output.writeUTF(chat.name)
                        output.writeUTF(chat.kind)
                        output.writeUTF(chat.access)
                    }
                    output.writeInt(community.hiddenDmChatIds.size)
                    community.hiddenDmChatIds.forEach(output::writeUTF)
                    val suppressed = community.suppressedDmMessageIds.flatMap { (chatId, ids) -> ids.map { chatId to it } }
                    output.writeInt(suppressed.size)
                    suppressed.forEach { (chatId, messageId) ->
                        output.writeUTF(chatId)
                        output.writeUTF(messageId)
                    }
                    AccountBackupTrustCodec.write(output, community)
                }
            }
        }.toByteArray()

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
            AccountBackupCodec.encode(backup).copyOf(AccountBackupCodec.encode(backup).size - 23)
                .also { java.nio.ByteBuffer.wrap(it).putInt(2) }

        assertEquals(backup, AccountBackupCodec.decode(bytes))
    }

    @Test
    fun unsupported_account_backup_versions_are_rejected() {
        val bytes = AccountBackupCodec.encode(AccountBackup("community-a", listOf(community("community-a", "General"))))
        java.nio.ByteBuffer.wrap(bytes).putInt(8)

        assertNull(AccountBackupCodec.decode(bytes))
    }

    @Test
    fun outer_backup_payload_v2_through_v7_migrate_and_v7_is_current() {
        val backup = AccountBackup("community-a", listOf(community("community-a", "General")))
        val current = payload(backup)
        val currentBytes = AccountBackupCodec.encode(backup)
        val v5Bytes = currentBytes.copyOf(currentBytes.size - 4).also { java.nio.ByteBuffer.wrap(it).putInt(5) }
        val v4Bytes = currentBytes.copyOf(currentBytes.size - 15).also { java.nio.ByteBuffer.wrap(it).putInt(4) }
        val v4Json =
            ExportPayload.toJson(current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v4Bytes)))
                .replace("\"v\":7", "\"v\":4")
        val v3Bytes = currentBytes.copyOf(currentBytes.size - 23).also { java.nio.ByteBuffer.wrap(it).putInt(3) }
        val v3Payload = current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v3Bytes))
        val v3Json = ExportPayload.toJson(v3Payload).replace("\"v\":7", "\"v\":3")
        val v2Bytes = v3Bytes.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(2) }
        val v2Payload = current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v2Bytes))
        val v2Json = ExportPayload.toJson(v2Payload).replace("\"v\":7", "\"v\":2")
        val v5Payload = current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v5Bytes))
        val v5Json = ExportPayload.toJson(v5Payload).replace("\"v\":7", "\"v\":5")
        val v6Bytes = currentBytes.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(6) }
        val v6Payload = current.copy(accountBackupBase64 = Base64.getEncoder().encodeToString(v6Bytes))
        val v6Json = ExportPayload.toJson(v6Payload).replace("\"v\":7", "\"v\":6")
        val v7Json = ExportPayload.toJson(current)

        assertTrue(v7Json.contains("\"v\":7"))
        assertNotNull(ExportPayload.fromJson(v7Json))
        assertNotNull(ExportPayload.fromJson(v6Json))
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

    private fun deletionHash(bytes: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(32)

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
