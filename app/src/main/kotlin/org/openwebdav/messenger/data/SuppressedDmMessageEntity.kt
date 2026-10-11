package org.openwebdav.messenger.data

import androidx.room.Entity

@Entity(tableName = "suppressed_dm_messages", primaryKeys = ["communityId", "chatId", "messageId"])
data class SuppressedDmMessageEntity(
    val communityId: String,
    val chatId: String,
    val messageId: String,
)
