package org.openwebdav.messenger.data

import androidx.room.Entity

@Entity(tableName = "hidden_dms", primaryKeys = ["communityId", "chatId"])
data class HiddenDmEntity(
    val communityId: String,
    val chatId: String,
)
