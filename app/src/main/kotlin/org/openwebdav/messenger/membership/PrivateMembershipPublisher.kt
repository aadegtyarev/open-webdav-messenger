package org.openwebdav.messenger.membership

import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity

/** Durable-before-network claim publication; retries reuse the exact ciphertext and never imply acknowledgment. */
internal class PrivateMembershipPublisher(
    private val store: PendingPrivateClaimStore,
    private val claims: PrivateMembershipClaimCrypto,
) {
    suspend fun publish(
        access: ChatAccess,
        communityId: String,
        chatId: String,
        displayName: String,
        identity: Identity,
        chatKey: ChatKey,
        service: PrivateMembershipService,
    ): PrivateClaimPublicationStatus {
        if (access != ChatAccess.PRIVATE) return PrivateClaimPublicationStatus.NOT_PRIVATE
        val prior = runCatching { store.load(communityId, chatId, PRIVATE_KIND, chatKey, identity) }.getOrNull()
        val pending =
            prior ?: run {
                val bytes =
                    runCatching { claims.seal(chatId, displayName, identity, chatKey) }.getOrNull()
                        ?: return PrivateClaimPublicationStatus.PENDING
                try {
                    store.savePending(communityId, chatId, PRIVATE_KIND, chatKey, identity, bytes)
                } catch (_: Exception) {
                    bytes.fill(0)
                    return PrivateClaimPublicationStatus.PENDING
                }
                PendingPrivateClaim(bytes, false)
            }
        val uploadedBefore = pending.uploaded
        val result = runCatching { service.publishSelf(pending.fileBytes, chatId) }.getOrNull()
        if (result == MembershipPublishOutcome.Published) {
            runCatching { store.markUploaded(communityId, chatId, PRIVATE_KIND, chatKey, identity, pending.fileBytes) }
            return PrivateClaimPublicationStatus.UPLOADED
        }
        return if (uploadedBefore) PrivateClaimPublicationStatus.UPLOADED else PrivateClaimPublicationStatus.PENDING
    }

    private companion object {
        const val PRIVATE_KIND = "private"
    }
}
