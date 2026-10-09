package org.openwebdav.messenger.export

import java.io.DataInput
import java.io.DataOutput

internal object AccountBackupGraveyardCodec {
    fun write(
        output: DataOutput,
        deletions: List<ChatDeletionBackup>,
    ) {
        val ordered = deletions.sortedBy { it.chatId }
        require(AccountBackupGraveyardValidation.isValid(ordered))
        output.writeInt(ordered.size)
        ordered.forEach { deletion ->
            val raw = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
            output.writeUTF(deletion.chatId)
            output.writeUTF(deletion.scope)
            output.writeUTF(deletion.originMode)
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
        val count = input.readInt().also { require(it in 0..AccountBackupGraveyardValidation.MAX_CHATS) }
        val seen = mutableSetOf<String>()
        val result =
            List(count) {
                val chatId = input.readUTF().also { require(seen.add(it)) }
                val scope = input.readUTF()
                val originMode = input.readUTF()
                val originHash = input.readUTF()
                val tombstoneHash = input.readUTF()
                val size = input.readInt().also { require(it in 1..AccountBackupGraveyardValidation.MAX_TOMBSTONE_BYTES) }
                val bytes = ByteArray(size).also { input.readFully(it) }
                val phase = input.readUTF().also { require(it in AccountBackupGraveyardValidation.PHASES) }
                val attempts = input.readInt().also { require(it in 0..AccountBackupGraveyardValidation.MAX_ATTEMPTS) }
                val nextAttempt = if (input.readBoolean()) input.readLong() else null
                val error =
                    if (input.readBoolean()) {
                        input.readUTF().also {
                            require(it.length <= AccountBackupGraveyardValidation.MAX_ERROR_CHARS)
                        }
                    } else {
                        null
                    }
                val updated = input.readLong()
                ChatDeletionBackup(
                    chatId,
                    scope,
                    originMode,
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
        require(AccountBackupGraveyardValidation.isValid(result))
        return result
    }
}
