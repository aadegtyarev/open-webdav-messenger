package org.openwebdav.messenger.membership

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.identity.IdentityCrypto

class PrivateMembershipClaimCryptoTest {
    private val native = CryptoTestSupport.native()
    private val identityCrypto = IdentityCrypto(native)
    private val identity = identityCrypto.generateIdentity()
    private val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 7 })
    private val codec = PrivateMembershipClaimCodec(identityCrypto)
    private val crypto = PrivateMembershipClaimCrypto(MessageCrypto(Aead(native)), codec)

    @Test
    fun claim_round_trip_binds_chat_identity_and_key_possession() {
        val bytes = crypto.seal("chat_01", "Alice", identity, key)
        val opened = crypto.open(bytes, "chat_01", key) as ClaimParseResult.Verified
        assertEquals("Alice", opened.claim.displayName)
        assertTrue(opened.claim.copySigningPublicKey().contentEquals(identity.copySignPublic()))
        assertTrue(crypto.open(bytes, "other", key) == ClaimParseResult.Rejected)
        assertTrue(crypto.open(bytes, "chat_01", ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 8 })) == ClaimParseResult.Rejected)
    }

    @Test
    fun altered_ciphertext_and_claim_signature_fail_closed() {
        val bytes = crypto.seal("chat_01", "Alice", identity, key)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(crypto.open(bytes, "chat_01", key) == ClaimParseResult.Rejected)

        val payload =
            codec.sign(
                PrivateMembershipClaim("chat_01", "Alice", identity.copySignPublic(), identity.copyBoxPublic()),
                identity.copySignSecret(),
            )
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        val forged = MessageCrypto(Aead(native)).sealEnvelope(key, payload)
        assertTrue(crypto.open(forged, "chat_01", key) == ClaimParseResult.Rejected)
    }
}
