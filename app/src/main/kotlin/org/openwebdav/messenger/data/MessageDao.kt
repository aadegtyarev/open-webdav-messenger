package org.openwebdav.messenger.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the local message history (`docs/protocol/webdav-layout.md` §9.3).
 *
 * All access is `suspend` (writes) or `Flow`/`PagingSource` (observable reads) — **never** a blocking
 * main-thread query, and the database is built without `allowMainThreadQueries()` (stack-notes Room:
 * "Room does not allow database access on the main thread"). Unbounded history is exposed as a Paging 3
 * [PagingSource] rather than a whole-chat load (stack-notes Room: page an unbounded message query).
 *
 * Insert dedup is by (local community, §2 message-id): [insertIgnore] uses `OnConflictStrategy.IGNORE`,
 * so re-inserting an ID within one community is an idempotent no-op without merging other roots.
 */
@Dao
interface MessageDao {
    /**
     * Insert a message, ignoring a duplicate §2 message-id (idempotent dedup, §9.3 step 3).
     * Returns the inserted row id, or `-1` when the row already existed (the dedup signal).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSuppressed(rows: List<SuppressedDmMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChatDeletion(deletion: ChatDeletionEntity): Long

    @Query("SELECT * FROM chat_deletions WHERE communityId = :communityId AND chatId = :chatId")
    suspend fun chatDeletion(
        communityId: String,
        chatId: String,
    ): ChatDeletionEntity?

    @Query("SELECT * FROM chat_deletions WHERE communityId = :communityId")
    suspend fun chatDeletionsForCommunity(communityId: String): List<ChatDeletionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM chat_deletions WHERE communityId = :communityId AND chatId = :chatId)")
    suspend fun isChatDeleted(
        communityId: String,
        chatId: String,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHiddenDm(hidden: HiddenDmEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHiddenDms(hidden: List<HiddenDmEntity>)

    @Query("SELECT * FROM hidden_dms")
    fun observeHiddenDms(): Flow<List<HiddenDmEntity>>

    @Query("SELECT * FROM chat_deletions")
    fun observeChatDeletions(): Flow<List<ChatDeletionEntity>>

    @Query("SELECT * FROM hidden_dms WHERE communityId = :communityId")
    suspend fun hiddenDmsForCommunity(communityId: String): List<HiddenDmEntity>

    @Query("SELECT * FROM suppressed_dm_messages WHERE communityId = :communityId")
    suspend fun suppressedDmMessagesForCommunity(communityId: String): List<SuppressedDmMessageEntity>

    @Query("DELETE FROM hidden_dms WHERE communityId = :communityId")
    suspend fun deleteHiddenDmsForCommunity(communityId: String)

    @Query("DELETE FROM suppressed_dm_messages WHERE communityId = :communityId")
    suspend fun deleteSuppressedDmsForCommunity(communityId: String)

    @Transaction
    suspend fun replaceDmDeletionState(
        communityId: String,
        hiddenChatIds: List<String>,
        suppressedIds: List<SuppressedDmMessageEntity>,
        chatDeletions: List<ChatDeletionEntity>,
    ) {
        deleteHiddenDmsForCommunity(communityId)
        deleteSuppressedDmsForCommunity(communityId)
        if (hiddenChatIds.isNotEmpty()) putHiddenDms(hiddenChatIds.map { HiddenDmEntity(communityId, it) })
        if (suppressedIds.isNotEmpty()) insertSuppressed(suppressedIds)
        chatDeletions.filter { it.communityId == communityId }.forEach { incoming ->
            if (chatDeletion(communityId, incoming.chatId) == null) insertChatDeletion(incoming)
        }
        chatDeletionsForCommunity(communityId).forEach { deletion ->
            deleteMessagesForChat(communityId, deletion.chatId)
            deleteCursorForChat(communityId, deletion.chatId)
            clearHiddenDm(communityId, deletion.chatId)
            deleteSuppressedForChat(communityId, deletion.chatId)
        }
    }

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_dms WHERE communityId = :communityId AND chatId = :chatId)")
    suspend fun isDmHidden(
        communityId: String,
        chatId: String,
    ): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM suppressed_dm_messages WHERE communityId = :communityId " +
            "AND chatId = :chatId AND messageId = :messageId)",
    )
    suspend fun isMessageSuppressed(
        communityId: String,
        chatId: String,
        messageId: String,
    ): Boolean

    @Query("SELECT messageId FROM messages WHERE communityId = :communityId AND chatId = :chatId")
    suspend fun messageIdsForChat(
        communityId: String,
        chatId: String,
    ): List<String>

    @Query("SELECT messageId FROM suppressed_dm_messages WHERE communityId = :communityId AND chatId = :chatId")
    suspend fun suppressedIdsForChat(
        communityId: String,
        chatId: String,
    ): List<String>

    @Query("SELECT COUNT(*) FROM suppressed_dm_messages")
    suspend fun suppressionCount(): Int

    @Query("DELETE FROM hidden_dms WHERE communityId = :communityId AND chatId = :chatId")
    suspend fun clearHiddenDm(
        communityId: String,
        chatId: String,
    )

    @Query("DELETE FROM messages WHERE communityId = :communityId AND chatId = :chatId")
    suspend fun deleteMessagesForChat(
        communityId: String,
        chatId: String,
    )

    @Query("DELETE FROM sync_cursors WHERE communityId = :communityId AND chatId = :chatId")
    suspend fun deleteCursorForChat(
        communityId: String,
        chatId: String,
    )

    @Query("DELETE FROM suppressed_dm_messages WHERE communityId = :communityId AND chatId = :chatId")
    suspend fun deleteSuppressedForChat(
        communityId: String,
        chatId: String,
    )

    @Query(
        "UPDATE chat_deletions SET phase = :nextPhase, updatedAtMillis = :updatedAtMillis " +
            "WHERE communityId = :communityId AND chatId = :chatId AND tombstoneHash = :tombstoneHash AND phase = :expectedPhase",
    )
    suspend fun updateDeletionPhase(
        communityId: String,
        chatId: String,
        tombstoneHash: String,
        expectedPhase: String,
        nextPhase: String,
        updatedAtMillis: Long,
    ): Int

    @Transaction
    suspend fun transitionDeletionPhase(
        communityId: String,
        chatId: String,
        tombstoneHash: String,
        expectedPhase: String,
        nextPhase: String,
        updatedAtMillis: Long,
    ): Int {
        val valid =
            when (expectedPhase) {
                ChatDeletionEntity.PHASE_LOCAL_COMMITTED -> nextPhase == ChatDeletionEntity.PHASE_TOMBSTONE_PUBLISHED
                ChatDeletionEntity.PHASE_TOMBSTONE_PUBLISHED -> nextPhase == ChatDeletionEntity.PHASE_CLEANUP_PENDING
                ChatDeletionEntity.PHASE_CLEANUP_PENDING -> nextPhase == ChatDeletionEntity.PHASE_COMPLETE
                else -> false
            }
        if (!valid) return 0
        return updateDeletionPhase(communityId, chatId, tombstoneHash, expectedPhase, nextPhase, updatedAtMillis)
    }

    @Transaction
    suspend fun commitChatDeletion(deletion: ChatDeletionEntity): ChatDeletionCommitResult {
        if (deletion.phase != ChatDeletionEntity.PHASE_LOCAL_COMMITTED) return ChatDeletionCommitResult.Conflict
        val existing = chatDeletion(deletion.communityId, deletion.chatId)
        if (existing != null && !existing.sameTombstone(deletion)) return ChatDeletionCommitResult.Conflict
        if (existing == null && insertChatDeletion(deletion) == -1L) return ChatDeletionCommitResult.Conflict
        deleteMessagesForChat(deletion.communityId, deletion.chatId)
        deleteCursorForChat(deletion.communityId, deletion.chatId)
        clearHiddenDm(deletion.communityId, deletion.chatId)
        deleteSuppressedForChat(deletion.communityId, deletion.chatId)
        return if (existing == null) ChatDeletionCommitResult.Committed else ChatDeletionCommitResult.AlreadyCommitted
    }

    private fun ChatDeletionEntity.sameTombstone(other: ChatDeletionEntity): Boolean =
        scope == other.scope && originHash == other.originHash && tombstoneHash == other.tombstoneHash &&
            tombstoneStoredBytes.contentEquals(other.tombstoneStoredBytes)

    @Transaction
    suspend fun insertUnlessSuppressed(
        message: MessageEntity,
        peerMessage: Boolean,
    ): Long {
        if (isChatDeleted(message.communityId, message.chatId)) return -1L
        if (isMessageSuppressed(message.communityId, message.chatId, message.messageId)) return -1L
        if (isDmHidden(message.communityId, message.chatId)) {
            if (!peerMessage || message.sendStatus == MessageEntity.STATUS_SENDING) return -1L
            clearHiddenDm(message.communityId, message.chatId)
        }
        return insertIgnore(message)
    }

    @Transaction
    suspend fun hideAndPurgeDm(
        communityId: String,
        chatId: String,
    ): DmLocalDeleteResult {
        if (isDmHidden(communityId, chatId)) return DmLocalDeleteResult.AlreadyHidden
        val messageIds = messageIdsForChat(communityId, chatId)
        val priorIds = suppressedIdsForChat(communityId, chatId).toHashSet()
        val additions = messageIds.filterNot(priorIds::contains).distinct()
        if (messageIds.size > MAX_SUPPRESSED_IDS_PER_CHAT || suppressionCount() + additions.size > MAX_SUPPRESSED_IDS_TOTAL) {
            return DmLocalDeleteResult.SuppressionLimitReached
        }
        if (additions.isNotEmpty()) {
            insertSuppressed(additions.map { SuppressedDmMessageEntity(communityId, chatId, it) })
        }
        putHiddenDm(HiddenDmEntity(communityId, chatId))
        deleteMessagesForChat(communityId, chatId)
        deleteCursorForChat(communityId, chatId)
        return DmLocalDeleteResult.Deleted
    }

    /** Observable, ordered-by-order-token history for a chat (offline-readable, §6). */
    @Query("SELECT * FROM messages WHERE communityId = :communityId AND chatId = :chatId ORDER BY orderToken ASC")
    fun observeChat(
        communityId: String,
        chatId: String,
    ): Flow<List<MessageEntity>>

    /** Paged history for the future UI (Paging 3) — ordered by the §4 order-token, ascending. */
    @Query("SELECT * FROM messages WHERE communityId = :communityId AND chatId = :chatId ORDER BY orderToken ASC")
    fun pagedChat(
        communityId: String,
        chatId: String,
    ): PagingSource<Int, MessageEntity>

    /** Whether a row with [messageId] already exists (dedup probe / tests). */
    @Query("SELECT COUNT(*) FROM messages WHERE communityId = :communityId AND messageId = :messageId")
    suspend fun count(
        communityId: String,
        messageId: String,
    ): Int

    /** All rows for a chat, ordered — for one-shot reads and tests (not the observable path). */
    @Query("SELECT * FROM messages WHERE communityId = :communityId AND chatId = :chatId ORDER BY orderToken ASC")
    suspend fun messagesForChat(
        communityId: String,
        chatId: String,
    ): List<MessageEntity>

    @Query(
        "UPDATE messages SET sendStatus = 'SENT', outboxEnvelope = NULL, outboxRecipients = NULL, outboxClaimToken = NULL " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxClaimToken IS NULL AND outboxEnvelope IS NOT NULL",
    )
    suspend fun finishOutgoing(
        messageId: String,
        communityId: String,
    ): Int

    @Query(
        "UPDATE messages SET sendStatus = 'FAILED', outboxClaimToken = NULL " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxClaimToken IS NULL AND outboxEnvelope IS NOT NULL",
    )
    suspend fun failOutgoing(
        messageId: String,
        communityId: String,
    ): Int

    @Query(
        "UPDATE messages SET sendStatus = 'SENDING', outboxClaimToken = :claimToken " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'FAILED' AND outboxEnvelope IS NOT NULL",
    )
    suspend fun claimOutgoing(
        messageId: String,
        communityId: String,
        claimToken: String,
    ): Int

    @Query(
        "SELECT * FROM messages WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxClaimToken = :claimToken AND outboxEnvelope IS NOT NULL",
    )
    suspend fun claimedOutgoing(
        messageId: String,
        communityId: String,
        claimToken: String,
    ): MessageEntity?

    @Query(
        "UPDATE messages SET sendStatus = 'SENT', outboxEnvelope = NULL, outboxRecipients = NULL, outboxClaimToken = NULL " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxClaimToken = :claimToken AND outboxEnvelope IS NOT NULL",
    )
    suspend fun finishOutgoingClaim(
        messageId: String,
        communityId: String,
        claimToken: String,
    ): Int

    @Query(
        "UPDATE messages SET sendStatus = 'FAILED', outboxClaimToken = NULL " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxClaimToken = :claimToken AND outboxEnvelope IS NOT NULL",
    )
    suspend fun failOutgoingClaim(
        messageId: String,
        communityId: String,
        claimToken: String,
    ): Int

    @Query(
        "UPDATE messages SET sendStatus = 'FAILED', outboxClaimToken = NULL WHERE sendStatus = 'SENDING' " +
            "AND communityId = :communityId AND outboxEnvelope IS NOT NULL AND outboxCommunityId = :communityId",
    )
    suspend fun recoverInterruptedOutgoing(communityId: String): Int

    @Query(
        "SELECT * FROM messages WHERE sendStatus = 'FAILED' AND communityId = :communityId AND outboxEnvelope IS NOT NULL " +
            "AND outboxCommunityId = :communityId",
    )
    suspend fun pendingOutgoing(communityId: String): List<MessageEntity>

    /** Mark all non-SENDING messages up to [orderToken] as READ. */
    @Query(
        "UPDATE messages SET sendStatus = 'READ' " +
            "WHERE communityId = :communityId AND chatId = :chatId AND orderToken <= :orderToken " +
            "AND sendStatus = 'SENT'",
    )
    suspend fun markReadUpTo(
        communityId: String,
        chatId: String,
        orderToken: String,
    )

    /** Count unread (SENT status) messages in a chat. Observable. */
    @Query("SELECT COUNT(*) FROM messages WHERE communityId = :communityId AND chatId = :chatId AND sendStatus = 'SENT'")
    fun observeUnreadCount(
        communityId: String,
        chatId: String,
    ): Flow<Int>

    companion object {
        const val MAX_SUPPRESSED_IDS_PER_CHAT = 10_000
        const val MAX_SUPPRESSED_IDS_TOTAL = 100_000
    }
}
