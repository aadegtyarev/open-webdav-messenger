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
 * dedup; reads/advances the per-community/chat cursor (`docs/protocol/webdav-layout.md` §9.3).
 *
 * All methods are `suspend` (off the main thread, stack-notes Room).
 */
class MessageStore(
    private val messageDao: MessageDao,
    private val cursorDao: SyncCursorDao,
    private val communityId: String,
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
        outboxEnvelope: ByteArray? = null,
        outboxRecipients: List<String> = emptyList(),
        outboxCommunityId: String? = null,
    ): Boolean =
        messageDao.insertIgnore(
            toEntity(
                messageId,
                orderToken,
                message,
                receivedAtMillis,
                sendStatus,
                outboxEnvelope,
                outboxRecipients,
                outboxCommunityId,
                communityId,
            ),
        ) != DEDUP_NO_ROW

    /** Mark a locally-sent message as fully delivered and discard its retry payload. */
    suspend fun markSent(
        messageId: String,
        communityId: String,
    ) {
        if (communityId == this.communityId) messageDao.finishOutgoing(messageId, communityId)
    }

    /** Mark a locally-sent message for later retry without discarding its original operation. */
    suspend fun markFailed(
        messageId: String,
        communityId: String,
    ) {
        if (communityId == this.communityId) messageDao.failOutgoing(messageId, communityId)
    }

    suspend fun claimOutgoing(
        messageId: String,
        communityId: String,
    ): MessageEntity? {
        if (communityId != this.communityId) return null
        if (messageDao.claimOutgoing(messageId, communityId) != 1) return null
        return messageDao.claimedOutgoing(messageId, communityId)
    }

    suspend fun pendingOutgoing(communityId: String): List<MessageEntity> =
        if (communityId == this.communityId) messageDao.pendingOutgoing(communityId) else emptyList()

    suspend fun recoverInterruptedOutgoing(communityId: String) {
        messageDao.recoverInterruptedOutgoing(communityId)
    }

    /** Mark all messages in [chatId] up to [orderToken] as READ (for received messages viewed by the user). */
    suspend fun markMessagesReadUpTo(
        chatId: String,
        orderToken: String,
    ) = messageDao.markReadUpTo(communityId, chatId, orderToken)

    /** The stored cursor order-token for [chatId], or `""` (start of window) if none recorded yet (§9.3). */
    suspend fun cursorFor(chatId: String): String = cursorDao.cursorFor(communityId, chatId)?.orderToken ?: ""

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
            cursorDao.upsert(SyncCursorEntity(communityId, chatId, orderToken))
        }
    }

    /** Observable, ordered history for a chat (offline, off-main-thread, §6). */
    fun observeChat(chatId: String): Flow<List<MessageEntity>> = messageDao.observeChat(communityId, chatId)

    /** Paged history for the future UI (Paging 3, ordered by order-token). */
    fun pagedChat(chatId: String): PagingSource<Int, MessageEntity> = messageDao.pagedChat(communityId, chatId)

    /** One-shot ordered read (tests / non-observable callers). */
    suspend fun messagesForChat(chatId: String): List<MessageEntity> = messageDao.messagesForChat(communityId, chatId)

    /** Observable count of unread messages (sendStatus = 'SENT') for a chat. */
    fun observeUnreadCount(chatId: String): Flow<Int> = messageDao.observeUnreadCount(communityId, chatId)

    private fun toEntity(
        messageId: String,
        orderToken: String,
        message: Message,
        receivedAtMillis: Long,
        sendStatus: String,
        outboxEnvelope: ByteArray?,
        outboxRecipients: List<String>,
        outboxCommunityId: String?,
        communityId: String,
    ): MessageEntity {
        val senderHex = Hex.encode(message.sender.copySignPub())
        return when (message) {
            is TextMessage ->
                MessageEntity(
                    communityId = communityId,
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
                    outboxEnvelope = outboxEnvelope,
                    outboxRecipients = outboxRecipients.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    outboxCommunityId = outboxCommunityId,
                )
            is ReactionMessage ->
                MessageEntity(
                    communityId = communityId,
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
