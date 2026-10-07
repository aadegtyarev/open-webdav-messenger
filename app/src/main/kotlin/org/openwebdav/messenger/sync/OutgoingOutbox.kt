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
        communityId: String,
        senderIdentifier: String,
    ): Boolean = deliverOne(messageId, communityId, senderIdentifier)?.complete ?: false

    suspend fun retryChats(
        chatIds: Set<String>,
        communityId: String,
        senderIdentifier: String,
    ) {
        store.pendingOutgoing(communityId)
            .filter { it.chatId in chatIds }
            .forEach { deliverOne(it.messageId, communityId, senderIdentifier) }
    }

    private suspend fun deliverOne(
        messageId: String,
        communityId: String,
        senderIdentifier: String,
    ): SendOutcome? {
        val message = store.claimOutgoing(messageId, communityId) ?: return null
        val envelope = message.outboxEnvelope ?: return null
        val recipients = message.outboxRecipients?.split('\n')?.filter(String::isNotBlank).orEmpty()
        val outcome =
            try {
                sendStored(message.copy(outboxEnvelope = envelope, outboxRecipients = recipients.joinToString("\n")), senderIdentifier)
            } catch (_: Exception) {
                SendOutcome(false, 0, recipients.size)
            }
        if (outcome.complete) {
            store.markSent(message.messageId, communityId)
        } else {
            store.markFailed(message.messageId, communityId)
        }
        return outcome
    }
}
