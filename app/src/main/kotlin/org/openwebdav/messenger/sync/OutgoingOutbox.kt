package org.openwebdav.messenger.sync

import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.data.MessageStore

/** Retries persisted outgoing envelopes without minting a new message identity. */
internal class OutgoingOutbox(
    private val store: MessageStore,
    private val sendStored: suspend (MessageEntity, String) -> SendOutcome,
) {
    suspend fun retry(
        messageId: String,
        senderIdentifier: String,
    ): Boolean {
        val pending = store.pendingOutgoing().firstOrNull { it.messageId == messageId } ?: return false
        return deliverOne(pending, senderIdentifier).complete
    }

    suspend fun retryChats(
        chatIds: Set<String>,
        senderIdentifier: String,
    ) {
        store.pendingOutgoing()
            .filter { it.chatId in chatIds }
            .forEach { deliverOne(it, senderIdentifier) }
    }

    private suspend fun deliverOne(
        message: MessageEntity,
        senderIdentifier: String,
    ): SendOutcome {
        val envelope = message.outboxEnvelope ?: return SendOutcome(false, 0, 0)
        val recipients = message.outboxRecipients?.split('\n')?.filter(String::isNotBlank).orEmpty()
        store.markSending(message.messageId)
        val outcome =
            try {
                sendStored(message.copy(outboxEnvelope = envelope, outboxRecipients = recipients.joinToString("\n")), senderIdentifier)
            } catch (_: Exception) {
                SendOutcome(false, 0, recipients.size)
            }
        if (outcome.complete) store.markSent(message.messageId) else store.markFailed(message.messageId)
        return outcome
    }
}
