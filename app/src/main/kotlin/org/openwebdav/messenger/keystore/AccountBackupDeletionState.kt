package org.openwebdav.messenger.keystore

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.openwebdav.messenger.data.ChatDeletionEntity
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.data.SuppressedDmMessageEntity
import org.openwebdav.messenger.export.ChatDeletionBackup

internal data class DmDeletionSnapshot(
    val hiddenChatIds: List<String>,
    val suppressedMessageIds: Map<String, List<String>>,
    val chatDeletions: List<ChatDeletionEntity>,
)

internal class AccountBackupDeletionState(private val context: Context) {
    fun snapshot(communityId: String): DmDeletionSnapshot {
        if (!databaseFileExists()) return DmDeletionSnapshot(emptyList(), emptyMap(), emptyList())
        val db = MessengerDatabase.get(context)
        return runBlocking {
            db.withTransaction {
                val dao = db.messageDao()
                val hidden = dao.hiddenDmsForCommunity(communityId).map { it.chatId }
                val suppressed =
                    dao.suppressedDmMessagesForCommunity(communityId)
                        .groupBy({ it.chatId }, { it.messageId })
                DmDeletionSnapshot(hidden, suppressed, dao.chatDeletionsForCommunity(communityId))
            }
        }
    }

    fun replace(
        communityId: String,
        hiddenChatIds: List<String>,
        suppressedMessageIds: Map<String, List<String>>,
        chatDeletions: List<ChatDeletionBackup>,
    ) {
        if (!databaseFileExists() && hiddenChatIds.isEmpty() && suppressedMessageIds.isEmpty() && chatDeletions.isEmpty()) return
        val db = MessengerDatabase.get(context)
        runBlocking {
            db.messageDao().replaceDmDeletionState(
                communityId,
                hiddenChatIds,
                suppressedMessageIds.flatMap { (chatId, ids) ->
                    ids.map { messageId -> SuppressedDmMessageEntity(communityId, chatId, messageId) }
                },
                chatDeletions.map { deletion ->
                    ChatDeletionEntity(
                        communityId = communityId,
                        chatId = deletion.chatId,
                        scope = deletion.scope,
                        originHash = deletion.originHash,
                        tombstoneHash = deletion.tombstoneHash,
                        tombstoneStoredBytes = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64),
                        phase = deletion.phase,
                        cleanupAttempts = deletion.cleanupAttempts,
                        nextAttemptAtMillis = deletion.nextAttemptAtMillis,
                        lastError = deletion.lastError,
                        updatedAtMillis = deletion.updatedAtMillis,
                    )
                },
            )
        }
    }

    // Deletion graveyards are intentionally monotonic across account replacement and old backups.
    fun clear(communityIds: Set<String>) {
        if (communityIds.isEmpty() || !databaseFileExists()) return
        val db = MessengerDatabase.get(context)
        runBlocking {
            db.withTransaction {
                communityIds.forEach { id ->
                    db.messageDao().deleteHiddenDmsForCommunity(id)
                    db.messageDao().deleteSuppressedDmsForCommunity(id)
                }
            }
        }
    }

    private fun databaseFileExists(): Boolean = context.getDatabasePath(MessengerDatabase.DB_NAME).exists()
}
