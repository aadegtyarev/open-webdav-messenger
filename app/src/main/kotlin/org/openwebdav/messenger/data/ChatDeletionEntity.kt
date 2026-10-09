package org.openwebdav.messenger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chat_deletions",
    primaryKeys = ["communityId", "chatId"],
    indices = [Index(value = ["phase", "nextAttemptAtMillis", "communityId"])],
)
data class ChatDeletionEntity(
    val communityId: String,
    val chatId: String,
    val scope: String,
    @ColumnInfo(defaultValue = "'ORIGIN_HASH'") val originMode: String = ORIGIN_MODE_HASH,
    val originHash: String,
    val tombstoneHash: String,
    val tombstoneStoredBytes: ByteArray,
    val phase: String,
    val cleanupAttempts: Int = 0,
    val nextAttemptAtMillis: Long? = null,
    val lastError: String? = null,
    val updatedAtMillis: Long,
) {
    companion object {
        const val SCOPE_GROUP = "GROUP"
        const val SCOPE_DM = "DM"
        const val ORIGIN_MODE_HASH = "ORIGIN_HASH"
        const val ORIGIN_MODE_LEGACY_CHAT_ID = "LEGACY_CHAT_ID"
        const val PHASE_LOCAL_COMMITTED = "LOCAL_COMMITTED"
        const val PHASE_TOMBSTONE_PUBLISHED = "TOMBSTONE_PUBLISHED"
        const val PHASE_CLEANUP_PENDING = "CLEANUP_PENDING"
        const val PHASE_COMPLETE = "COMPLETE"
    }
}
