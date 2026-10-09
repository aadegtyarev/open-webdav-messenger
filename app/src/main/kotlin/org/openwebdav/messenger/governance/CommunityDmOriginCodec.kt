package org.openwebdav.messenger.governance

import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.Base32
import java.security.MessageDigest

internal object CommunityDmOriginCodec {
    fun sign(
        origin: CommunityDmOrigin,
        signingSecret: ByteArray,
        crypto: IdentityCrypto,
    ): ByteArray {
        val body = CommunityDmOriginWire.encodeBody(origin)
        return body + crypto.sign(CommunityDmOriginWire.signatureInput(body), signingSecret)
    }

    fun contentAddress(bytes: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_CHARS)

    fun verify(
        bytes: ByteArray,
        communityId: String,
        chatId: String,
        crypto: IdentityCrypto,
    ): CommunityDmOrigin? {
        if (!GovernanceRecordSupport.isValidCommunityId(communityId)) return null
        val decoded = CommunityDmOriginWire.decode(bytes) ?: return null
        if (decoded.origin.communityId != communityId || decoded.origin.chatId != chatId) return null
        return decoded.origin.takeIf {
            crypto.verify(decoded.signature, CommunityDmOriginWire.signatureInput(decoded.body), it.initiatorSigningPublicKey)
        }
    }

    private const val HASH_CHARS = 32
}
