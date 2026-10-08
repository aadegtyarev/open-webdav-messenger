package org.openwebdav.messenger.export

import org.openwebdav.messenger.transport.ConnectionConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Strict bounded binary encoding for the multi-community portion inside the encrypted export payload. */
internal object AccountBackupCodec {
    fun encode(backup: AccountBackup): ByteArray {
        require(backup.communities.size <= MAX_COMMUNITIES)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(backup.activeCommunityId)
            out.writeInt(backup.communities.size)
            backup.communities.forEach { community ->
                out.writeUTF(community.id)
                out.writeUTF(community.name)
                out.writeUTF(community.anchorChatId)
                out.writeUTF(community.config.baseUrl)
                out.writeUTF(community.config.username)
                out.writeUTF(community.config.appPassword)
                out.writeUTF(community.config.chatRoot)
                out.writeBoolean(community.communityKeyBase64 != null)
                community.communityKeyBase64?.let(out::writeUTF)
                out.writeBoolean(community.isHost)
                out.writeInt(community.pollFloorSeconds)
                out.writeInt(community.retentionWindowDays)
                require(community.chats.size <= MAX_CHATS)
                out.writeInt(community.chats.size)
                community.chats.forEach { chat ->
                    out.writeUTF(chat.id)
                    out.writeUTF(chat.name)
                    out.writeUTF(chat.kind)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): AccountBackup? =
        runCatching {
            require(bytes.size <= MAX_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == VERSION)
                val activeId = input.readUTF()
                val count = input.readInt().also { require(it in 1..MAX_COMMUNITIES) }
                val communities =
                    List(count) {
                        val id = input.readUTF().also { require(it.isNotBlank()) }
                        val name = input.readUTF()
                        val anchor = input.readUTF().also { require(it.isNotBlank()) }
                        val config = ConnectionConfig(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
                        val communityKey = if (input.readBoolean()) input.readUTF() else null
                        val isHost = input.readBoolean()
                        val pollFloor = input.readInt().also { require(it in 1..3600) }
                        val retentionDays = input.readInt().also { require(it in 7..90) }
                        val chatCount = input.readInt().also { require(it in 1..MAX_CHATS) }
                        val chats =
                            List(chatCount) {
                                ChatBackup(input.readUTF(), input.readUTF(), input.readUTF())
                            }
                        CommunityBackup(id, name, anchor, config, chats, communityKey, isHost, pollFloor, retentionDays)
                    }
                require(input.available() == 0 && communities.any { it.id == activeId })
                AccountBackup(activeId, communities)
            }
        }.getOrNull()

    private const val VERSION = 1
    private const val MAX_COMMUNITIES = 100
    private const val MAX_CHATS = 1000
    private const val MAX_BYTES = 1_000_000
}
