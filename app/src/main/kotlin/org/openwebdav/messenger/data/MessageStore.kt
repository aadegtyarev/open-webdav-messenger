package org.openwebdav.messenger.data

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import org.openwebdav.messenger.message.Message
import org.openwebdav.messenger.message.ReactionMessage
import org.openwebdav.messenger.message.TextMessage
import org.openwebdav.messenger.protocol.Hex

/**
 * The persistence seam the `sync/` orchestrator calls — it owns ALL Room access so `sync/` holds no
 * SQL (arch note Variant A: `data/` owns persistence, `sync/` calls it). Maps a typed [Message] plus
 * its §2 coordinates (message-id, order-token) to a [MessageEntity] and persists with idempotent
 * dedup; reads/advances the per-chat cursor (`docs/protocol/webdav-layout.md` §9.3).
 *
 * All methods are `suspend` (off the main thread, stack-notes Room).
 */
class MessageStore(
    private val messageDao: MessageDao,
    private val cursorDao: SyncCursorDao,
) {
    /**
     * Persist a received/sent [message] under its §2 [messageId] and §4 [orderToken]. Idempotent on
     * the message-id: a second persist of the same id is a no-op (§9.3 step 3). Returns `true` if a
     * new row was inserted, `false` if it was a duplicate (the dedup signal the caller counts).
     */
    suspend fun persist(
        messageId: String,
        orderToken: String,
        message: Message,
        receivedAtMillis: Long,
    ): Boolean = messageDao.insertIgnore(toEntity(messageId, orderToken, message, receivedAtMillis)) != DEDUP_NO_ROW

    /** The stored cursor order-token for [chatId], or `""` (start of window) if none recorded yet (§9.3). */
    suspend fun cursorFor(chatId: String): String = cursorDao.cursorFor(chatId)?.orderToken ?: ""

    /**
     * Advance the stored cursor for [chatId] to [orderToken] — called ONLY after the entries up to
     * this coordinate were fetched-and-persisted (the §9.3 cursor-advance invariant; the no-skip
     * guarantee under 429/Doze). Never moves the cursor backwards.
     */
    suspend fun advanceCursor(
        chatId: String,
        orderToken: String,
    ) {
        val current = cursorFor(chatId)
        if (orderToken > current) {
            cursorDao.upsert(SyncCursorEntity(chatId, orderToken))
        }
    }

    /** Observable, ordered history for a chat (offline, off-main-thread, §6). */
    fun observeChat(chatId: String): Flow<List<MessageEntity>> = messageDao.observeChat(chatId)

    /** Paged history for the future UI (Paging 3, ordered by order-token). */
    fun pagedChat(chatId: String): PagingSource<Int, MessageEntity> = messageDao.pagedChat(chatId)

    /** One-shot ordered read (tests / non-observable callers). */
    suspend fun messagesForChat(chatId: String): List<MessageEntity> = messageDao.messagesForChat(chatId)

    private fun toEntity(
        messageId: String,
        orderToken: String,
        message: Message,
        receivedAtMillis: Long,
    ): MessageEntity {
        val senderHex = Hex.encode(message.sender.copySignPub())
        return when (message) {
            is TextMessage ->
                MessageEntity(
                    messageId = messageId,
                    chatId = message.chatId,
                    orderToken = orderToken,
                    senderSignPub = senderHex,
                    kind = MessageEntity.KIND_TEXT,
                    body = message.body,
                    replyTo = message.replyTo,
                    targetId = null,
                    reactionIndex = null,
                    sendTimestampMillis = message.sendTimestampMillis,
                    receivedAtMillis = receivedAtMillis,
                )
            is ReactionMessage ->
                MessageEntity(
                    messageId = messageId,
                    chatId = message.chatId,
                    orderToken = orderToken,
                    senderSignPub = senderHex,
                    kind = MessageEntity.KIND_REACTION,
                    body = null,
                    replyTo = null,
                    targetId = message.targetId,
                    reactionIndex = message.reactionIndex,
                    sendTimestampMillis = null,
                    receivedAtMillis = receivedAtMillis,
                )
        }
    }

    private companion object {
        /** `OnConflictStrategy.IGNORE` returns -1 for a conflicting (duplicate) insert. */
        const val DEDUP_NO_ROW = -1L
    }
}
