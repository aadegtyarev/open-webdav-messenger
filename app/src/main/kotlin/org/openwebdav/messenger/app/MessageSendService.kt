package org.openwebdav.messenger.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.message.TextMessage
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken

/**
 * The send entry point the chat feed calls (`ui-chat-surface` plan → Contracts: "A send entry point").
 * It composes the existing engine seams — it does NOT re-implement send: build a [TextMessage] → sign +
 * AEAD-seal via the graph's `MessageEnvelope` under the chat key → mint the §4 order-token + §2 content
 * name → `SyncEngine.send(chatId, orderToken, bytes, allMembers=[self], self)` → persist the local echo so
 * the sender's own message appears immediately.
 *
 * The roster is `[self]` in this slice (no directory yet), so `send` writes only the shared `log/` copy and
 * no change-notes; peers pick the message up via the full-log poll. The local-echo persist uses the SAME
 * §2 message-id the poll will later see, so a later re-fetch dedups to one feed row (idempotent on the
 * message-id PK — plan interaction `send_then_background_poll_dedups_to_one_row`).
 *
 * All work is off the UI thread on [ioDispatcher] (network + AEAD + Room — stack-notes Kotlin/Compose).
 */
internal interface ChatMessageSender {
    suspend fun send(
        text: String,
        onRecoverablyPersisted: suspend () -> Unit = {},
    ): MessageSendService.SendResult

    suspend fun retry(messageId: String): Boolean
}

internal class MessageSendService(
    private val graph: RuntimeGraph,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCurrent: () -> Boolean = { true },
) : ChatMessageSender {
    /** Send [text] in the joined chat and invoke [onRecoverablyPersisted] once its local echo is durable. */
    override suspend fun send(
        text: String,
        onRecoverablyPersisted: suspend () -> Unit,
    ): SendResult =
        AccountMutationBarrier.process.withExclusive {
            sendExclusive(text, onRecoverablyPersisted)
        }

    private suspend fun sendExclusive(
        text: String,
        onRecoverablyPersisted: suspend () -> Unit,
    ): SendResult {
        check(isCurrent() && !graph.isDeletionFenced() && !graph.store.isOutgoingBlocked(graph.chatId)) {
            "Chat or account changed before send"
        }
        val recipients =
            (graph.recipientSnapshot() as? RecipientReadiness.Ready)?.members
                ?: error("Verified recipients are not ready")
        var persistedMessageId: String? = null
        try {
            val prepared =
                withContext(ioDispatcher) {
                    val now = clock()
                    val message =
                        TextMessage(
                            chatId = graph.chatId,
                            sender = graph.identity.publicIdentity(),
                            replyTo = null,
                            body = text,
                            sendTimestampMillis = now,
                        )
                    val signSecret = graph.identity.copySignSecret()
                    val envelopeBytes =
                        try {
                            graph.envelope.seal(message, graph.chatKey, signSecret)
                        } finally {
                            signSecret.fill(0)
                        }
                    val orderToken = OrderToken.build(now, graph.senderIdentifier, graph.nextSeq())
                    val messageId = MessageId.messageId(orderToken, envelopeBytes)
                    var inserted = false
                    withContext(NonCancellable) {
                        check(isCurrent() && !graph.isDeletionFenced() && !graph.store.isOutgoingBlocked(graph.chatId)) {
                            "Chat was deleted before local send commit"
                        }
                        inserted =
                            graph.store.persist(
                                messageId = messageId,
                                orderToken = orderToken,
                                message = message,
                                receivedAtMillis = now,
                                sendStatus = MessageEntity.STATUS_SENDING,
                                outboxEnvelope = envelopeBytes,
                                outboxRecipients = recipients.filter { it != graph.senderIdentifier },
                                outboxCommunityId = graph.communityId,
                            )
                        if (inserted) persistedMessageId = messageId
                    }
                    check(inserted) { "New outgoing message ID already exists" }
                    PreparedSend(orderToken, messageId, envelopeBytes, recipients)
                }
            onRecoverablyPersisted()
            check(isCurrent() && !graph.isDeletionFenced() && !graph.store.isOutgoingBlocked(graph.chatId)) {
                "Chat or account changed before delivery"
            }
            return withContext(ioDispatcher) {
                val outcome =
                    try {
                        graph.engine.send(
                            graph.chatId,
                            prepared.orderToken,
                            prepared.envelopeBytes,
                            allMembers = prepared.recipients,
                            senderIdentifier = graph.senderIdentifier,
                            onLogWritten = {
                                graph.store.markLogWritten(prepared.messageId, graph.communityId)
                            },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        org.openwebdav.messenger.sync.SendOutcome(false, 0, prepared.recipients.size)
                    }
                if (graph.isDeletionFenced() || graph.store.isOutgoingBlocked(graph.chatId)) {
                    return@withContext SendResult(prepared.messageId, logWritten = false, complete = false)
                }
                when {
                    outcome.complete -> graph.store.markSent(prepared.messageId, graph.communityId)
                    outcome.logWritten -> graph.store.markLogWritten(prepared.messageId, graph.communityId)
                    else -> graph.store.markFailed(prepared.messageId, graph.communityId)
                }
                SendResult(messageId = prepared.messageId, logWritten = outcome.logWritten, complete = outcome.logWritten)
            }
        } catch (cancelled: CancellationException) {
            persistedMessageId?.let { messageId ->
                withContext(NonCancellable + ioDispatcher) {
                    graph.store.markFailed(messageId, graph.communityId)
                }
            }
            throw cancelled
        }
    }

    private data class PreparedSend(
        val orderToken: String,
        val messageId: String,
        val envelopeBytes: ByteArray,
        val recipients: List<String>,
    )

    override suspend fun retry(messageId: String): Boolean =
        AccountMutationBarrier.process.withExclusive {
            if (!isCurrent() || graph.isDeletionFenced() || graph.store.isOutgoingBlocked(graph.chatId)) return@withExclusive false
            withContext(ioDispatcher) {
                if (!isCurrent() || graph.isDeletionFenced() || graph.store.isOutgoingBlocked(graph.chatId)) return@withContext false
                graph.engine.retryOutgoing(messageId, graph.communityId, graph.senderIdentifier)
            }
        }

    /**
     * @property messageId the §2 id of the sent message (the local echo row's key; the dedup key on poll).
     * @property logWritten whether the shared-`log/` write landed; successful log append is the Sent boundary,
     *   independent of pending change-index notifications. A failed or uncertain append remains retryable.
     */
    data class SendResult(val messageId: String, val logWritten: Boolean, val complete: Boolean = logWritten)
}
