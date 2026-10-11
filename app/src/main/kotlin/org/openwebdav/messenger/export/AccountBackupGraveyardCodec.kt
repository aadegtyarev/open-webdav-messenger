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
            writeEvidence(output, deletion.tombstonePlaintextBytesBase64)
            writeEvidence(output, deletion.originPlaintextBytesBase64)
            output.writeUTF(deletion.phase)
            output.writeInt(deletion.cleanupAttempts)
            output.writeBoolean(deletion.nextAttemptAtMillis != null)
            deletion.nextAttemptAtMillis?.let(output::writeLong)
            output.writeBoolean(deletion.lastError != null)
            deletion.lastError?.let(output::writeUTF)
            output.writeLong(deletion.updatedAtMillis)
        }
    }

    fun read(
        input: DataInput,
        includeEvidence: Boolean,
    ): List<ChatDeletionBackup> {
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
                val tombstoneEvidence = if (includeEvidence) readEvidence(input) else null
                val originEvidence = if (includeEvidence) readEvidence(input) else null
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
                    tombstoneEvidence,
                    originEvidence,
                )
            }
        require(AccountBackupGraveyardValidation.isValid(result))
        return result
    }

    private fun writeEvidence(
        output: DataOutput,
        encoded: String?,
    ) {
        output.writeBoolean(encoded != null)
        if (encoded != null) {
            val bytes = java.util.Base64.getDecoder().decode(encoded)
            require(bytes.size in 1..AccountBackupGraveyardValidation.MAX_TOMBSTONE_BYTES)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }

    private fun readEvidence(input: DataInput): String? =
        if (!input.readBoolean()) {
            null
        } else {
            val size = input.readInt().also { require(it in 1..AccountBackupGraveyardValidation.MAX_TOMBSTONE_BYTES) }
            val bytes = ByteArray(size).also { input.readFully(it) }
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
}
