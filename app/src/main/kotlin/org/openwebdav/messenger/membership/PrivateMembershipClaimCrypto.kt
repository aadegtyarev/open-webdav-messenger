package org.openwebdav.messenger.membership

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.OpenResult
import org.openwebdav.messenger.identity.Identity

/** Ed25519 identity proof inside chat-key AEAD, so disk observers see only ciphertext. */
internal class PrivateMembershipClaimCrypto(
    private val aead: PrivateMembershipAead,
    private val codec: PrivateMembershipClaimCodec,
) {
    fun seal(
        chatId: String,
        displayName: String,
        identity: Identity,
        chatKey: ChatKey,
    ): ByteArray {
        val signingSecret = identity.copySignSecret()
        val claim = PrivateMembershipClaim(chatId, displayName, identity.copySignPublic(), identity.copyBoxPublic())
        return try {
            aead.seal(chatId, chatKey, codec.sign(claim, signingSecret))
        } finally {
            signingSecret.fill(0)
        }
    }

    fun open(
        bytes: ByteArray,
        expectedChatId: String,
        chatKey: ChatKey,
    ): ClaimParseResult {
        if (bytes.size > PrivateMembershipFormat.MAX_FILE_BYTES) return ClaimParseResult.Rejected
        val opened = aead.open(expectedChatId, chatKey, bytes)
        if (opened !is OpenResult.Opened) return ClaimParseResult.Rejected
        return when (val parsed = codec.parseAndVerify(opened.bytes)) {
            is ClaimParseResult.Verified ->
                if (parsed.claim.chatId == expectedChatId) parsed else ClaimParseResult.Rejected
            ClaimParseResult.Rejected -> ClaimParseResult.Rejected
        }
    }
}
