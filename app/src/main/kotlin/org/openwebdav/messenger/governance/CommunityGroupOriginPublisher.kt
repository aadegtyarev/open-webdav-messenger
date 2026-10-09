package org.openwebdav.messenger.governance

import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult
import java.security.SecureRandom

internal class CommunityGroupOriginPublisher(
    private val writer: CommunityGovernanceWriter,
    private val identityCrypto: IdentityCrypto,
) {
    suspend fun publish(
        communityId: String,
        chatId: String,
        access: ChatAccess,
        creator: Identity,
        communityKey: ChatKey,
    ): Boolean {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val secret = creator.copySignSecret()
        val signedOrigin =
            try {
                CommunityGroupOriginCodec.sign(
                    CommunityGroupOrigin(
                        communityId,
                        chatId,
                        access,
                        creator.copySignPublic(),
                        creator.copyBoxPublic(),
                        nonce,
                    ),
                    secret,
                    identityCrypto,
                )
            } finally {
                nonce.fill(0)
                secret.fill(0)
            }
        return try {
            val reference = writer.seal(GovernanceObjectKind.GROUP_ORIGIN, communityId, communityKey, signedOrigin)
            writer.publish(reference) is WebDavResult.Success
        } finally {
            signedOrigin.fill(0)
        }
    }

    private companion object {
        const val NONCE_BYTES = 32
    }
}
