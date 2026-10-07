package org.openwebdav.messenger.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
        "UPDATE messages SET sendStatus = 'SENT', outboxEnvelope = NULL, outboxRecipients = NULL " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxEnvelope IS NOT NULL",
    )
    suspend fun finishOutgoing(
        messageId: String,
        communityId: String,
    ): Int

    @Query(
        "UPDATE messages SET sendStatus = 'FAILED' " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxEnvelope IS NOT NULL",
    )
    suspend fun failOutgoing(
        messageId: String,
        communityId: String,
    ): Int

    @Query(
        "UPDATE messages SET sendStatus = 'SENDING' " +
            "WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'FAILED' AND outboxEnvelope IS NOT NULL",
    )
    suspend fun claimOutgoing(
        messageId: String,
        communityId: String,
    ): Int

    @Query(
        "SELECT * FROM messages WHERE communityId = :communityId AND messageId = :messageId AND outboxCommunityId = :communityId " +
            "AND sendStatus = 'SENDING' AND outboxEnvelope IS NOT NULL",
    )
    suspend fun claimedOutgoing(
        messageId: String,
        communityId: String,
    ): MessageEntity?

    @Query(
        "UPDATE messages SET sendStatus = 'FAILED' WHERE sendStatus = 'SENDING' " +
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
}
