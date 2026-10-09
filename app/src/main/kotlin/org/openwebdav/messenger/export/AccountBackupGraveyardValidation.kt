package org.openwebdav.messenger.export

import org.openwebdav.messenger.protocol.Base32
import java.security.MessageDigest

internal object AccountBackupGraveyardValidation {
    fun isValid(deletions: List<ChatDeletionBackup>): Boolean =
        runCatching {
            require(deletions.size <= MAX_CHATS && deletions.map { it.chatId }.distinct().size == deletions.size)
            deletions.forEach(::validate)
        }.isSuccess

    fun validate(deletion: ChatDeletionBackup) {
        require(IDENTIFIER.matches(deletion.chatId))
        require(deletion.scope in SCOPES && deletion.originMode in ORIGIN_MODES && deletion.phase in PHASES)
        require(HASH.matches(deletion.originHash) && HASH.matches(deletion.tombstoneHash))
        require(deletion.cleanupAttempts in 0..MAX_ATTEMPTS && (deletion.lastError?.length ?: 0) <= MAX_ERROR_CHARS)
        val bytes = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
        require(bytes.isNotEmpty() && bytes.size <= MAX_TOMBSTONE_BYTES)
        require(java.util.Base64.getEncoder().encodeToString(bytes) == deletion.tombstoneStoredBytesBase64)
        val hash = Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_CHARS)
        require(hash == deletion.tombstoneHash)
    }

    const val MAX_CHATS = 1000
    const val MAX_TOMBSTONE_BYTES = 64 * 1024

    private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,96}")
    private val HASH = Regex("[a-z2-7]{32}")
    private val SCOPES = setOf("GROUP", "DM")
    private val ORIGIN_MODES = setOf("ORIGIN_HASH", "LEGACY_CHAT_ID")
    val PHASES = setOf("LOCAL_COMMITTED", "TOMBSTONE_PUBLISHED", "CLEANUP_PENDING", "COMPLETE")
    const val MAX_ATTEMPTS = 1_000_000
    const val MAX_ERROR_CHARS = 1024
    private const val HASH_CHARS = 32
}
