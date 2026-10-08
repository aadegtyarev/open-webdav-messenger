package org.openwebdav.messenger.membership

/** Append-only claim publication seam used by the idempotent publisher and concurrency tests. */
internal fun interface PrivateClaimWriter {
    suspend fun publishSelf(
        fileBytes: ByteArray,
        chatId: String,
    ): MembershipPublishOutcome
}
