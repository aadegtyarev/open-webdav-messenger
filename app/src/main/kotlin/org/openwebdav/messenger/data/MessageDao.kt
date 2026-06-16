package org.openwebdav.messenger.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the local message history (`docs/features/sync_plan.md` → Local history).
 *
 * All access is `suspend` (writes) or `Flow`/`PagingSource` (observable reads) — **never** a blocking
 * main-thread query, and the database is built without `allowMainThreadQueries()` (stack-notes Room:
 * "Room does not allow database access on the main thread"). Unbounded history is exposed as a Paging 3
 * [PagingSource] rather than a whole-chat load (stack-notes Room: page an unbounded message query).
 *
 * Insert dedup is by the §2 message-id primary key: [insertIgnore] uses `OnConflictStrategy.IGNORE`,
 * so re-inserting the same message-id across two poll cycles is an idempotent no-op — exactly one row
 * (`docs/protocol/webdav-layout.md` §9.3 step 3 / plan scenario 4).
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
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY orderToken ASC")
    fun observeChat(chatId: String): Flow<List<MessageEntity>>

    /** Paged history for the future UI (Paging 3) — ordered by the §4 order-token, ascending. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY orderToken ASC")
    fun pagedChat(chatId: String): PagingSource<Int, MessageEntity>

    /** Whether a row with [messageId] already exists (dedup probe / tests). */
    @Query("SELECT COUNT(*) FROM messages WHERE messageId = :messageId")
    suspend fun count(messageId: String): Int

    /** All rows for a chat, ordered — for one-shot reads and tests (not the observable path). */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY orderToken ASC")
    suspend fun messagesForChat(chatId: String): List<MessageEntity>

    /** Update the sendStatus of a single message (e.g. SENDING → SENT or FAILED). */
    @Query("UPDATE messages SET sendStatus = :status WHERE messageId = :messageId")
    suspend fun updateSendStatus(
        messageId: String,
        status: String,
    )

    /** Mark all non-SENDING messages up to [orderToken] as READ. */
    @Query(
        "UPDATE messages SET sendStatus = 'READ' " +
            "WHERE chatId = :chatId AND orderToken <= :orderToken " +
            "AND sendStatus = 'SENT'",
    )
    suspend fun markReadUpTo(
        chatId: String,
        orderToken: String,
    )
}
