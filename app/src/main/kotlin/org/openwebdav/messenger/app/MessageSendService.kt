package org.openwebdav.messenger.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
internal class MessageSendService(
    private val graph: RuntimeGraph,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Send [text] in the joined chat: seal, write to the shared log, persist the local echo. */
    suspend fun send(text: String): SendResult =
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
                    signSecret.fill(0) // identity secret never lingers past use
                }
            val orderToken = OrderToken.build(now, graph.senderIdentifier, graph.nextSeq())
            val messageId = MessageId.messageId(orderToken, envelopeBytes)

            // Local echo FIRST — the message appears in chat instantly with SENDING status.
            graph.store.persist(messageId, orderToken, message, now, MessageEntity.STATUS_SENDING)

            val outcome =
                graph.engine.send(
                    graph.chatId,
                    orderToken,
                    envelopeBytes,
                    allMembers = listOf(graph.senderIdentifier),
                    graph.senderIdentifier,
                )

            SendResult(messageId = messageId, logWritten = outcome.logWritten)
        }

    /**
     * @property messageId the §2 id of the sent message (the local echo row's key; the dedup key on poll).
     * @property logWritten whether the shared-`log/` write landed; `false` means kept-locally / will-retry
     *   (the echo row is already persisted, so the message is not lost — plan Scenario 6 offline send).
     */
    data class SendResult(val messageId: String, val logWritten: Boolean)
}
