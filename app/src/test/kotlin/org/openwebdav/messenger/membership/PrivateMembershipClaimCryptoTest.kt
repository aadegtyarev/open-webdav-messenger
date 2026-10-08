package org.openwebdav.messenger.membership

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.Envelope

class PrivateMembershipClaimCryptoTest {
    private val native = CryptoTestSupport.native()
    private val identityCrypto = IdentityCrypto(native)
    private val identity = identityCrypto.generateIdentity()
    private val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 7 })
    private val codec = PrivateMembershipClaimCodec(identityCrypto)
    private val aead = Aead(native)
    private val claimAead = PrivateMembershipAead(aead)
    private val crypto = PrivateMembershipClaimCrypto(claimAead, codec)

    @Test
    fun claim_round_trip_binds_chat_identity_and_key_possession() {
        val bytes = crypto.seal("chat_01", "Alice", identity, key)
        val opened = crypto.open(bytes, "chat_01", key) as ClaimParseResult.Verified
        assertEquals("Alice", opened.claim.displayName)
        assertTrue(opened.claim.copySigningPublicKey().contentEquals(identity.copySignPublic()))
        assertTrue(crypto.open(bytes, "other", key) == ClaimParseResult.Rejected)
        val otherKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 8 })
        assertTrue(crypto.open(bytes, "chat_01", otherKey) == ClaimParseResult.Rejected)
    }

    @Test
    fun wrong_domain_or_wire_version_associated_data_is_rejected() {
        val id = "chat_01".toByteArray(Charsets.UTF_8)
        val validClaim = signedClaim()
        val versionSuffix = byteArrayOf(0, PrivateMembershipFormat.VERSION) + length(id) + id
        val wrongDomain = "owdm/other-claim/aead".toByteArray(Charsets.UTF_8) + versionSuffix
        val wrongVersion = "owdm/private-membership/aead".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 2) + length(id) + id

        for (aad in listOf(wrongDomain, wrongVersion)) {
            val blob = aead.sealWithAssociatedData(key, aad, validClaim)
            val ciphertext = Envelope.frame(Envelope.CODEC_NONE, blob)
            assertTrue(crypto.open(ciphertext, "chat_01", key) == ClaimParseResult.Rejected)
        }
    }

    @Test
    fun blank_display_name_is_a_valid_canonical_claim() {
        val bytes = crypto.seal("chat_01", " \u2003\t", identity, key)
        val opened = crypto.open(bytes, "chat_01", key) as ClaimParseResult.Verified

        assertEquals("", opened.claim.displayName)
    }

    @Test
    fun display_name_canonicalization_is_deterministic_and_byte_bounded() {
        val secret = identity.copySignSecret()
        try {
            val signing = identity.copySignPublic()
            val box = identity.copyBoxPublic()
            val canonical = PrivateMembershipClaim("chat_01", "Alice", signing, box)
            val padded = PrivateMembershipClaim("chat_01", "\u2003Alice \t", signing, box)
            assertArrayEquals(codec.sign(canonical, secret), codec.sign(padded, secret))

            val max = PrivateMembershipClaim("chat_01", "é".repeat(128), signing, box)
            val maxParsed = codec.parseAndVerify(codec.sign(max, secret)) as ClaimParseResult.Verified
            assertEquals("é".repeat(128), maxParsed.claim.displayName)
            val tooLong = PrivateMembershipClaim("chat_01", "é".repeat(129), signing, box)
            assertTrue(runCatching { codec.sign(tooLong, secret) }.isFailure)
        } finally {
            secret.fill(0)
        }
    }

    @Test
    fun altered_ciphertext_and_claim_signature_fail_closed() {
        val bytes = crypto.seal("chat_01", "Alice", identity, key)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(crypto.open(bytes, "chat_01", key) == ClaimParseResult.Rejected)

        val payload = signedClaim()
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        val forged = claimAead.seal("chat_01", key, payload)
        assertTrue(crypto.open(forged, "chat_01", key) == ClaimParseResult.Rejected)
    }

    private fun signedClaim(): ByteArray {
        val secret = identity.copySignSecret()
        return try {
            codec.sign(PrivateMembershipClaim("chat_01", "Alice", identity.copySignPublic(), identity.copyBoxPublic()), secret)
        } finally {
            secret.fill(0)
        }
    }

    private fun length(bytes: ByteArray): ByteArray = byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte())
}
