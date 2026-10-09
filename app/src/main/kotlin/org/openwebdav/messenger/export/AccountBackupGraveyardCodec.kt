package org.openwebdav.messenger.export

import org.openwebdav.messenger.protocol.Base32
import java.io.DataInput
import java.io.DataOutput
import java.security.MessageDigest

internal object AccountBackupGraveyardCodec {
    fun isValid(deletions: List<ChatDeletionBackup>): Boolean =
        runCatching {
            require(deletions.size <= MAX_CHATS && deletions.map { it.chatId }.distinct().size == deletions.size)
            deletions.forEach { deletion ->
                require(IDENTIFIER.matches(deletion.chatId) && deletion.scope in SCOPES && deletion.phase in PHASES)
                require(HASH.matches(deletion.originHash) && HASH.matches(deletion.tombstoneHash))
                require(deletion.cleanupAttempts in 0..MAX_ATTEMPTS && (deletion.lastError?.length ?: 0) <= MAX_ERROR_CHARS)
                val bytes = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
                require(bytes.isNotEmpty() && bytes.size <= MAX_TOMBSTONE_BYTES)
                require(java.util.Base64.getEncoder().encodeToString(bytes) == deletion.tombstoneStoredBytesBase64)
                val hash = Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_CHARS)
                require(hash == deletion.tombstoneHash)
            }
        }.isSuccess

    fun write(
        output: DataOutput,
        deletions: List<ChatDeletionBackup>,
    ) {
        val ordered = deletions.sortedBy { it.chatId }
        require(isValid(ordered))
        output.writeInt(ordered.size)
        ordered.forEach { deletion ->
            require(IDENTIFIER.matches(deletion.chatId) && deletion.scope in SCOPES && deletion.phase in PHASES)
            require(HASH.matches(deletion.originHash) && HASH.matches(deletion.tombstoneHash))
            require(deletion.cleanupAttempts in 0..MAX_ATTEMPTS && (deletion.lastError?.length ?: 0) <= MAX_ERROR_CHARS)
            val raw = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
            require(raw.isNotEmpty() && raw.size <= MAX_TOMBSTONE_BYTES)
            require(java.util.Base64.getEncoder().encodeToString(raw) == deletion.tombstoneStoredBytesBase64)
            output.writeUTF(deletion.chatId)
            output.writeUTF(deletion.scope)
            output.writeUTF(deletion.originHash)
            output.writeUTF(deletion.tombstoneHash)
            output.writeInt(raw.size)
            output.write(raw)
            output.writeUTF(deletion.phase)
            output.writeInt(deletion.cleanupAttempts)
            output.writeBoolean(deletion.nextAttemptAtMillis != null)
            deletion.nextAttemptAtMillis?.let(output::writeLong)
            output.writeBoolean(deletion.lastError != null)
            deletion.lastError?.let(output::writeUTF)
            output.writeLong(deletion.updatedAtMillis)
        }
    }

    fun read(input: DataInput): List<ChatDeletionBackup> {
        val count = input.readInt().also { require(it in 0..MAX_CHATS) }
        val seen = mutableSetOf<String>()
        val result =
            List(count) {
                val chatId = input.readUTF().also { require(IDENTIFIER.matches(it) && seen.add(it)) }
                val scope = input.readUTF().also { require(it in SCOPES) }
                val originHash = input.readUTF().also { require(HASH.matches(it)) }
                val tombstoneHash = input.readUTF().also { require(HASH.matches(it)) }
                val size = input.readInt().also { require(it in 1..MAX_TOMBSTONE_BYTES) }
                val bytes = ByteArray(size).also { input.readFully(it) }
                val phase = input.readUTF().also { require(it in PHASES) }
                val attempts = input.readInt().also { require(it in 0..MAX_ATTEMPTS) }
                val nextAttempt = if (input.readBoolean()) input.readLong() else null
                val error = if (input.readBoolean()) input.readUTF().also { require(it.length <= MAX_ERROR_CHARS) } else null
                val updated = input.readLong()
                ChatDeletionBackup(
                    chatId,
                    scope,
                    originHash,
                    tombstoneHash,
                    java.util.Base64.getEncoder().encodeToString(bytes),
                    phase,
                    attempts,
                    nextAttempt,
                    error,
                    updated,
                )
            }
        require(isValid(result))
        return result
    }

    private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,96}")
    private val HASH = Regex("[a-z2-7]{32}")
    private val SCOPES = setOf("GROUP", "DM")
    private val PHASES = setOf("LOCAL_COMMITTED", "TOMBSTONE_PUBLISHED", "CLEANUP_PENDING", "COMPLETE")
    private const val MAX_CHATS = 1000
    private const val MAX_ATTEMPTS = 1_000_000
    private const val MAX_ERROR_CHARS = 1024
    private const val MAX_TOMBSTONE_BYTES = 64 * 1024
    private const val HASH_CHARS = 32
}
