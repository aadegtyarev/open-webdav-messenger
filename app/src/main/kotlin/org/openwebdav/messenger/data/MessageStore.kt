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
     * Persist a received/sent [message] under its §2 [messageId] and §4 [orderToken], with
     * [sendStatus] (SENT for received, SENDING for local echo). Idempotent on the message-id.
     */
    suspend fun persist(
        messageId: String,
        orderToken: String,
        message: Message,
        receivedAtMillis: Long,
        sendStatus: String = MessageEntity.STATUS_SENT,
    ): Boolean = messageDao.insertIgnore(toEntity(messageId, orderToken, message, receivedAtMillis, sendStatus)) != DEDUP_NO_ROW

    /** Mark a locally-sent message as successfully written to the disk. */
    suspend fun markSent(messageId: String) = messageDao.updateSendStatus(messageId, MessageEntity.STATUS_SENT)

    /** Mark a locally-sent message as failed to reach the disk. */
    suspend fun markFailed(messageId: String) = messageDao.updateSendStatus(messageId, MessageEntity.STATUS_FAILED)

    /** Mark all messages in [chatId] up to [orderToken] as READ (for received messages viewed by the user). */
    suspend fun markMessagesReadUpTo(
        chatId: String,
        orderToken: String,
    ) = messageDao.markReadUpTo(chatId, orderToken)

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
        sendStatus: String,
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
                    sendStatus = sendStatus,
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
                    sendStatus = sendStatus,
                )
        }
    }

    private companion object {
        /** `OnConflictStrategy.IGNORE` returns -1 for a conflicting (duplicate) insert. */
        const val DEDUP_NO_ROW = -1L
    }
}
