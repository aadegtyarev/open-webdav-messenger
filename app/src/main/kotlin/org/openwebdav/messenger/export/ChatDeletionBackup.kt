package org.openwebdav.messenger.export

data class ChatDeletionBackup(
    val chatId: String,
    val scope: String,
    val originHash: String,
    val tombstoneHash: String,
    val tombstoneStoredBytesBase64: String,
    val phase: String,
    val cleanupAttempts: Int,
    val nextAttemptAtMillis: Long?,
    val lastError: String?,
    val updatedAtMillis: Long,
)
