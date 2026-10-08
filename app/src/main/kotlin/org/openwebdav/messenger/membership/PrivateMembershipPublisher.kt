package org.openwebdav.messenger.membership

import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.protocol.Hex

/** Durable-before-network claim publication; retries reuse the exact ciphertext and never imply acknowledgment. */
internal class PrivateMembershipPublisher(
    private val store: PendingPrivateClaimStore,
    private val claims: PrivateMembershipClaimCrypto,
    private val barrier: AccountMutationBarrier = AccountMutationBarrier.process,
) {
    private val locks = PrivateMembershipPublishLock()

    suspend fun publish(
        access: ChatAccess,
        communityId: String,
        chatId: String,
        displayName: String,
        identity: Identity,
        chatKey: ChatKey,
        service: PrivateClaimWriter,
        expectedGeneration: Long = barrier.replacementGeneration(),
        contextCurrent: () -> Boolean = { true },
    ): PrivateClaimPublicationStatus {
        if (access != ChatAccess.PRIVATE) return PrivateClaimPublicationStatus.NOT_PRIVATE
        val owner = Hex.encode(identity.copySignPublic())
        return locks.withLock("$owner\u001f$communityId\u001f$chatId") {
            fun isFresh(): Boolean =
                barrier.replacementGeneration() == expectedGeneration &&
                    runCatching(contextCurrent).getOrDefault(false)

            val pending =
                barrier.withStableAccount {
                    if (!isFresh()) return@withStableAccount null
                    val prior =
                        try {
                            store.load(communityId, chatId, PRIVATE_KIND, chatKey, identity)
                        } catch (_: Exception) {
                            return@withStableAccount null
                        }
                    if (prior?.uploaded == true) return@withStableAccount prior
                    if (prior != null) return@withStableAccount prior
                    val bytes =
                        runCatching { claims.seal(chatId, displayName, identity, chatKey) }.getOrNull()
                            ?: return@withStableAccount null
                    try {
                        store.savePending(communityId, chatId, PRIVATE_KIND, chatKey, identity, bytes)
                        PendingPrivateClaim(bytes, false)
                    } catch (_: Exception) {
                        bytes.fill(0)
                        null
                    }
                } ?: return@withLock PrivateClaimPublicationStatus.PENDING
            if (pending.uploaded) {
                pending.fileBytes.fill(0)
                return@withLock PrivateClaimPublicationStatus.UPLOADED
            }
            val mayStart = barrier.withStableAccount { isFresh() }
            if (!mayStart) {
                pending.fileBytes.fill(0)
                return@withLock PrivateClaimPublicationStatus.PENDING
            }
            try {
                val result =
                    try {
                        service.publishSelf(pending.fileBytes, chatId)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                barrier.withStableAccount {
                    if (!isFresh()) return@withStableAccount PrivateClaimPublicationStatus.PENDING
                    if (result == MembershipPublishOutcome.Published) {
                        runCatching { store.markUploaded(communityId, chatId, PRIVATE_KIND, chatKey, identity, pending.fileBytes) }
                        PrivateClaimPublicationStatus.UPLOADED
                    } else {
                        PrivateClaimPublicationStatus.PENDING
                    }
                }
            } finally {
                pending.fileBytes.fill(0)
            }
        }
    }

    private companion object {
        const val PRIVATE_KIND = "private"
    }
}
