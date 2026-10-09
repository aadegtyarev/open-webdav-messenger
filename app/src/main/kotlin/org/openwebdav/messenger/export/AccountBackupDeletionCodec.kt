package org.openwebdav.messenger.export

import java.io.DataInput
import java.io.DataOutput

internal data class LocalDeletionBackup(
    val hiddenDmChatIds: List<String>,
    val suppressedDmMessageIds: Map<String, List<String>>,
)

internal object AccountBackupDeletionCodec {
    fun write(
        output: DataOutput,
        community: CommunityBackup,
    ) {
        val hidden = community.hiddenDmChatIds.distinct().sorted()
        require(hidden.size == community.hiddenDmChatIds.size && hidden.size <= MAX_CHATS)
        output.writeInt(hidden.size)
        hidden.forEach(output::writeUTF)
        val groups = community.suppressedDmMessageIds.toSortedMap()
        val total =
            groups.values.sumOf { ids ->
                require(ids.size <= MAX_IDS_PER_CHAT && ids.distinct().size == ids.size)
                ids.size
            }
        require(groups.size <= MAX_CHATS && total <= MAX_IDS_TOTAL)
        val pairs = groups.flatMap { (chatId, ids) -> ids.sorted().map { chatId to it } }
        output.writeInt(pairs.size)
        pairs.forEach { (chatId, messageId) ->
            output.writeUTF(chatId)
            output.writeUTF(messageId)
        }
    }

    fun read(input: DataInput): LocalDeletionBackup {
        val hiddenCount = input.readInt().also { require(it in 0..MAX_CHATS) }
        val hidden = List(hiddenCount) { input.readUTF() }
        require(hidden.distinct().size == hidden.size)
        val suppressedCount = input.readInt().also { require(it in 0..MAX_IDS_TOTAL) }
        val suppressed = linkedMapOf<String, MutableList<String>>()
        repeat(suppressedCount) {
            val chatId = input.readUTF()
            val messageId = input.readUTF()
            val ids = suppressed.getOrPut(chatId, ::mutableListOf)
            require(ids.size < MAX_IDS_PER_CHAT && messageId !in ids)
            ids.add(messageId)
        }
        return LocalDeletionBackup(hidden, suppressed.mapValues { it.value.toList() })
    }

    private const val MAX_CHATS = 1000
    private const val MAX_IDS_PER_CHAT = 10_000
    private const val MAX_IDS_TOTAL = 100_000
}
