package org.openwebdav.messenger.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityDmOriginCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun dm_origin_is_canonical_context_bound_and_initiator_signed() {
        val initiator = crypto.generateIdentity()
        val peer = crypto.generateIdentity()
        val chatId = "0123456789abcdef0123456789abcdef"
        val origin =
            CommunityDmOrigin(
                "community-1",
                chatId,
                listOf(
                    CommunityDmParticipant(peer.copySignPublic(), peer.copyBoxPublic()),
                    CommunityDmParticipant(initiator.copySignPublic(), initiator.copyBoxPublic()),
                ),
                initiator.copySignPublic(),
                ByteArray(32) { 8 },
            )
        val bytes = CommunityDmOriginCodec.sign(origin, initiator.copySignSecret(), crypto)
        val verified = CommunityDmOriginCodec.verify(bytes, "community-1", chatId, crypto)

        assertEquals(2, verified?.participants?.size)
        assertTrue(verified!!.initiatorSigningPublicKey.contentEquals(initiator.copySignPublic()))
        assertNull(CommunityDmOriginCodec.verify(bytes, "other-community", chatId, crypto))
        assertNull(CommunityDmOriginCodec.verify(bytes, "community-1", "other-chat", crypto))
        val tampered = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(CommunityDmOriginCodec.verify(tampered, "community-1", chatId, crypto))
    }
}
