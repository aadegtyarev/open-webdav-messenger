package org.openwebdav.messenger.export

import org.openwebdav.messenger.transport.ConnectionConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Strict bounded binary encoding for the multi-community portion inside the encrypted export payload. */
internal object AccountBackupCodec {
    fun encode(backup: AccountBackup): ByteArray {
        if (backup.communities.size > MAX_COMMUNITIES) throw PayloadTooLargeException()
        val bytes = ByteArrayOutputStream()
        try {
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
                    if (community.chats.size > MAX_CHATS) throw PayloadTooLargeException()
                    out.writeInt(community.chats.size)
                    community.chats.forEach { chat ->
                        out.writeUTF(chat.id)
                        out.writeUTF(chat.name)
                        out.writeUTF(chat.kind)
                        require(chat.access in ACCESS_VALUES)
                        out.writeUTF(chat.access)
                        if (bytes.size() > MAX_BYTES) throw PayloadTooLargeException()
                    }
                    AccountBackupDeletionCodec.write(out, community)
                    AccountBackupTrustCodec.write(out, community)
                    if (bytes.size() > MAX_BYTES) throw PayloadTooLargeException()
                }
            }
        } catch (_: java.io.UTFDataFormatException) {
            throw PayloadTooLargeException()
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): AccountBackup? =
        runCatching {
            require(bytes.size <= MAX_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readInt().also { require(it in LEGACY_VERSION..VERSION) }
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
                                val chatId = input.readUTF()
                                val chatName = input.readUTF()
                                val kind = input.readUTF()
                                val access = if (version >= ACCESS_VERSION) input.readUTF() else legacyAccess()
                                require(access in ACCESS_VALUES)
                                ChatBackup(chatId, chatName, kind, access)
                            }
                        val deletion =
                            if (version >= DELETION_STATE_VERSION) {
                                AccountBackupDeletionCodec.read(input)
                            } else {
                                LocalDeletionBackup(emptyList(), emptyMap())
                            }
                        val trust =
                            if (version >= TRUST_STATE_VERSION) {
                                AccountBackupTrustCodec.read(input)
                            } else {
                                CommunityTrustBackup("", false, 0, null)
                            }
                        CommunityBackup(
                            id = id,
                            name = name,
                            anchorChatId = anchor,
                            config = config,
                            chats = chats,
                            communityKeyBase64 = communityKey,
                            isHost = isHost,
                            pollFloorSeconds = pollFloor,
                            retentionWindowDays = retentionDays,
                            hiddenDmChatIds = deletion.hiddenDmChatIds,
                            suppressedDmMessageIds = deletion.suppressedDmMessageIds,
                            genesisRecordBase64 = trust.genesisRecordBase64.ifEmpty { null },
                            hostFingerprintVerified = trust.fingerprintVerified,
                            highestPolicyRevision = trust.highestPolicyRevision,
                            highestPolicyHash = trust.highestPolicyHash,
                        )
                    }
                require(input.available() == 0 && communities.any { it.id == activeId })
                AccountBackup(activeId, communities)
            }
        }.getOrNull()

    private const val VERSION = 5
    private const val LEGACY_VERSION = 1
    private const val ACCESS_VERSION = 2
    private const val DELETION_STATE_VERSION = 4
    private const val TRUST_STATE_VERSION = 5
    private val ACCESS_VALUES = setOf("public", "private", "unknown")

    private fun legacyAccess(): String = "unknown"

    private const val MAX_COMMUNITIES = 100
    private const val MAX_CHATS = 1000
    private const val MAX_BYTES = 1_000_000
}
